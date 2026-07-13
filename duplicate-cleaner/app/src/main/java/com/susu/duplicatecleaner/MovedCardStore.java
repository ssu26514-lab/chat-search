package com.susu.duplicatecleaner;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MovedCardStore {
    private static final String PREFS = "moved_card_sync";
    private static final String KEY_URIS = "uris";

    private MovedCardStore() {
    }

    static synchronized void record(Context context, List<String> uris) {
        if (uris == null || uris.isEmpty()) return;
        Set<String> values = new HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_URIS, new HashSet<>()));
        values.addAll(uris);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_URIS, values).apply();
    }

    static synchronized List<String> consume(Context context) {
        Set<String> values = new HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_URIS, new HashSet<>()));
        if (!values.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_URIS).apply();
        }
        return new ArrayList<>(values);
    }
}
