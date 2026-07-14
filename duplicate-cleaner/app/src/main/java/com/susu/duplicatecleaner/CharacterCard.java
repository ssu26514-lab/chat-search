package com.susu.duplicatecleaner;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    String fileFormat;

    Uri contentUri() {
        return Uri.parse(uri);
    }

    boolean readable() {
        return error == null && persona != null;
    }

    int greetingCount() {
        return greetings == null ? 0 : greetings.size();
    }

    boolean hasCoverImage() {
        if (fileFormat != null && !fileFormat.trim().isEmpty()) {
            return "PNG".equalsIgnoreCase(fileFormat);
        }
        return fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    String formatLabel() {
        if (fileFormat != null && !fileFormat.trim().isEmpty()) return fileFormat;
        if (fileName == null) return "未知";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".png")) return "PNG";
        return "文件";
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
            object.put("fileFormat", formatLabel());
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
        card.fileFormat = object.optString("fileFormat", "");
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
