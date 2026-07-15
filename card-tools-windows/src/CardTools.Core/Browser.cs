using System.Collections.Concurrent;

namespace CardTools.Core;

public static class CharacterCardBrowser
{
    public static async Task<(IReadOnlyList<ParsedCharacterCard> Cards,
        IReadOnlyList<(string Path, string Error)> Failures)> ScanAsync(
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
                cards.Add(card);
            else
                failures.Add((path, reason));
            int done = Interlocked.Increment(ref completed);
            progress?.Report(new ScanProgress(done, files.Length, path,
                "正在读取 PNG / JSON 角色卡"));
            return ValueTask.CompletedTask;
        }).ConfigureAwait(false);
        return (
            cards.OrderBy(card => card.Name, StringComparer.CurrentCultureIgnoreCase)
                .ThenBy(card => card.SourcePath, StringComparer.OrdinalIgnoreCase)
                .ToArray(),
            failures.OrderBy(item => item.Path, StringComparer.OrdinalIgnoreCase)
                .ToArray());
    }
}
