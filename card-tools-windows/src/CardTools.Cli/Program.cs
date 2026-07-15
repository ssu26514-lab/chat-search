using CardTools.Core;

Console.OutputEncoding = System.Text.Encoding.UTF8;

if (args.Length < 2)
{
    PrintHelp();
    return 2;
}

string command = args[0].Trim().ToLowerInvariant();
string folder = Path.GetFullPath(args[1]);
using var cancellation = new CancellationTokenSource();
Console.CancelKeyPress += (_, eventArgs) =>
{
    eventArgs.Cancel = true;
    cancellation.Cancel();
};

var progress = new Progress<ScanProgress>(value =>
{
    Console.Write($"\r{value.Stage} {value.Completed}/{value.Total}  {Trim(value.CurrentPath, 70),-70}");
});

try
{
    switch (command)
    {
        case "duplicates":
            await RunStrictDuplicates(folder, progress, cancellation.Token);
            break;
        case "semantic":
            await RunSemanticDuplicates(folder, progress, cancellation.Token);
            break;
        case "classify":
            await RunClassification(folder, progress, cancellation.Token);
            break;
        case "rename-preview":
            RunRenamePreview(folder, cancellation.Token);
            break;
        case "rename-apply":
            if (!args.Contains("--yes", StringComparer.OrdinalIgnoreCase))
            {
                Console.Error.WriteLine("rename-apply 必须附加 --yes，避免误改名。");
                return 3;
            }
            RunRenameApply(folder, cancellation.Token);
            break;
        default:
            PrintHelp();
            return 2;
    }
    return 0;
}
catch (OperationCanceledException)
{
    Console.WriteLine("\n任务已取消。");
    return 130;
}
catch (Exception exception)
{
    Console.Error.WriteLine($"\n执行失败：{exception.Message}");
    return 1;
}

static async Task RunStrictDuplicates(string folder,
    IProgress<ScanProgress> progress, CancellationToken token)
{
    IReadOnlyList<DuplicateGroup> groups = await StrictDuplicateScanner.ScanAsync(
        folder, progress, token);
    Console.WriteLine($"\n发现 {groups.Count} 个字节级重复组。\n");
    foreach (DuplicateGroup group in groups)
    {
        Console.WriteLine($"[{group.GroupId}] {group.Files.Count} 份 · {group.Size:N0} B");
        Console.WriteLine($"  建议保留：{group.RecommendedKeeper}");
        foreach (DuplicateFile file in group.Files)
            Console.WriteLine($"  - {file.Path}");
        Console.WriteLine();
    }
}

static async Task RunSemanticDuplicates(string folder,
    IProgress<ScanProgress> progress, CancellationToken token)
{
    SemanticScanResult result = await SemanticDuplicateScanner.ScanAsync(
        folder, progress, token);
    Console.WriteLine($"\n有效内容完全一致：{result.ExactContentGroups.Count} 组");
    Console.WriteLine($"同名但内容有变化：{result.RelatedVariants.Count} 组");
    Console.WriteLine($"无法解析：{result.Failures.Count} 个\n");

    foreach (SemanticDuplicateGroup group in result.ExactContentGroups)
    {
        Console.WriteLine($"[{group.GroupId}] {group.CharacterName} · {group.Cards.Count} 份");
        Console.WriteLine($"  建议保留：{group.RecommendedKeeper}");
        foreach (ParsedCharacterCard card in group.Cards)
            Console.WriteLine($"  - [{card.Format}] {card.SourcePath}");
        Console.WriteLine();
    }

    foreach (RelatedVariantGroup group in result.RelatedVariants)
    {
        Console.WriteLine($"[有变化] {group.CharacterName}");
        foreach (ParsedCharacterCard card in group.Cards)
            Console.WriteLine($"  - [{card.Format}] {card.Greetings.Count} 个开场白 · {card.SourcePath}");
        Console.WriteLine();
    }
}

static async Task RunClassification(string folder,
    IProgress<ScanProgress> progress, CancellationToken token)
{
    IReadOnlyList<ClassifiedFile> files = await TavernFileClassifier.ScanAsync(
        folder, progress, token);
    Console.WriteLine($"\n分类完成，共 {files.Count} 个文件。\n");
    foreach (IGrouping<FileCategory, ClassifiedFile> group in files.GroupBy(file => file.Category))
    {
        Console.WriteLine($"== {CategoryText(group.Key)}（{group.Count()}）==");
        foreach (ClassifiedFile file in group)
        {
            Console.WriteLine($"[{file.Confidence}%] {file.Subtype} · {file.FileName}");
            Console.WriteLine($"  为什么：{file.Reason}");
            Console.WriteLine($"  路径：{file.Path}");
        }
        Console.WriteLine();
    }
}

static void RunRenamePreview(string folder, CancellationToken token)
{
    IReadOnlyList<RenamePlan> plans = RenameService.Preview(folder, token);
    RenamePlan[] changes = plans.Where(plan => plan.NeedsRename).ToArray();
    Console.WriteLine($"需要改名 {changes.Length} 个；无需改名或跳过 {plans.Count - changes.Length} 个。\n");
    foreach (RenamePlan plan in changes)
    {
        Console.WriteLine($"原：{plan.SourcePath}");
        Console.WriteLine($"新：{plan.TargetPath}");
        Console.WriteLine();
    }
}

static void RunRenameApply(string folder, CancellationToken token)
{
    IReadOnlyList<RenamePlan> plans = RenameService.Preview(folder, token);
    IReadOnlyList<RenamePlan> applied = RenameService.Apply(plans);
    Console.WriteLine($"完成处理 {applied.Count} 个改名计划。\n");
    foreach (RenamePlan plan in applied)
        Console.WriteLine($"{plan.Reason}：{plan.SourcePath} → {plan.TargetPath}");
}

static string CategoryText(FileCategory category) => category switch
{
    FileCategory.CharacterCard => "角色卡",
    FileCategory.Preset => "预设",
    FileCategory.Beauty => "美化 / 主题",
    FileCategory.WorldBook => "世界书",
    FileCategory.RegexScript => "正则脚本",
    FileCategory.ExtensionPlugin => "插件 / 扩展",
    FileCategory.ImageAsset => "普通图片 / 素材",
    FileCategory.MixedPackage => "混合包 / 压缩包",
    FileCategory.Damaged => "损坏 / 无法读取",
    _ => "无法确定"
};

static string Trim(string text, int max) => text.Length <= max
    ? text : "…" + text[^Math.Max(1, max - 1)..];

static void PrintHelp()
{
    Console.WriteLine("""
CardTools.Cli - Windows 角色卡文件工具

用法：
  CardTools.Cli duplicates <文件夹>
  CardTools.Cli semantic <文件夹>
  CardTools.Cli classify <文件夹>
  CardTools.Cli rename-preview <文件夹>
  CardTools.Cli rename-apply <文件夹> --yes

说明：
  duplicates      字节级严格查重，只生成报告，不自动删除
  semantic        PNG / JSON 角色卡有效内容查重
  classify        分类角色卡、预设、美化、世界书、正则、插件和素材
  rename-preview  生成改名前后对照，不修改文件
  rename-apply    按预览规则原地改名，必须明确附加 --yes
""");
}
