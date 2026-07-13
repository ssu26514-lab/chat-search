package com.susu.duplicatecleaner;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SemanticDuplicateSession {
    private static final Map<String, SemanticCardScanner.Group> GROUPS = new LinkedHashMap<>();
    private static final Map<String, Integer> KEEPER_INDEX = new HashMap<>();
    private static final Map<String, JSONObject> TEST_PAYLOADS = new HashMap<>();
    private static List<SemanticCardScanner.Failure> failures = new ArrayList<>();

    private SemanticDuplicateSession() {
    }

    static synchronized void set(SemanticCardScanner.ScanResult result) {
        GROUPS.clear();
        KEEPER_INDEX.clear();
        TEST_PAYLOADS.clear();
        failures = new ArrayList<>(result.failures);
        for (SemanticCardScanner.Group group : result.exactGroups) putGroup(group);
        for (SemanticCardScanner.Group group : result.variantGroups) putGroup(group);
    }

    static synchronized void setGroupsForTest(List<SemanticCardScanner.Group> groups,
                                              Map<String, JSONObject> payloads) {
        GROUPS.clear();
        KEEPER_INDEX.clear();
        TEST_PAYLOADS.clear();
        failures = new ArrayList<>();
        for (SemanticCardScanner.Group group : groups) putGroup(group);
        if (payloads != null) TEST_PAYLOADS.putAll(payloads);
    }

    private static void putGroup(SemanticCardScanner.Group group) {
        GROUPS.put(group.id, group);
        KEEPER_INDEX.put(group.id, recommendedKeeper(group));
    }

    static synchronized SemanticCardScanner.Group group(String id) {
        return GROUPS.get(id);
    }

    static synchronized List<SemanticCardScanner.Group> groups(
            SemanticCardScanner.GroupType type) {
        List<SemanticCardScanner.Group> result = new ArrayList<>();
        for (SemanticCardScanner.Group group : GROUPS.values()) {
            if (group.type == type) result.add(group);
        }
        return result;
    }

    static synchronized List<SemanticCardScanner.Failure> failures() {
        return new ArrayList<>(failures);
    }

    static synchronized int keeperIndex(String groupId) {
        SemanticCardScanner.Group group = GROUPS.get(groupId);
        if (group == null || group.cards.isEmpty()) return 0;
        int index = KEEPER_INDEX.getOrDefault(groupId, 0);
        return Math.max(0, Math.min(index, group.cards.size() - 1));
    }

    static synchronized void selectKeeper(String groupId, int index) {
        SemanticCardScanner.Group group = GROUPS.get(groupId);
        if (group == null || index < 0 || index >= group.cards.size()) return;
        KEEPER_INDEX.put(groupId, index);
    }

    static synchronized SemanticCardParser.CardRecord keeper(String groupId) {
        SemanticCardScanner.Group group = GROUPS.get(groupId);
        if (group == null || group.cards.isEmpty()) return null;
        return group.cards.get(keeperIndex(groupId));
    }

    static synchronized void removeDeleted(String groupId, List<String> deletedUris) {
        SemanticCardScanner.Group group = GROUPS.get(groupId);
        if (group == null || deletedUris == null || deletedUris.isEmpty()) return;
        List<SemanticCardParser.CardRecord> remaining = new ArrayList<>();
        for (SemanticCardParser.CardRecord card : group.cards) {
            if (!deletedUris.contains(card.uri)) remaining.add(card);
        }
        if (remaining.size() < 2) {
            GROUPS.remove(groupId);
            KEEPER_INDEX.remove(groupId);
            return;
        }
        SemanticCardScanner.Group replacement = new SemanticCardScanner.Group(
                group.id, group.type, group.title, remaining, group.safeDelete);
        GROUPS.put(groupId, replacement);
        KEEPER_INDEX.put(groupId, recommendedKeeper(replacement));
    }

    static synchronized JSONObject testPayload(String uri) {
        return TEST_PAYLOADS.get(uri);
    }

    static synchronized void clear() {
        GROUPS.clear();
        KEEPER_INDEX.clear();
        TEST_PAYLOADS.clear();
        failures.clear();
    }

    static String recommendationReason(SemanticCardParser.CardRecord card) {
        if (card == null) return "";
        if (card.payloadConflict) return "内部 chara / ccv3 内容冲突，不建议自动保留";
        if (card.hasChara && card.hasCcv3) return "兼容性优先：同时包含 chara 和 ccv3";
        if (card.hasCcv3) return "包含 ccv3，新版格式兼容性较好";
        if (card.hasChara) return "包含传统 chara，兼容常见导入方式";
        return "未识别到标准角色卡数据块";
    }

    private static int recommendedKeeper(SemanticCardScanner.Group group) {
        if (group.cards.isEmpty()) return 0;
        int bestIndex = 0;
        int bestScore = Integer.MIN_VALUE;
        long bestSize = Long.MAX_VALUE;
        for (int i = 0; i < group.cards.size(); i++) {
            SemanticCardParser.CardRecord card = group.cards.get(i);
            int score = 0;
            if (!card.payloadConflict) score += 100;
            if (card.hasCcv3) score += 40;
            if (card.hasChara) score += 25;
            if (card.hasChara && card.hasCcv3) score += 15;
            if (card.specVersion != null && !card.specVersion.isEmpty()) score += 5;
            long size = card.size < 0 ? Long.MAX_VALUE : card.size;
            if (score > bestScore || (score == bestScore && size < bestSize)) {
                bestScore = score;
                bestSize = size;
                bestIndex = i;
            }
        }
        return bestIndex;
    }
}
