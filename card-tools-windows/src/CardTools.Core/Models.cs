using System.Text.Json.Nodes;

namespace CardTools.Core;

public enum FileCategory
{
    CharacterCard,
    Preset,
    Beauty,
    WorldBook,
    RegexScript,
    ExtensionPlugin,
    ImageAsset,
    MixedPackage,
    Unknown,
    Damaged
}

public sealed record ClassifiedFile(
    string Path,
    string FileName,
    long Size,
    FileCategory Category,
    string Subtype,
    string Reason,
    string Details,
    int Confidence);

public sealed record ParsedCharacterCard(
    string SourcePath,
    string Format,
    string Name,
    string Persona,
    IReadOnlyList<string> Greetings,
    JsonObject Root,
    JsonObject Data,
    string SemanticHash,
    string PersonaHash,
    string GreetingsHash,
    string WorldBookHash,
    string ExtensionsHash);

public sealed record DuplicateFile(string Path, long Size, string Sha256);

public sealed record DuplicateGroup(
    string GroupId,
    long Size,
    string Sha256,
    IReadOnlyList<DuplicateFile> Files)
{
    public string RecommendedKeeper => Files
        .OrderBy(file => file.Path.Length)
        .ThenBy(file => file.Path, StringComparer.OrdinalIgnoreCase)
        .First().Path;
}

public sealed record SemanticDuplicateGroup(
    string GroupId,
    string CharacterName,
    string SemanticHash,
    IReadOnlyList<ParsedCharacterCard> Cards)
{
    public string RecommendedKeeper => Cards
        .OrderBy(card => card.Format.Equals("JSON", StringComparison.OrdinalIgnoreCase) ? 0 : 1)
        .ThenBy(card => new FileInfo(card.SourcePath).Length)
        .ThenBy(card => card.SourcePath, StringComparer.OrdinalIgnoreCase)
        .First().SourcePath;
}

public sealed record RelatedVariantGroup(
    string CharacterName,
    IReadOnlyList<ParsedCharacterCard> Cards);

public sealed record SemanticScanResult(
    IReadOnlyList<SemanticDuplicateGroup> ExactContentGroups,
    IReadOnlyList<RelatedVariantGroup> RelatedVariants,
    IReadOnlyList<(string Path, string Error)> Failures);

public sealed record RenamePlan(
    string SourcePath,
    string TargetPath,
    string CharacterName,
    bool NeedsRename,
    string Reason);

public sealed record SafeMoveResult(
    string SourcePath,
    string TargetPath,
    bool Success,
    string Message);

public sealed record DeleteResult(
    string Path,
    bool Success,
    string Message);

public sealed record ScanProgress(int Completed, int Total, string CurrentPath, string Stage);
