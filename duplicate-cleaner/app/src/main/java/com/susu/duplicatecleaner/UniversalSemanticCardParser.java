package com.susu.duplicatecleaner;

import android.content.ContentResolver;

import org.json.JSONObject;

import java.util.Locale;

final class UniversalSemanticCardParser {
    private UniversalSemanticCardParser() {
    }

    static SemanticCardParser.ParsedPayload parse(
            ContentResolver resolver,
            SemanticCardParser.CardRecord seed) throws Exception {
        String lower = seed.fileName == null ? ""
                : seed.fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json")) {
            return SemanticCardParser.parse(resolver, seed);
        }

        JSONObject root = JsonDocumentReader.readObject(resolver, seed.contentUri());
        RoleCardHeuristics.Result result = RoleCardHeuristics.inspect(root);
        if (!result.roleCard) {
            throw new IllegalArgumentException("JSON 不是角色卡：" + result.reason);
        }

        SemanticCardParser.CardRecord jsonSeed = seed;
        jsonSeed.width = 0;
        jsonSeed.height = 0;
        jsonSeed.imageDataHash = "";
        jsonSeed.chunkKeywords.clear();
        jsonSeed.chunkKeywords.add("JSON 文件");
        String spec = root.optString("spec", "").toLowerCase(Locale.ROOT);
        if (spec.contains("v3")) jsonSeed.hasCcv3 = true;
        else jsonSeed.hasChara = true;
        SemanticCardParser.ParsedPayload payload =
                SemanticCardParser.fromJsonForTest(jsonSeed, root);
        payload.record.regexCount = SemanticRegexExtractor.count(payload.extensions);
        return payload;
    }

    static boolean isJson(SemanticCardParser.CardRecord record) {
        return record != null && record.fileName != null
                && record.fileName.toLowerCase(Locale.ROOT).endsWith(".json");
    }
}
