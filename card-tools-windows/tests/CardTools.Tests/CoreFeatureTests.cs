using CardTools.Core;
using System.Buffers.Binary;
using System.Text;
using System.Text.Json.Nodes;

namespace CardTools.Tests;

public sealed class CoreFeatureTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(),
        "CardToolsTests", Guid.NewGuid().ToString("N"));

    public CoreFeatureTests()
    {
        Directory.CreateDirectory(_root);
    }

    [Fact]
    public void JsonCharacterCardIsParsedWithAllGreetings()
    {
        string path = Path.Combine(_root, "card.json");
        File.WriteAllText(path, BuildRoleCard().ToJsonString(), Encoding.UTF8);

        ParsedCharacterCard card = CharacterCardParser.Parse(path);

        Assert.Equal("测试角色", card.Name);
        Assert.Equal("JSON", card.Format);
        Assert.Equal(3, card.Greetings.Count);
        Assert.Contains("完整角色描述", card.Persona, StringComparison.Ordinal);
    }

    [Fact]
    public void PngAndJsonCopiesHaveTheSameSemanticFingerprint()
    {
        JsonObject payload = BuildRoleCard();
        string jsonPath = Path.Combine(_root, "card.json");
        string pngPath = Path.Combine(_root, "card.png");
        File.WriteAllText(jsonPath, payload.ToJsonString(), Encoding.UTF8);
        WriteCardPng(pngPath, payload);

        ParsedCharacterCard json = CharacterCardParser.Parse(jsonPath);
        ParsedCharacterCard png = CharacterCardParser.Parse(pngPath);

        Assert.Equal(json.SemanticHash, png.SemanticHash);
        Assert.Equal(json.PersonaHash, png.PersonaHash);
        Assert.Equal(json.GreetingsHash, png.GreetingsHash);
    }

    [Fact]
    public void MetadataAndZeroWidthWatermarksDoNotChangeSemanticFingerprint()
    {
        JsonObject left = BuildRoleCard();
        JsonObject right = BuildRoleCard();
        JsonObject data = right["data"]!.AsObject();
        data["creator_notes"] = "新版说明\u200b\u200c";
        data["creation_date"] = "2026-07-15";
        data["_build"] = "different-build";
        data["description"] = "完整角色描述\u200b";
        string leftPath = Path.Combine(_root, "left.json");
        string rightPath = Path.Combine(_root, "right.json");
        File.WriteAllText(leftPath, left.ToJsonString(), Encoding.UTF8);
        File.WriteAllText(rightPath, right.ToJsonString(), Encoding.UTF8);

        ParsedCharacterCard leftCard = CharacterCardParser.Parse(leftPath);
        ParsedCharacterCard rightCard = CharacterCardParser.Parse(rightPath);

        Assert.Equal(leftCard.SemanticHash, rightCard.SemanticHash);
    }

    [Fact]
    public void RealGreetingChangeProducesDifferentSemanticFingerprint()
    {
        JsonObject left = BuildRoleCard();
        JsonObject right = BuildRoleCard();
        right["data"]!["alternate_greetings"]!.AsArray().Add("真正新增的开场白");
        string leftPath = Path.Combine(_root, "left.json");
        string rightPath = Path.Combine(_root, "right.json");
        File.WriteAllText(leftPath, left.ToJsonString(), Encoding.UTF8);
        File.WriteAllText(rightPath, right.ToJsonString(), Encoding.UTF8);

        ParsedCharacterCard leftCard = CharacterCardParser.Parse(leftPath);
        ParsedCharacterCard rightCard = CharacterCardParser.Parse(rightPath);

        Assert.NotEqual(leftCard.SemanticHash, rightCard.SemanticHash);
        Assert.NotEqual(leftCard.GreetingsHash, rightCard.GreetingsHash);
    }

    [Fact]
    public void ClassifierSeparatesRoleCardPresetThemeWorldBookAndRegex()
    {
        string role = WriteJson("role.json", BuildRoleCard());
        string preset = WriteJson("preset.json", new JsonObject
        {
            ["name"] = "Claude预设",
            ["temperature"] = 0.8,
            ["top_p"] = 0.9,
            ["chat_completion_source"] = "claude",
            ["model"] = "claude",
            ["prompts"] = new JsonArray(),
            ["prompt_order"] = new JsonArray()
        });
        string theme = WriteJson("theme.json", new JsonObject
        {
            ["theme_name"] = "蓝色主题",
            ["main_text_color"] = "#ffffff",
            ["blur_strength"] = 8,
            ["font_scale"] = 1.0,
            ["chat_width"] = 80,
            ["custom_css"] = "body{}"
        });
        string book = WriteJson("book.json", new JsonObject
        {
            ["entries"] = new JsonArray
            {
                new JsonObject
                {
                    ["keys"] = new JsonArray("测试"),
                    ["content"] = "世界书内容"
                }
            },
            ["scan_depth"] = 4
        });
        string regex = WriteJson("regex.json", new JsonObject
        {
            ["regex_scripts"] = new JsonArray
            {
                new JsonObject
                {
                    ["scriptName"] = "替换",
                    ["findRegex"] = "测试",
                    ["replaceString"] = "替换"
                }
            }
        });

        Assert.Equal(FileCategory.CharacterCard,
            TavernFileClassifier.Classify(role).Category);
        Assert.Equal(FileCategory.Preset,
            TavernFileClassifier.Classify(preset).Category);
        Assert.Equal(FileCategory.Beauty,
            TavernFileClassifier.Classify(theme).Category);
        Assert.Equal(FileCategory.WorldBook,
            TavernFileClassifier.Classify(book).Category);
        Assert.Equal(FileCategory.RegexScript,
            TavernFileClassifier.Classify(regex).Category);
    }

    [Fact]
    public async Task StrictDuplicateScannerOnlyGroupsIdenticalBytes()
    {
        File.WriteAllBytes(Path.Combine(_root, "a.bin"), new byte[] { 1, 2, 3, 4 });
        File.WriteAllBytes(Path.Combine(_root, "b.bin"), new byte[] { 1, 2, 3, 4 });
        File.WriteAllBytes(Path.Combine(_root, "c.bin"), new byte[] { 1, 2, 3, 5 });

        IReadOnlyList<DuplicateGroup> groups =
            await StrictDuplicateScanner.ScanAsync(_root);

        DuplicateGroup group = Assert.Single(groups);
        Assert.Equal(2, group.Files.Count);
        Assert.DoesNotContain(group.Files,
            file => file.Path.EndsWith("c.bin", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task SafeMoveCopiesVerifiesAndRemovesSource()
    {
        string sourceFolder = Path.Combine(_root, "source");
        string targetFolder = Path.Combine(_root, "target");
        Directory.CreateDirectory(sourceFolder);
        string source = Path.Combine(sourceFolder, "角色卡.json");
        File.WriteAllText(source, BuildRoleCard().ToJsonString(), Encoding.UTF8);
        string originalHash = await Hashing.Sha256FileAsync(source);

        SafeMoveResult result = await SafeFileOperations.MoveVerifiedAsync(
            source, targetFolder);

        Assert.True(result.Success, result.Message);
        Assert.False(File.Exists(source));
        Assert.True(File.Exists(result.TargetPath));
        Assert.Equal(originalHash, await Hashing.Sha256FileAsync(result.TargetPath));
    }

    [Fact]
    public void RenamePreviewUsesInternalNameAndResolvesCollisions()
    {
        string folder = Path.Combine(_root, "rename");
        Directory.CreateDirectory(folder);
        JsonObject first = BuildRoleCard();
        JsonObject second = BuildRoleCard();
        File.WriteAllText(Path.Combine(folder, "001.json"), first.ToJsonString(), Encoding.UTF8);
        File.WriteAllText(Path.Combine(folder, "002.json"), second.ToJsonString(), Encoding.UTF8);

        IReadOnlyList<RenamePlan> plans = RenameService.Preview(folder);

        Assert.Equal(2, plans.Count);
        Assert.Contains(plans, plan => Path.GetFileName(plan.TargetPath) == "测试角色.json");
        Assert.Contains(plans, plan => Path.GetFileName(plan.TargetPath) == "测试角色(1).json");
    }

    [Fact]
    public async Task SemanticScannerSeparatesExactContentAndVariants()
    {
        string folder = Path.Combine(_root, "semantic");
        Directory.CreateDirectory(folder);
        JsonObject baseCard = BuildRoleCard();
        JsonObject variant = BuildRoleCard();
        variant["data"]!["alternate_greetings"]!.AsArray().Add("新版新增开场白");
        File.WriteAllText(Path.Combine(folder, "same.json"),
            baseCard.ToJsonString(), Encoding.UTF8);
        WriteCardPng(Path.Combine(folder, "same.png"), baseCard);
        File.WriteAllText(Path.Combine(folder, "variant.json"),
            variant.ToJsonString(), Encoding.UTF8);

        SemanticScanResult result = await SemanticDuplicateScanner.ScanAsync(folder);

        SemanticDuplicateGroup exact = Assert.Single(result.ExactContentGroups);
        Assert.Equal(2, exact.Cards.Count);
        RelatedVariantGroup related = Assert.Single(result.RelatedVariants);
        Assert.Equal(3, related.Cards.Count);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private string WriteJson(string name, JsonObject value)
    {
        string path = Path.Combine(_root, name);
        File.WriteAllText(path, value.ToJsonString(), Encoding.UTF8);
        return path;
    }

    private static JsonObject BuildRoleCard() => new()
    {
        ["spec"] = "chara_card_v3",
        ["spec_version"] = "3.0",
        ["data"] = new JsonObject
        {
            ["name"] = "测试角色",
            ["description"] = "完整角色描述",
            ["personality"] = "安静、理性",
            ["scenario"] = "测试场景",
            ["first_mes"] = "第一条开场白",
            ["alternate_greetings"] = new JsonArray("第二条开场白", "第三条开场白"),
            ["character_book"] = new JsonObject
            {
                ["entries"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["name"] = "基础设定",
                        ["keys"] = new JsonArray("测试"),
                        ["content"] = "基础世界书内容"
                    }
                }
            },
            ["extensions"] = new JsonObject
            {
                ["regex_scripts"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["scriptName"] = "基础替换",
                        ["findRegex"] = "测试",
                        ["replaceString"] = "替换"
                    }
                }
            }
        }
    };

    private static void WriteCardPng(string path, JsonObject payload)
    {
        byte[] json = Encoding.UTF8.GetBytes(payload.ToJsonString());
        string base64 = Convert.ToBase64String(json);
        byte[] keyword = Encoding.Latin1.GetBytes("chara");
        byte[] text = Encoding.Latin1.GetBytes(base64);
        byte[] chunk = new byte[keyword.Length + 1 + text.Length];
        keyword.CopyTo(chunk, 0);
        text.CopyTo(chunk, keyword.Length + 1);

        using FileStream stream = File.Create(path);
        stream.Write(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });
        WriteChunk(stream, "tEXt", chunk);
        WriteChunk(stream, "IEND", Array.Empty<byte>());
    }

    private static void WriteChunk(Stream stream, string type, byte[] data)
    {
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, data.Length);
        stream.Write(length);
        stream.Write(Encoding.ASCII.GetBytes(type));
        stream.Write(data);
        stream.Write(new byte[4]);
    }
}
