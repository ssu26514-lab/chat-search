using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace CardTools.Core;

public static class CharacterCardParser
{
    private const int MaxTextChunkBytes = 128 * 1024 * 1024;

    private static readonly HashSet<string> IgnoredMetadataKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        "creator_notes", "creatorcomment", "creator_comment", "creator",
        "creation_date", "created_at", "modified_at", "modification_date",
        "character_version", "version_name", "source", "source_url",
        "_build", "build", "build_id", "wm", "watermark", "watermark_id",
        "exported_at", "last_modified", "avatar", "avatar_path"
    };

    public static bool IsSupportedCardFile(string path)
    {
        string extension = Path.GetExtension(path);
        return extension.Equals(".png", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".json", StringComparison.OrdinalIgnoreCase);
    }

    public static bool TryParse(string path, out ParsedCharacterCard? card, out string reason)
    {
        try
        {
            card = Parse(path);
            reason = "已识别角色卡";
            return true;
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidDataException
            or JsonException
            or FormatException
            or ArgumentException)
        {
            card = null;
            reason = exception.Message;
            return false;
        }
    }

    public static ParsedCharacterCard Parse(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        string fullPath = Path.GetFullPath(path);
        string extension = Path.GetExtension(fullPath);
        JsonObject root;
        string format;

        if (extension.Equals(".json", StringComparison.OrdinalIgnoreCase))
        {
            root = ReadJsonObject(fullPath);
            format = "JSON";
        }
        else if (extension.Equals(".png", StringComparison.OrdinalIgnoreCase))
        {
            root = ReadPngCardPayload(fullPath);
            format = "PNG";
        }
        else
        {
            throw new InvalidDataException("只支持 PNG 或 JSON 角色卡");
        }

        if (!LooksLikeCharacterCard(root, out string detectionReason))
        {
            throw new InvalidDataException($"不是角色卡：{detectionReason}");
        }

        JsonObject data = root["data"] as JsonObject ?? root;
        string name = ReadString(data, "name");
        if (string.IsNullOrWhiteSpace(name))
        {
            name = Path.GetFileNameWithoutExtension(fullPath);
        }

        var personaParts = new List<string>();
        AddSection(personaParts, "角色描述", ReadString(data, "description", "char_persona"));
        AddSection(personaParts, "性格", ReadString(data, "personality"));
        AddSection(personaParts, "场景", ReadString(data, "scenario"));
        AddSection(personaParts, "示例对话", ReadString(data, "mes_example"));
        string persona = personaParts.Count == 0
            ? "（没有读取到人设内容）"
            : string.Join(Environment.NewLine + Environment.NewLine, personaParts);

        var greetings = new List<string>();
        AddGreeting(greetings, ReadString(data, "first_mes"));
        if (data["alternate_greetings"] is JsonArray alternatives)
        {
            foreach (JsonNode? node in alternatives)
            {
                if (node is JsonValue value && value.TryGetValue(out string? text))
                {
                    AddGreeting(greetings, text);
                }
            }
        }

        JsonObject effective = BuildEffectiveContent(data);
        JsonObject personaObject = BuildPersonaContent(data);
        JsonArray greetingArray = BuildGreetingContent(data);
        JsonNode? worldBook = data["character_book"]?.DeepClone();
        JsonNode? extensions = CleanNode(data["extensions"]);

        return new ParsedCharacterCard(
            fullPath,
            format,
            name.Trim(),
            persona,
            greetings,
            root,
            data,
            HashCanonical(effective),
            HashCanonical(personaObject),
            HashCanonical(greetingArray),
            HashCanonical(worldBook),
            HashCanonical(extensions));
    }

    public static bool LooksLikeCharacterCard(JsonObject root, out string reason)
    {
        JsonObject data = root["data"] as JsonObject ?? root;
        string spec = ReadString(root, "spec").ToLowerInvariant();
        if (spec.Contains("chara_card_v2", StringComparison.Ordinal)
            || spec.Contains("chara_card_v3", StringComparison.Ordinal)
            || spec.Contains("character_card", StringComparison.Ordinal))
        {
            reason = $"检测到明确规范标记 {ReadString(root, "spec")}";
            return true;
        }

        bool hasName = HasText(data, "name");
        bool hasIdentity = HasText(data, "description")
            || HasText(data, "char_persona")
            || HasText(data, "personality")
            || HasText(data, "scenario");
        bool hasDialogue = HasText(data, "first_mes")
            || HasText(data, "mes_example")
            || data["alternate_greetings"] is JsonArray;

        int presetSignals = CountKeys(root,
            "temperature", "top_p", "top_k", "min_p", "typical_p",
            "chat_completion_source", "prompt_order", "prompts", "sampler_order",
            "repetition_penalty", "input_sequence", "output_sequence", "story_string");
        int themeSignals = CountKeys(root,
            "theme_name", "main_text_color", "blur_strength", "font_scale",
            "chat_width", "custom_css", "border_color", "background_color");

        if (presetSignals >= 4)
        {
            reason = "结构更像生成或聊天预设";
            return false;
        }
        if (themeSignals >= 4)
        {
            reason = "结构更像主题美化配置";
            return false;
        }
        if (hasName && hasIdentity && hasDialogue)
        {
            reason = "检测到角色名、人设和开场白组合结构";
            return true;
        }

        reason = "缺少足够的角色名、人设和开场白组合字段";
        return false;
    }

    public static JsonObject ReadJsonObject(string path)
    {
        string text = File.ReadAllText(path, Encoding.UTF8).TrimStart('\uFEFF');
        JsonNode? node = JsonNode.Parse(text);
        return node as JsonObject
            ?? throw new InvalidDataException("JSON 顶层不是对象结构");
    }

    private static JsonObject ReadPngCardPayload(string path)
    {
        using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            1024 * 1024, FileOptions.SequentialScan);
        Span<byte> signature = stackalloc byte[8];
        stream.ReadExactly(signature);
        ReadOnlySpan<byte> expected = new byte[]
        {
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        if (!signature.SequenceEqual(expected))
        {
            throw new InvalidDataException("扩展名是 PNG，但文件内容不是 PNG");
        }

        JsonObject? firstValid = null;
        while (stream.Position < stream.Length)
        {
            Span<byte> header = stackalloc byte[8];
            stream.ReadExactly(header);
            int length = BinaryPrimitives.ReadInt32BigEndian(header[..4]);
            if (length < 0)
            {
                throw new InvalidDataException("PNG 数据块长度异常");
            }
            string type = Encoding.ASCII.GetString(header[4..]);
            if (length > MaxTextChunkBytes && type is "tEXt" or "zTXt" or "iTXt")
            {
                throw new InvalidDataException("PNG 角色卡文本数据块超过 128 MB");
            }

            byte[] data = new byte[length];
            stream.ReadExactly(data);
            stream.Seek(4, SeekOrigin.Current); // CRC

            if (type is "tEXt" or "zTXt" or "iTXt")
            {
                (string Keyword, string Text)? entry = ParsePngText(type, data);
                if (entry is not null
                    && (entry.Value.Keyword.Equals("chara", StringComparison.OrdinalIgnoreCase)
                        || entry.Value.Keyword.Equals("ccv3", StringComparison.OrdinalIgnoreCase)))
                {
                    try
                    {
                        JsonObject payload = DecodeCardPayload(entry.Value.Text);
                        firstValid ??= payload;
                        if (entry.Value.Keyword.Equals("ccv3", StringComparison.OrdinalIgnoreCase))
                        {
                            return payload;
                        }
                    }
                    catch (Exception exception) when (exception is JsonException or FormatException)
                    {
                        // Continue: another chara/ccv3 chunk may still be valid.
                    }
                }
            }

            if (type.Equals("IEND", StringComparison.Ordinal))
            {
                break;
            }
        }

        return firstValid
            ?? throw new InvalidDataException("PNG 内没有可解析的 chara 或 ccv3 角色卡数据");
    }

    private static (string Keyword, string Text)? ParsePngText(string type, byte[] data)
    {
        int firstZero = Array.IndexOf(data, (byte)0);
        if (firstZero <= 0)
        {
            return null;
        }
        string keyword = Encoding.Latin1.GetString(data, 0, firstZero);

        if (type == "tEXt")
        {
            return (keyword, Encoding.Latin1.GetString(data, firstZero + 1,
                data.Length - firstZero - 1));
        }

        if (type == "zTXt")
        {
            int start = firstZero + 2;
            if (start > data.Length)
            {
                return null;
            }
            return (keyword, Encoding.Latin1.GetString(Inflate(data.AsSpan(start))));
        }

        int position = firstZero + 1;
        if (position + 2 > data.Length)
        {
            return null;
        }
        int compressionFlag = data[position];
        position += 2;
        int languageEnd = Array.IndexOf(data, (byte)0, position);
        if (languageEnd < 0)
        {
            return null;
        }
        position = languageEnd + 1;
        int translatedEnd = Array.IndexOf(data, (byte)0, position);
        if (translatedEnd < 0)
        {
            return null;
        }
        position = translatedEnd + 1;
        ReadOnlySpan<byte> textBytes = data.AsSpan(position);
        byte[] decoded = compressionFlag == 1 ? Inflate(textBytes) : textBytes.ToArray();
        return (keyword, Encoding.UTF8.GetString(decoded));
    }

    private static byte[] Inflate(ReadOnlySpan<byte> data)
    {
        using var input = new MemoryStream(data.ToArray(), writable: false);
        using var inflater = new ZLibStream(input, CompressionMode.Decompress);
        using var output = new MemoryStream();
        inflater.CopyTo(output);
        return output.ToArray();
    }

    private static JsonObject DecodeCardPayload(string encoded)
    {
        string trimmed = encoded.Trim();
        if (trimmed.StartsWith('{'))
        {
            return JsonNode.Parse(trimmed) as JsonObject
                ?? throw new JsonException("角色卡 JSON 顶层不是对象");
        }
        byte[] bytes = Convert.FromBase64String(trimmed);
        string json = Encoding.UTF8.GetString(bytes).TrimStart('\uFEFF').Trim();
        return JsonNode.Parse(json) as JsonObject
            ?? throw new JsonException("Base64 解码后的角色卡不是 JSON 对象");
    }

    private static JsonObject BuildEffectiveContent(JsonObject data)
    {
        JsonNode? cleaned = CleanNode(data);
        return cleaned as JsonObject ?? new JsonObject();
    }

    private static JsonObject BuildPersonaContent(JsonObject data)
    {
        string[] keys =
        {
            "name", "nickname", "description", "char_persona", "personality",
            "scenario", "mes_example", "system_prompt", "post_history_instructions",
            "group_only_greetings"
        };
        var result = new JsonObject();
        foreach (string key in keys)
        {
            if (data[key] is JsonNode value)
            {
                result[key] = CleanNode(value);
            }
        }
        return result;
    }

    private static JsonArray BuildGreetingContent(JsonObject data)
    {
        var result = new JsonArray();
        string first = ReadString(data, "first_mes").Trim();
        if (first.Length > 0)
        {
            result.Add(first);
        }
        if (data["alternate_greetings"] is JsonArray alternatives)
        {
            foreach (JsonNode? item in alternatives)
            {
                if (item is JsonValue value && value.TryGetValue(out string? text)
                    && !string.IsNullOrWhiteSpace(text))
                {
                    result.Add(text.Trim());
                }
            }
        }
        return result;
    }

    private static JsonNode? CleanNode(JsonNode? node)
    {
        if (node is null)
        {
            return null;
        }
        if (node is JsonObject sourceObject)
        {
            var target = new JsonObject();
            foreach ((string key, JsonNode? value) in sourceObject
                .OrderBy(pair => pair.Key, StringComparer.Ordinal))
            {
                if (IgnoredMetadataKeys.Contains(key))
                {
                    continue;
                }
                JsonNode? cleaned = CleanNode(value);
                if (cleaned is not null)
                {
                    target[key] = cleaned;
                }
            }
            return target;
        }
        if (node is JsonArray sourceArray)
        {
            var target = new JsonArray();
            foreach (JsonNode? value in sourceArray)
            {
                target.Add(CleanNode(value));
            }
            return target;
        }
        if (node is JsonValue jsonValue && jsonValue.TryGetValue(out string? text))
        {
            return JsonValue.Create(NormalizeText(text));
        }
        return node.DeepClone();
    }

    private static string HashCanonical(JsonNode? node)
    {
        string canonical = node is null
            ? "null"
            : node.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(canonical));
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string NormalizeText(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }
        var builder = new StringBuilder(text.Length);
        foreach (char character in text.Replace("\r\n", "\n", StringComparison.Ordinal)
            .Replace('\r', '\n'))
        {
            if (character is '\u200B' or '\u200C' or '\u200D' or '\u2060' or '\uFEFF')
            {
                continue;
            }
            builder.Append(character);
        }
        return builder.ToString().Trim();
    }

    private static void AddSection(ICollection<string> parts, string title, string value)
    {
        string cleaned = NormalizeText(value);
        if (cleaned.Length > 0)
        {
            parts.Add($"【{title}】{Environment.NewLine}{cleaned}");
        }
    }

    private static void AddGreeting(ICollection<string> greetings, string? value)
    {
        string cleaned = NormalizeText(value);
        if (cleaned.Length > 0)
        {
            greetings.Add(cleaned);
        }
    }

    private static bool HasText(JsonObject source, string key) =>
        !string.IsNullOrWhiteSpace(ReadString(source, key));

    private static string ReadString(JsonObject source, params string[] keys)
    {
        foreach (string key in keys)
        {
            if (source[key] is JsonValue value && value.TryGetValue(out string? text))
            {
                return text ?? string.Empty;
            }
        }
        return string.Empty;
    }

    private static int CountKeys(JsonObject source, params string[] keys)
    {
        var existing = new HashSet<string>(source.Select(pair => pair.Key),
            StringComparer.OrdinalIgnoreCase);
        if (source["data"] is JsonObject data)
        {
            existing.UnionWith(data.Select(pair => pair.Key));
        }
        return keys.Count(existing.Contains);
    }
}
