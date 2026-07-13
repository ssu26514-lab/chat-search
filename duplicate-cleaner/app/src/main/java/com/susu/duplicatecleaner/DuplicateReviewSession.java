package com.susu.duplicatecleaner;

import java.util.ArrayList;
import java.util.List;

final class DuplicateReviewSession {
    private static List<DuplicateScanner.DuplicateGroup> groups = new ArrayList<>();

    private DuplicateReviewSession() {
    }

    static synchronized void set(List<DuplicateScanner.DuplicateGroup> source) {
        groups = new ArrayList<>(source);
    }

    static synchronized List<DuplicateScanner.DuplicateGroup> groups() {
        return new ArrayList<>(groups);
    }

    static synchronized void clear() {
        groups.clear();
    }
}
