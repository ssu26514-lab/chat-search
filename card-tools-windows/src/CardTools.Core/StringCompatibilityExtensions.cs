namespace CardTools.Core;

internal static class StringCompatibilityExtensions
{
    public static bool EndsWith(this string value, char character,
        StringComparison comparisonType) =>
        value.EndsWith(character.ToString(), comparisonType);
}
