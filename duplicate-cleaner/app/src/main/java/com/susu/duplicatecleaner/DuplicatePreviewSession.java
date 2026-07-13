package com.susu.duplicatecleaner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DuplicatePreviewSession {
    private static List<DuplicateScanner.DuplicateGroup> groups = new ArrayList<>();
    private static final Map<Integer, Integer> keeperSelections = new HashMap<>();

    private DuplicatePreviewSession() {
    }

    static synchronized void set(List<DuplicateScanner.DuplicateGroup> source) {
        groups = new ArrayList<>(source);
        keeperSelections.clear();
        for (int i = 0; i < groups.size(); i++) keeperSelections.put(i, 0);
    }

    static synchronized List<DuplicateScanner.DuplicateGroup> groups() {
        return new ArrayList<>(groups);
    }

    static synchronized int keeperIndex(int groupIndex) {
        return keeperSelections.getOrDefault(groupIndex, 0);
    }

    static synchronized void selectKeeper(int groupIndex, int fileIndex) {
        if (groupIndex < 0 || groupIndex >= groups.size()) return;
        int max = groups.get(groupIndex).files.size();
        if (fileIndex < 0 || fileIndex >= max) return;
        keeperSelections.put(groupIndex, fileIndex);
    }

    static synchronized List<DuplicateScanner.DuplicateGroup> groupsWithSelections() {
        List<DuplicateScanner.DuplicateGroup> result = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            DuplicateScanner.DuplicateGroup group = groups.get(groupIndex);
            List<DuplicateScanner.FileEntry> reordered = new ArrayList<>(group.files);
            int keeper = keeperSelections.getOrDefault(groupIndex, 0);
            if (keeper > 0 && keeper < reordered.size()) {
                DuplicateScanner.FileEntry selected = reordered.remove(keeper);
                reordered.add(0, selected);
            }
            result.add(new DuplicateScanner.DuplicateGroup(reordered));
        }
        return result;
    }

    static synchronized void clear() {
        groups.clear();
        keeperSelections.clear();
    }
}
