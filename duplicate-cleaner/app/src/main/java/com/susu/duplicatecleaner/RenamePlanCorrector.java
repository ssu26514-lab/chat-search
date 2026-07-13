package com.susu.duplicatecleaner;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RenamePlanCorrector {
    private RenamePlanCorrector() {
    }

    static int keepAlreadyValidNumberedNames(List<CardRenamer.RenameItem> pending,
                                             List<CardRenamer.RenameItem> all) {
        int corrected = 0;
        if (all == null) return corrected;

        for (CardRenamer.RenameItem item : all) {
            if (item == null || item.error != null || item.characterName == null) continue;
            if (!isAcceptableExistingName(item)) continue;

            if (item.needsRename) corrected++;
            item.targetName = item.originalName;
            item.needsRename = false;
        }

        if (pending != null) {
            Iterator<CardRenamer.RenameItem> iterator = pending.iterator();
            while (iterator.hasNext()) {
                CardRenamer.RenameItem item = iterator.next();
                if (item != null && !item.needsRename) iterator.remove();
            }
        }
        return corrected;
    }

    static boolean isAcceptableExistingName(CardRenamer.RenameItem item) {
        String originalBase = baseName(item.originalName);
        String character = normalizeSpaces(item.characterName);
        if (originalBase == null || character == null) return false;

        String normalizedOriginal = normalizeSpaces(originalBase);
        if (normalizedOriginal.equalsIgnoreCase(character)) return true;

        String pattern = "^" + Pattern.quote(character)
                + "\\s*[（(]\\s*[0-9０-９]+\\s*[)）]$";
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(normalizedOriginal)
                .matches();
    }

    private static String baseName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String normalizeSpaces(String value) {
        if (value == null) return null;
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
