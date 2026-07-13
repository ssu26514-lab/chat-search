package com.susu.duplicatecleaner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FavoriteStore {
    private static final String PREFS = "character_card_favorites";
    private static final String KEY_ITEMS = "items";

    private static List<CharacterCard> cachedCards;
    private static Set<String> cachedUris;

    private FavoriteStore() {
    }

    static synchronized List<CharacterCard> load(Context context) {
        ensureLoaded(context);
        return new ArrayList<>(cachedCards);
    }

    static synchronized boolean contains(Context context, String uri) {
        ensureLoaded(context);
        return uri != null && cachedUris.contains(uri);
    }

    static synchronized void add(Context context, CharacterCard card) {
        ensureLoaded(context);
        Map<String, CharacterCard> map = new LinkedHashMap<>();
        for (CharacterCard existing : cachedCards) map.put(existing.uri, existing);
        map.put(card.uri, card);
        save(context, new ArrayList<>(map.values()));
    }

    static synchronized void remove(Context context, String uri) {
        ensureLoaded(context);
        List<CharacterCard> next = new ArrayList<>();
        for (CharacterCard card : cachedCards) {
            if (!uri.equals(card.uri)) next.add(card);
        }
        save(context, next);
    }

    static synchronized void removeMany(Context context, List<String> uris) {
        ensureLoaded(context);
        Set<String> removing = new HashSet<>(uris);
        List<CharacterCard> next = new ArrayList<>();
        for (CharacterCard card : cachedCards) {
            if (!removing.contains(card.uri)) next.add(card);
        }
        save(context, next);
    }

    static synchronized void clear(Context context) {
        save(context, new ArrayList<>());
    }

    private static void ensureLoaded(Context context) {
        if (cachedCards != null && cachedUris != null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ITEMS, "[]");
        List<CharacterCard> result = new ArrayList<>();
        Set<String> uris = new HashSet<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                CharacterCard card = CharacterCard.fromFavoriteJson(object);
                if (card.uri == null || card.uri.isEmpty()) continue;
                result.add(card);
                uris.add(card.uri);
            }
        } catch (Exception ignored) {
        }
        cachedCards = result;
        cachedUris = uris;
    }

    private static void save(Context context, List<CharacterCard> cards) {
        cachedCards = new ArrayList<>(cards);
        cachedUris = new HashSet<>();
        for (CharacterCard card : cachedCards) {
            if (card.uri != null) cachedUris.add(card.uri);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, CharacterCard.toJsonArray(cards).toString())
                .apply();
    }
}
