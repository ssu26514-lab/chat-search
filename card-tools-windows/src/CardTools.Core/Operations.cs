using System.Security.Cryptography;
using System.Text;

namespace CardTools.Core;

public static class SafeFileOperations
{
    public static async Task<SafeMoveResult> MoveVerifiedAsync(
        string sourcePath,
        string targetFolder,
        CancellationToken cancellationToken = default)
    {
        string source = Path.GetFullPath(sourcePath);
        string targetRoot = Path.GetFullPath(targetFolder);
        if (!File.Exists(source))
            return new SafeMoveResult(source, string.Empty, false, "源文件不存在");
        Directory.CreateDirectory(targetRoot);

        string target = UniquePath(targetRoot, Path.GetFileName(source));
        string temporary = target + ".cardtools-copying";
        try
        {
            await using (FileStream input = new(source, FileMode.Open, FileAccess.Read,
                FileShare.Read, 1024 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            await using (FileStream output = new(temporary, FileMode.CreateNew,
                FileAccess.Write, FileShare.None, 1024 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                await input.CopyToAsync(output, 1024 * 1024, cancellationToken)
                    .ConfigureAwait(false);
                await output.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            var sourceInfo = new FileInfo(source);
            var copiedInfo = new FileInfo(temporary);
            if (sourceInfo.Length != copiedInfo.Length)
            {
                File.Delete(temporary);
                return new SafeMoveResult(source, target, false,
                    "复制后的文件大小与源文件不一致，已撤销");
            }

            string sourceHash = await Hashing.Sha256FileAsync(source, cancellationToken)
                .ConfigureAwait(false);
            string copiedHash = await Hashing.Sha256FileAsync(temporary, cancellationToken)
                .ConfigureAwait(false);
            if (!sourceHash.Equals(copiedHash, StringComparison.Ordinal))
            {
                File.Delete(temporary);
                return new SafeMoveResult(source, target, false,
                    "复制后的 SHA-256 与源文件不一致，已撤销");
            }

            File.Move(temporary, target);
            File.Delete(source);
            return new SafeMoveResult(source, target, true, "移动完成并通过 SHA-256 校验");
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or OperationCanceledException)
        {
            TryDelete(temporary);
            return new SafeMoveResult(source, target, false, exception.Message);
        }
    }

    public static async Task<IReadOnlyList<DeleteResult>> DeleteExactDuplicatesAsync(
        DuplicateGroup group,
        string keeperPath,
        IEnumerable<string> selectedPaths,
        CancellationToken cancellationToken = default)
    {
        string keeper = Path.GetFullPath(keeperPath);
        if (!group.Files.Any(file => PathEquals(file.Path, keeper)))
            throw new ArgumentException("保留文件不属于这个重复组", nameof(keeperPath));
        if (!File.Exists(keeper))
            throw new FileNotFoundException("保留文件不存在", keeper);

        string keeperHash = await Hashing.Sha256FileAsync(keeper, cancellationToken)
            .ConfigureAwait(false);
        if (!keeperHash.Equals(group.Sha256, StringComparison.Ordinal))
            throw new InvalidOperationException("保留文件在扫描后发生变化，已停止删除");

        var results = new List<DeleteResult>();
        foreach (string selected in selectedPaths.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            cancellationToken.ThrowIfCancellationRequested();
            string path = Path.GetFullPath(selected);
            if (PathEquals(path, keeper))
            {
                results.Add(new DeleteResult(path, false, "保留文件不会被删除"));
                continue;
            }
            if (!group.Files.Any(file => PathEquals(file.Path, path)))
            {
                results.Add(new DeleteResult(path, false, "文件不属于这个重复组"));
                continue;
            }
            if (!File.Exists(path))
            {
                results.Add(new DeleteResult(path, false, "文件已经不存在"));
                continue;
            }
            string currentHash = await Hashing.Sha256FileAsync(path, cancellationToken)
                .ConfigureAwait(false);
            if (!currentHash.Equals(keeperHash, StringComparison.Ordinal))
            {
                results.Add(new DeleteResult(path, false,
                    "扫描后内容发生变化，已安全跳过"));
                continue;
            }
            try
            {
                File.Delete(path);
                results.Add(new DeleteResult(path, true, "已删除字节级重复副本"));
            }
            catch (Exception exception) when (exception is IOException
                or UnauthorizedAccessException)
            {
                results.Add(new DeleteResult(path, false, exception.Message));
            }
        }
        return results;
    }

    public static async Task<IReadOnlyList<DeleteResult>> DeleteSemanticDuplicatesAsync(
        SemanticDuplicateGroup group,
        string keeperPath,
        IEnumerable<string> selectedPaths,
        CancellationToken cancellationToken = default)
    {
        string keeper = Path.GetFullPath(keeperPath);
        ParsedCharacterCard? keeperCard = group.Cards.FirstOrDefault(card =>
            PathEquals(card.SourcePath, keeper));
        if (keeperCard is null)
            throw new ArgumentException("保留文件不属于这个有效内容重复组", nameof(keeperPath));
        ParsedCharacterCard currentKeeper = CharacterCardParser.Parse(keeper);
        if (!currentKeeper.SemanticHash.Equals(group.SemanticHash, StringComparison.Ordinal))
            throw new InvalidOperationException("保留文件在扫描后发生变化，已停止删除");

        var results = new List<DeleteResult>();
        foreach (string selected in selectedPaths.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            cancellationToken.ThrowIfCancellationRequested();
            string path = Path.GetFullPath(selected);
            if (PathEquals(path, keeper))
            {
                results.Add(new DeleteResult(path, false, "保留文件不会被删除"));
                continue;
            }
            if (!group.Cards.Any(card => PathEquals(card.SourcePath, path)))
            {
                results.Add(new DeleteResult(path, false, "文件不属于这个有效内容重复组"));
                continue;
            }
            try
            {
                ParsedCharacterCard current = CharacterCardParser.Parse(path);
                if (!current.SemanticHash.Equals(currentKeeper.SemanticHash,
                    StringComparison.Ordinal))
                {
                    results.Add(new DeleteResult(path, false,
                        "重新解析后有效内容不再一致，已安全跳过"));
                    continue;
                }
                File.Delete(path);
                results.Add(new DeleteResult(path, true,
                    "已删除有效内容完全一致的副本"));
            }
            catch (Exception exception) when (exception is IOException
                or UnauthorizedAccessException
                or InvalidDataException
                or System.Text.Json.JsonException
                or FormatException)
            {
                results.Add(new DeleteResult(path, false, exception.Message));
            }
        }
        return results;
    }

    public static string UniquePath(string targetFolder, string fileName)
    {
        string baseName = Path.GetFileNameWithoutExtension(fileName);
        string extension = Path.GetExtension(fileName);
        string candidate = Path.Combine(targetFolder, fileName);
        int counter = 1;
        while (File.Exists(candidate) || Directory.Exists(candidate))
        {
            candidate = Path.Combine(targetFolder, $"{baseName}({counter}){extension}");
            counter++;
        }
        return candidate;
    }

    private static bool PathEquals(string left, string right) =>
        string.Equals(Path.GetFullPath(left), Path.GetFullPath(right),
            StringComparison.OrdinalIgnoreCase);

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}

public static class RenameService
{
    private static readonly char[] InvalidFileNameCharacters =
        Path.GetInvalidFileNameChars();

    public static IReadOnlyList<RenamePlan> Preview(string rootFolder,
        CancellationToken cancellationToken = default)
    {
        string[] files = FileDiscovery.EnumerateFiles(rootFolder, cancellationToken)
            .Where(CharacterCardParser.IsSupportedCardFile)
            .ToArray();
        var result = new List<RenamePlan>();
        var reservedTargets = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (string path in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!CharacterCardParser.TryParse(path, out ParsedCharacterCard? card,
                out string reason) || card is null)
            {
                result.Add(new RenamePlan(path, path, string.Empty, false,
                    $"跳过：{reason}"));
                continue;
            }

            string directory = Path.GetDirectoryName(path)
                ?? throw new InvalidOperationException("无法确定文件所在文件夹");
            string extension = Path.GetExtension(path);
            string safeName = SanitizeFileName(card.Name);
            if (safeName.Length == 0)
                safeName = "未命名角色卡";

            string proposed = Path.Combine(directory, safeName + extension);
            proposed = ReserveUniquePath(proposed, path, reservedTargets);
            bool needsRename = !PathEquals(path, proposed);
            string planReason = needsRename
                ? "使用角色卡内部名称；重名时自动追加编号"
                : "文件名已经正确";
            result.Add(new RenamePlan(path, proposed, card.Name, needsRename, planReason));
            reservedTargets.Add(proposed);
        }
        return result;
    }

    public static IReadOnlyList<RenamePlan> Apply(IEnumerable<RenamePlan> plans)
    {
        var applied = new List<RenamePlan>();
        foreach (RenamePlan plan in plans.Where(plan => plan.NeedsRename))
        {
            if (!File.Exists(plan.SourcePath))
            {
                applied.Add(plan with { NeedsRename = false, Reason = "源文件不存在，未改名" });
                continue;
            }
            string target = plan.TargetPath;
            if (File.Exists(target) && !PathEquals(plan.SourcePath, target))
            {
                target = SafeFileOperations.UniquePath(
                    Path.GetDirectoryName(target)!, Path.GetFileName(target));
            }
            try
            {
                File.Move(plan.SourcePath, target);
                applied.Add(plan with { TargetPath = target, Reason = "改名完成" });
            }
            catch (Exception exception) when (exception is IOException
                or UnauthorizedAccessException)
            {
                applied.Add(plan with
                {
                    NeedsRename = false,
                    TargetPath = plan.SourcePath,
                    Reason = $"改名失败：{exception.Message}"
                });
            }
        }
        return applied;
    }

    public static string SanitizeFileName(string value)
    {
        var builder = new StringBuilder(value.Trim().Length);
        foreach (char character in value.Trim())
        {
            if (InvalidFileNameCharacters.Contains(character)
                || char.IsControl(character))
                continue;
            builder.Append(character);
        }
        string cleaned = builder.ToString().Trim().TrimEnd('.');
        return cleaned.Length > 120 ? cleaned[..120].TrimEnd() : cleaned;
    }

    private static string ReserveUniquePath(string proposed, string source,
        HashSet<string> reserved)
    {
        string directory = Path.GetDirectoryName(proposed)!;
        string baseName = Path.GetFileNameWithoutExtension(proposed);
        string extension = Path.GetExtension(proposed);
        string candidate = proposed;
        int counter = 1;
        while ((!PathEquals(candidate, source) && File.Exists(candidate))
            || reserved.Contains(candidate))
        {
            candidate = Path.Combine(directory, $"{baseName}({counter}){extension}");
            counter++;
        }
        return candidate;
    }

    private static bool PathEquals(string left, string right) =>
        string.Equals(Path.GetFullPath(left), Path.GetFullPath(right),
            StringComparison.OrdinalIgnoreCase);
}

public static class CardDifference
{
    public static IReadOnlyList<(string Component, string Status)> Compare(
        ParsedCharacterCard left, ParsedCharacterCard right)
    {
        return new[]
        {
            ("有效内容", Same(left.SemanticHash, right.SemanticHash,
                "完全一致", "存在变化")),
            ("人设", Same(left.PersonaHash, right.PersonaHash,
                "完全一致", "存在变化")),
            ("开场白", Same(left.GreetingsHash, right.GreetingsHash,
                $"完全一致，共 {left.Greetings.Count} 个",
                $"左侧 {left.Greetings.Count} 个，右侧 {right.Greetings.Count} 个")),
            ("世界书", Same(left.WorldBookHash, right.WorldBookHash,
                "完全一致", "存在变化")),
            ("扩展 / 正则", Same(left.ExtensionsHash, right.ExtensionsHash,
                "完全一致", "存在变化")),
            ("文件格式", left.Format.Equals(right.Format, StringComparison.OrdinalIgnoreCase)
                ? $"相同：{left.Format}"
                : $"不同：{left.Format} ↔ {right.Format}"),
            ("文件大小", $"{new FileInfo(left.SourcePath).Length:N0} B ↔ {new FileInfo(right.SourcePath).Length:N0} B")
        };
    }

    private static string Same(string left, string right,
        string equalText, string differentText) =>
        left.Equals(right, StringComparison.Ordinal) ? equalText : differentText;
}
