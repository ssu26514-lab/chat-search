package com.susu.duplicatecleaner;

import java.util.ArrayList;
import java.util.List;

final class RenamePreviewSession {
    private static List<CardRenamer.RenameItem> renameItems = new ArrayList<>();
    private static List<CardRenamer.RenameItem> allItems = new ArrayList<>();

    private RenamePreviewSession() {
    }

    static synchronized void set(List<CardRenamer.RenameItem> pending,
                                 List<CardRenamer.RenameItem> all) {
        renameItems = new ArrayList<>(pending);
        allItems = new ArrayList<>(all);
    }

    static synchronized List<CardRenamer.RenameItem> renameItems() {
        return new ArrayList<>(renameItems);
    }

    static synchronized List<CardRenamer.RenameItem> allItems() {
        return new ArrayList<>(allItems);
    }

    static synchronized void clear() {
        renameItems.clear();
        allItems.clear();
    }
}
