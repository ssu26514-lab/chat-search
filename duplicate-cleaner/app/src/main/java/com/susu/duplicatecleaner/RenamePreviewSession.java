package com.susu.duplicatecleaner;

import java.util.ArrayList;
import java.util.List;

final class RenamePreviewSession {
    private static List<CardRenamer.RenameItem> renameItems = new ArrayList<>();
    private static List<CardRenamer.RenameItem> allItems = new ArrayList<>();
    private static int correctedAlreadyValid;

    private RenamePreviewSession() {
    }

    static synchronized void set(List<CardRenamer.RenameItem> pending,
                                 List<CardRenamer.RenameItem> all) {
        // 在进入预览前再做一次“已经是正确编号名”的校正。
        // 例如内部角色名为“角色A”，文件名已经是“角色A (1).png”，
        // 这本来就是合法的重名版本，不应再被推成“角色A(4).png”。
        correctedAlreadyValid = RenamePlanCorrector.keepAlreadyValidNumberedNames(pending, all);
        renameItems = new ArrayList<>(pending);
        allItems = new ArrayList<>(all);
    }

    static synchronized List<CardRenamer.RenameItem> renameItems() {
        return new ArrayList<>(renameItems);
    }

    static synchronized List<CardRenamer.RenameItem> allItems() {
        return new ArrayList<>(allItems);
    }

    static synchronized int correctedAlreadyValid() {
        return correctedAlreadyValid;
    }

    static synchronized void clear() {
        renameItems.clear();
        allItems.clear();
        correctedAlreadyValid = 0;
    }
}
