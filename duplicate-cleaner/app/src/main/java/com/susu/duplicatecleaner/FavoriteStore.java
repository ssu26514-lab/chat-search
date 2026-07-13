package com.susu.duplicatecleaner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FavoriteStore {
    private static final String PREFS = "character_card_favorites";
    private static final String KEY_ITEMS = "items";

    private FavoriteStore() {
    }

    static synchronized List<CharacterCard> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ITEMS, "[]");
        List<CharacterCard> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                CharacterCard card = CharacterCard.fromFavoriteJson(object);
                if (card.uri == null || card.uri.isEmpty()) continue;
                result.add(card);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static synchronized boolean contains(Context context, String uri) {
        for (CharacterCard card : load(context)) {
            if (uri.equals(card.uri)) return true;
        }
        return false;
    }

    static synchronized void add(Context context, CharacterCard card) {
        Map<String, CharacterCard> map = new LinkedHashMap<>();
        for (CharacterCard existing : load(context)) map.put(existing.uri, existing);
        map.put(card.uri, card);
        save(context, new ArrayList<>(map.values()));
    }

    static synchronized void remove(Context context, String uri) {
        List<CharacterCard> current = load(context);
        List<CharacterCard> next = new ArrayList<>();
        for (CharacterCard card : current) {
            if (!uri.equals(card.uri)) next.add(card);
        }
        save(context, next);
    }

    static synchronized void removeMany(Context context, List<String> uris) {
        List<CharacterCard> current = load(context);
        List<CharacterCard> next = new ArrayList<>();
        for (CharacterCard card : current) {
            if (!uris.contains(card.uri)) next.add(card);
        }
        save(context, next);
    }

    static synchronized void clear(Context context) {
        save(context, new ArrayList<>());
    }

    private static void save(Context context, List<CharacterCard> cards) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, CharacterCard.toJsonArray(cards).toString())
                .apply();
    }
}
