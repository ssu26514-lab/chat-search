using System.Collections.Concurrent;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace CardTools.Core;

public static class FileDiscovery
{
    public static IReadOnlyList<string> EnumerateFiles(string rootFolder,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootFolder);
        string root = Path.GetFullPath(rootFolder);
        if (!Directory.Exists(root))
        {
            throw new DirectoryNotFoundException($"文件夹不存在：{root}");
        }

        var options = new EnumerationOptions
        {
            RecurseSubdirectories = true,
            IgnoreInaccessible = true,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint
        };
        var files = new List<string>();
        foreach (string file in Directory.EnumerateFiles(root, "*", options))
        {
            cancellationToken.ThrowIfCancellationRequested();
            files.Add(file);
        }
        files.Sort(StringComparer.OrdinalIgnoreCase);
        return files;
    }
}

public static class Hashing
{
    public static async Task<string> Sha256FileAsync(string path,
        CancellationToken cancellationToken = default)
    {
        await using FileStream stream = new(path, FileMode.Open, FileAccess.Read,
            FileShare.Read, 1024 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        using var sha = SHA256.Create();
        byte[] hash = await sha.ComputeHashAsync(stream, cancellationToken)
            .ConfigureAwait(false);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }
}

public static class StrictDuplicateScanner
{
    public static async Task<IReadOnlyList<DuplicateGroup>> ScanAsync(
        string rootFolder,
        IProgress<ScanProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        IReadOnlyList<string> files = FileDiscovery.EnumerateFiles(rootFolder,
            cancellationToken);
        var candidates = files
            .Select(path => new FileInfo(path))
            .Where(info => info.Exists)
            .GroupBy(info => info.Length)
            .Where(group => group.Count() > 1)
            .SelectMany(group => group)
            .ToArray();

        var results = new ConcurrentBag<(string Path, long Size, string Hash)>();
        int completed = 0;
        var options = new ParallelOptions
        {
            CancellationToken = cancellationToken,
            MaxDegreeOfParallelism = Math.Max(1,
                Math.Min(Environment.ProcessorCount, 8))
        };

        await Parallel.ForEachAsync(candidates, options, async (info, token) =>
        {
            string hash = await Hashing.Sha256FileAsync(info.FullName, token)
                .ConfigureAwait(false);
            results.Add((info.FullName, info.Length, hash));
            int done = Interlocked.Increment(ref completed);
            progress?.Report(new ScanProgress(done, candidates.Length,
                info.FullName, "正在计算 SHA-256"));
        }).ConfigureAwait(false);

        return results
            .GroupBy(item => (item.Size, item.Hash))
            .Where(group => group.Count() > 1)
            .Select((group, index) => new DuplicateGroup(
                $"D{index + 1:0000}",
                group.Key.Size,
                group.Key.Hash,
                group.Select(item => new DuplicateFile(item.Path, item.Size, item.Hash))
                    .OrderBy(file => file.Path, StringComparer.OrdinalIgnoreCase)
                    .ToArray()))
            .OrderByDescending(group => group.Files.Count)
            .ThenByDescending(group => group.Size)
            .ToArray();
    }
}

public static class SemanticDuplicateScanner
{
    public static async Task<SemanticScanResult> ScanAsync(
        string rootFolder,
        IProgress<ScanProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        string[] files = FileDiscovery.EnumerateFiles(rootFolder, cancellationToken)
            .Where(CharacterCardParser.IsSupportedCardFile)
            .ToArray();
        var cards = new ConcurrentBag<ParsedCharacterCard>();
        var failures = new ConcurrentBag<(string Path, string Error)>();
        int completed = 0;
        var options = new ParallelOptions
        {
            CancellationToken = cancellationToken,
            MaxDegreeOfParallelism = Math.Max(1,
                Math.Min(Environment.ProcessorCount, 6))
        };

        await Parallel.ForEachAsync(files, options, (path, token) =>
        {
            token.ThrowIfCancellationRequested();
            if (CharacterCardParser.TryParse(path, out ParsedCharacterCard? card,
                out string reason) && card is not null)
            {
                cards.Add(card);
            }
            else
            {
                failures.Add((path, reason));
            }
            int done = Interlocked.Increment(ref completed);
            progress?.Report(new ScanProgress(done, files.Length, path,
                "正在解析 PNG / JSON 角色卡"));
            return ValueTask.CompletedTask;
        }).ConfigureAwait(false);

        SemanticDuplicateGroup[] exact = cards
            .GroupBy(card => card.SemanticHash, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select((group, index) => new SemanticDuplicateGroup(
                $"S{index + 1:0000}",
                MostCommonName(group),
                group.Key,
                group.OrderBy(card => card.SourcePath,
                    StringComparer.OrdinalIgnoreCase).ToArray()))
            .OrderByDescending(group => group.Cards.Count)
            .ThenBy(group => group.CharacterName, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        RelatedVariantGroup[] variants = cards
            .GroupBy(card => NormalizeName(card.Name), StringComparer.OrdinalIgnoreCase)
            .Where(group => group.Key.Length > 0
                && group.Select(card => card.SemanticHash).Distinct().Count() > 1)
            .Select(group => new RelatedVariantGroup(
                MostCommonName(group),
                group.OrderBy(card => card.SourcePath,
                    StringComparer.OrdinalIgnoreCase).ToArray()))
            .OrderBy(group => group.CharacterName, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        return new SemanticScanResult(exact, variants,
            failures.OrderBy(item => item.Path, StringComparer.OrdinalIgnoreCase)
                .ToArray());
    }

    private static string NormalizeName(string value)
    {
        string name = value.Trim().ToLowerInvariant();
        while (name.EndsWith(')'))
        {
            int open = name.LastIndexOf('(');
            if (open < 0 || !int.TryParse(name.AsSpan(open + 1,
                    name.Length - open - 2), out _))
            {
                break;
            }
            name = name[..open].TrimEnd();
        }
        return name.Replace("《", string.Empty, StringComparison.Ordinal)
            .Replace("》", string.Empty, StringComparison.Ordinal)
            .Replace(" ", string.Empty, StringComparison.Ordinal);
    }

    private static string MostCommonName(IEnumerable<ParsedCharacterCard> cards) =>
        cards.GroupBy(card => card.Name, StringComparer.OrdinalIgnoreCase)
            .OrderByDescending(group => group.Count())
            .ThenBy(group => group.Key.Length)
            .Select(group => group.Key)
            .FirstOrDefault() ?? "未命名角色卡";
}

public static class TavernFileClassifier
{
    private static readonly string[] SupportedExtensions =
    {
        ".png", ".json", ".css", ".html", ".htm", ".js", ".zip",
        ".jpg", ".jpeg", ".webp", ".gif", ".txt", ".md"
    };

    public static async Task<IReadOnlyList<ClassifiedFile>> ScanAsync(
        string rootFolder,
        IProgress<ScanProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        string[] files = FileDiscovery.EnumerateFiles(rootFolder, cancellationToken)
            .Where(path => SupportedExtensions.Contains(Path.GetExtension(path),
                StringComparer.OrdinalIgnoreCase))
            .ToArray();
        var results = new ConcurrentBag<ClassifiedFile>();
        int completed = 0;
        var options = new ParallelOptions
        {
            CancellationToken = cancellationToken,
            MaxDegreeOfParallelism = Math.Max(1,
                Math.Min(Environment.ProcessorCount, 6))
        };

        await Parallel.ForEachAsync(files, options, (path, token) =>
        {
            token.ThrowIfCancellationRequested();
            results.Add(Classify(path));
            int done = Interlocked.Increment(ref completed);
            progress?.Report(new ScanProgress(done, files.Length, path,
                "正在分类酒馆文件"));
            return ValueTask.CompletedTask;
        }).ConfigureAwait(false);

        return results
            .OrderBy(item => item.Category)
            .ThenBy(item => item.Path, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public static ClassifiedFile Classify(string path)
    {
        var info = new FileInfo(path);
        string extension = info.Extension.ToLowerInvariant();
        try
        {
            return extension switch
            {
                ".png" => ClassifyPng(info),
                ".json" => ClassifyJson(info),
                ".css" => Make(info, FileCategory.Beauty,
                    "自定义 CSS / 样式表", "扩展名为 .css",
                    "界面主题或美化样式", 100),
                ".html" or ".htm" => Make(info, FileCategory.Beauty,
                    "HTML 美化 / 消息模板", "扩展名为 HTML",
                    "可能是界面、消息气泡或模板", 85),
                ".js" => Make(info, FileCategory.ExtensionPlugin,
                    "JavaScript 插件 / 脚本", "扩展名为 .js",
                    "建议与对应 manifest 和 CSS 一起移动", 90),
                ".zip" => ClassifyZip(info),
                ".jpg" or ".jpeg" or ".webp" or ".gif" => Make(info,
                    FileCategory.ImageAsset, "图片素材", "普通图片格式",
                    "可能是背景、头像、封面或美化素材", 100),
                ".txt" or ".md" => Make(info, FileCategory.Unknown,
                    "文本说明 / 无法确定", "仅凭文本后缀不能可靠分类",
                    "可以移动，不应自动删除", 35),
                _ => Make(info, FileCategory.Unknown, "不支持的类型",
                    "当前版本无法可靠识别", "只允许移动", 10)
            };
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidDataException
            or System.Text.Json.JsonException
            or FormatException)
        {
            return Make(info, FileCategory.Damaged, "损坏 / 无法读取",
                exception.Message, "建议移动到待检查文件夹", 100);
        }
    }

    private static ClassifiedFile ClassifyPng(FileInfo info)
    {
        if (CharacterCardParser.TryParse(info.FullName,
            out ParsedCharacterCard? card, out string reason) && card is not null)
        {
            return Make(info, FileCategory.CharacterCard, "PNG 角色卡",
                "检测到可解析的 chara / ccv3", $"角色名：{card.Name}", 100);
        }
        if (reason.Contains("没有可解析的 chara", StringComparison.Ordinal)
            || reason.Contains("没有可解析的 chara 或 ccv3", StringComparison.Ordinal))
        {
            return Make(info, FileCategory.ImageAsset, "普通 PNG 图片 / 素材",
                "没有可解析的角色卡数据块",
                "可能是背景、头像、封面或美化素材", 100);
        }
        return Make(info, FileCategory.Damaged, "疑似损坏 PNG 角色卡",
            reason, "只能移动到待修复文件夹", 95);
    }

    private static ClassifiedFile ClassifyJson(FileInfo info)
    {
        JsonObject root = CharacterCardParser.ReadJsonObject(info.FullName);
        if (CharacterCardParser.LooksLikeCharacterCard(root, out string cardReason))
        {
            JsonObject data = root["data"] as JsonObject ?? root;
            string name = ReadString(data, "name");
            return Make(info, FileCategory.CharacterCard, "JSON 角色卡",
                cardReason, $"角色名：{name}", 100);
        }

        int theme = CountKeys(root, "theme_name", "main_text_color",
            "italics_text_color", "blur_strength", "font_scale", "chat_width",
            "custom_css", "border_color", "background_color");
        int preset = CountKeys(root, "temperature", "top_p", "top_k", "min_p",
            "typical_p", "chat_completion_source", "prompt_order", "prompts",
            "sampler_order", "repetition_penalty", "input_sequence",
            "output_sequence", "story_string", "chat_start");
        int worldBook = CountWorldBookSignals(root);
        int regex = CountRegexObjects(root);
        int plugin = CountKeys(root, "display_name", "loading_order", "requires",
            "optional", "js", "css", "author", "version", "homepage");

        int strong = new[] { theme >= 4, preset >= 4, worldBook >= 3,
            regex > 0, plugin >= 3 }.Count(value => value);
        if (strong > 1)
        {
            return Make(info, FileCategory.MixedPackage, "混合 JSON 包",
                "同时检测到多种强结构，不能安全地只归为一种",
                $"主题 {theme}；预设 {preset}；世界书 {worldBook}；正则 {regex}；插件 {plugin}",
                70);
        }
        if (theme >= 4)
        {
            return Make(info, FileCategory.Beauty, "主题 Theme / 美化配置",
                "检测到颜色、模糊、字体、聊天宽度或 custom_css",
                $"主题特征 {theme} 项", 95);
        }
        if (preset >= 4)
        {
            return Make(info, FileCategory.Preset, PresetSubtype(root),
                "检测到模型、采样参数、提示词顺序或指令模板",
                $"预设特征 {preset} 项", 95);
        }
        if (worldBook >= 3)
        {
            return Make(info, FileCategory.WorldBook, "独立世界书 / Lorebook",
                "检测到 entries、keys 和 content 结构",
                WorldBookDetails(root), 95);
        }
        if (regex > 0)
        {
            return Make(info, FileCategory.RegexScript, "正则脚本集合",
                "检测到匹配式与替换式", $"正则脚本 {regex} 项", 95);
        }
        if (plugin >= 3)
        {
            return Make(info, FileCategory.ExtensionPlugin, "插件 / 扩展 manifest",
                "检测到 display_name、loading_order、js 或 css 等字段",
                $"插件特征 {plugin} 项", 90);
        }
        return Make(info, FileCategory.Unknown, "无法确定的 JSON",
            "没有达到角色卡、预设、美化、世界书、正则或插件的可靠阈值",
            "只允许移动，不自动删除", 35);
    }

    private static ClassifiedFile ClassifyZip(FileInfo info)
    {
        int entries = 0;
        int json = 0;
        int styles = 0;
        int scripts = 0;
        int images = 0;
        var examples = new List<string>();
        using ZipArchive archive = ZipFile.OpenRead(info.FullName);
        foreach (ZipArchiveEntry entry in archive.Entries.Take(500))
        {
            if (entry.FullName.EndsWith('/', StringComparison.Ordinal))
            {
                continue;
            }
            entries++;
            if (examples.Count < 10)
            {
                examples.Add(entry.FullName);
            }
            string extension = Path.GetExtension(entry.FullName).ToLowerInvariant();
            if (extension == ".json") json++;
            else if (extension is ".css" or ".html" or ".htm") styles++;
            else if (extension == ".js") scripts++;
            else if (extension is ".png" or ".jpg" or ".jpeg" or ".webp" or ".gif")
                images++;
        }
        return Make(info, FileCategory.MixedPackage, "ZIP 压缩包 / 整合包",
            "压缩包可能同时包含角色卡、预设、美化和素材",
            $"文件 {entries}；JSON {json}；样式 {styles}；脚本 {scripts}；图片 {images}\n示例：{string.Join("、", examples)}",
            100);
    }

    private static string PresetSubtype(JsonObject root)
    {
        if (root.ContainsKey("chat_completion_source")
            || root.ContainsKey("prompt_order") || root.ContainsKey("prompts"))
            return "聊天补全 / OpenAI 类预设";
        if (root.ContainsKey("input_sequence") || root.ContainsKey("output_sequence"))
            return "Instruct 指令预设";
        if (root.ContainsKey("story_string") || root.ContainsKey("chat_start"))
            return "Context 上下文预设";
        if (root.ContainsKey("sampler_order")
            || root.ContainsKey("repetition_penalty"))
            return "TextGen / Kobold 生成预设";
        return "生成参数预设";
    }

    private static int CountWorldBookSignals(JsonObject root)
    {
        int count = 0;
        if (root["entries"] is JsonArray or JsonObject) count += 2;
        if (root.ContainsKey("scan_depth") || root.ContainsKey("token_budget")) count++;
        JsonObject? first = root["entries"] is JsonArray array
            ? array.OfType<JsonObject>().FirstOrDefault()
            : null;
        if (first is not null)
        {
            if (first.ContainsKey("keys") || first.ContainsKey("key")) count++;
            if (first.ContainsKey("content")) count++;
        }
        return count;
    }

    private static string WorldBookDetails(JsonObject root) => root["entries"] switch
    {
        JsonArray array => $"世界书条目 {array.Count}",
        JsonObject obj => $"世界书条目 {obj.Count}",
        _ => "检测到世界书结构"
    };

    private static int CountRegexObjects(JsonNode? node)
    {
        if (node is JsonObject obj)
        {
            bool pattern = obj.Any(pair => NormalizeKey(pair.Key) is
                "findregex" or "regex" or "pattern" or "searchregex" or "matchregex");
            bool replacement = obj.Any(pair => NormalizeKey(pair.Key) is
                "replacestring" or "replacement" or "replace" or "substitution");
            if (pattern || replacement) return 1;
            return obj.Sum(pair => CountRegexObjects(pair.Value));
        }
        if (node is JsonArray array)
            return array.Sum(CountRegexObjects);
        return 0;
    }

    private static string NormalizeKey(string key) => key.ToLowerInvariant()
        .Replace("_", string.Empty, StringComparison.Ordinal)
        .Replace("-", string.Empty, StringComparison.Ordinal)
        .Replace(" ", string.Empty, StringComparison.Ordinal);

    private static int CountKeys(JsonObject root, params string[] keys)
    {
        var existing = new HashSet<string>(root.Select(pair => pair.Key),
            StringComparer.OrdinalIgnoreCase);
        if (root["data"] is JsonObject data)
            existing.UnionWith(data.Select(pair => pair.Key));
        return keys.Count(existing.Contains);
    }

    private static string ReadString(JsonObject source, string key) =>
        source[key] is JsonValue value && value.TryGetValue(out string? text)
            ? text ?? string.Empty : string.Empty;

    private static ClassifiedFile Make(FileInfo info, FileCategory category,
        string subtype, string reason, string details, int confidence) =>
        new(info.FullName, info.Name, info.Exists ? info.Length : -1,
            category, subtype, reason, details, confidence);
}
