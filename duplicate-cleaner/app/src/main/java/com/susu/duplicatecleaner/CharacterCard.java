package com.susu.duplicatecleaner;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class CharacterCard {
    String key;
    String treeUri;
    String uri;
    String parentDocumentId;
    String path;
    String fileName;
    String characterName;
    String persona;
    List<String> greetings = new ArrayList<>();
    long size;
    long modified;
    String error;

    Uri contentUri() {
        return Uri.parse(uri);
    }

    boolean readable() {
        return error == null && persona != null;
    }

    int greetingCount() {
        return greetings == null ? 0 : greetings.size();
    }

    JSONObject toFavoriteJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("key", key);
            object.put("treeUri", treeUri);
            object.put("uri", uri);
            object.put("parentDocumentId", parentDocumentId);
            object.put("path", path);
            object.put("fileName", fileName);
            object.put("characterName", characterName);
            object.put("size", size);
            object.put("modified", modified);
            object.put("greetingCount", greetingCount());
        } catch (Exception ignored) {
        }
        return object;
    }

    static CharacterCard fromFavoriteJson(JSONObject object) {
        CharacterCard card = new CharacterCard();
        card.key = object.optString("key", object.optString("uri", ""));
        card.treeUri = object.optString("treeUri", "");
        card.uri = object.optString("uri", "");
        card.parentDocumentId = object.optString("parentDocumentId", "");
        card.path = object.optString("path", object.optString("fileName", ""));
        card.fileName = object.optString("fileName", "");
        card.characterName = object.optString("characterName", card.fileName);
        card.size = object.optLong("size", -1L);
        card.modified = object.optLong("modified", 0L);
        int greetingCount = object.optInt("greetingCount", 0);
        card.greetings = new ArrayList<>();
        for (int i = 0; i < greetingCount; i++) card.greetings.add("");
        return card;
    }

    static JSONArray toJsonArray(List<CharacterCard> cards) {
        JSONArray array = new JSONArray();
        for (CharacterCard card : cards) array.put(card.toFavoriteJson());
        return array;
    }
}
