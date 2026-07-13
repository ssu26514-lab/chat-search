package com.susu.duplicatecleaner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SemanticRegexExtractor {
    private SemanticRegexExtractor() {
    }

    static Map<String, String> collect(Object extensions) {
        Map<String, String> result = new LinkedHashMap<>();
        collectRecursive(extensions, "扩展", result);
        return result;
    }

    static int count(Object extensions) {
        return collect(extensions).size();
    }

    private static void collectRecursive(Object value, String path,
                                         Map<String, String> result) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (isActualRegexScript(object)) {
                String label = firstNonEmpty(
                        object.optString("scriptName", ""),
                        object.optString("script_name", ""),
                        object.optString("name", ""),
                        object.optString("id", ""), path);
                String unique = label;
                int suffix = 2;
                while (result.containsKey(unique)) unique = label + " #" + suffix++;
                result.put(unique, SemanticCardParser.canonical(object));
                return;
            }
            for (String key : sortedKeys(object)) {
                collectRecursive(object.opt(key), path + "/" + key, result);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectRecursive(array.opt(i), path + "[" + i + "]", result);
            }
        }
    }

    private static boolean isActualRegexScript(JSONObject object) {
        boolean hasPattern = false;
        boolean hasReplacement = false;
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            String key = normalizeKey(iterator.next());
            if (key.equals("findregex") || key.equals("regex")
                    || key.equals("pattern") || key.equals("searchregex")
                    || key.equals("matchregex") || key.equals("findpattern")) {
                hasPattern = true;
            }
            if (key.equals("replacestring") || key.equals("replacement")
                    || key.equals("replace") || key.equals("substitution")) {
                hasReplacement = true;
            }
        }
        return hasPattern || (hasReplacement && (object.has("scriptName")
                || object.has("script_name") || object.has("name")));
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        return keys;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "未命名正则";
    }
}
