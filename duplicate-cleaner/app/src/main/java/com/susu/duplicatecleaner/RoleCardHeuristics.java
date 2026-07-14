package com.susu.duplicatecleaner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

final class RoleCardHeuristics {
    static final class Result {
        final boolean roleCard;
        final int score;
        final String reason;
        final JSONObject root;
        final JSONObject data;

        Result(boolean roleCard, int score, String reason,
               JSONObject root, JSONObject data) {
            this.roleCard = roleCard;
            this.score = score;
            this.reason = reason;
            this.root = root;
            this.data = data;
        }
    }

    private RoleCardHeuristics() {
    }

    static Result inspect(JSONObject root) {
        if (root == null) return new Result(false, 0, "JSON 对象为空", null, null);
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;

        String spec = lower(root.optString("spec", ""));
        boolean explicitSpec = spec.contains("chara_card_v2")
                || spec.contains("chara_card_v3")
                || spec.contains("character_card");

        int score = explicitSpec ? 8 : 0;
        boolean hasName = hasText(data, "name");
        boolean hasDescription = hasText(data, "description") || hasText(data, "char_persona");
        boolean hasPersonality = hasText(data, "personality");
        boolean hasScenario = hasText(data, "scenario");
        boolean hasFirstMessage = hasText(data, "first_mes");
        boolean hasAlternates = data.opt("alternate_greetings") instanceof JSONArray;
        boolean hasExamples = hasText(data, "mes_example");
        boolean hasBook = data.opt("character_book") instanceof JSONObject;
        boolean hasExtensions = data.opt("extensions") instanceof JSONObject;

        if (hasName) score += 2;
        if (hasDescription) score += 3;
        if (hasPersonality) score += 1;
        if (hasScenario) score += 1;
        if (hasFirstMessage) score += 3;
        if (hasAlternates) score += 2;
        if (hasExamples) score += 1;
        if (hasBook) score += 1;
        if (hasExtensions) score += 1;

        int presetSignals = presetSignalCount(root, data);
        int themeSignals = themeSignalCount(root);
        int worldBookSignals = worldBookSignalCount(root);

        boolean structuralCard = hasName
                && (hasDescription || hasPersonality || hasScenario)
                && (hasFirstMessage || hasAlternates || hasExamples);
        boolean roleCard = explicitSpec || (structuralCard && score >= 8);

        if (!explicitSpec && presetSignals >= 4) roleCard = false;
        if (!explicitSpec && themeSignals >= 4) roleCard = false;
        if (!explicitSpec && worldBookSignals >= 3 && !hasFirstMessage && !hasDescription) {
            roleCard = false;
        }

        String reason;
        if (explicitSpec) {
            reason = "检测到明确角色卡规范标记：" + root.optString("spec", "");
        } else if (roleCard) {
            reason = "检测到角色名、人设字段和开场白等完整角色卡组合结构";
        } else if (presetSignals >= 4) {
            reason = "结构更像生成/聊天预设，而不是角色卡";
        } else if (themeSignals >= 4) {
            reason = "结构更像界面主题或美化配置，而不是角色卡";
        } else if (worldBookSignals >= 3) {
            reason = "结构更像独立世界书，而不是角色卡";
        } else {
            reason = "缺少足够的角色身份、人设和开场白组合字段";
        }
        return new Result(roleCard, score, reason, root, data);
    }

    static boolean looksLikeRoleCard(JSONObject root) {
        return inspect(root).roleCard;
    }

    static int presetSignalCount(JSONObject root, JSONObject data) {
        Set<String> keys = new HashSet<>();
        collectKeys(root, keys);
        if (data != root) collectKeys(data, keys);
        String[] signals = new String[]{
                "temperature", "top_p", "top_k", "min_p", "typical_p",
                "repetition_penalty", "frequency_penalty", "presence_penalty",
                "chat_completion_source", "prompt_order", "prompts", "sampler_order",
                "max_tokens", "max_length", "model", "openai_model",
                "input_sequence", "output_sequence", "system_sequence",
                "story_string", "example_separator", "chat_start"
        };
        int count = 0;
        for (String signal : signals) if (keys.contains(signal)) count++;
        return count;
    }

    static int themeSignalCount(JSONObject root) {
        Set<String> keys = new HashSet<>();
        collectKeys(root, keys);
        String[] signals = new String[]{
                "main_text_color", "italics_text_color", "quote_text_color",
                "blur_strength", "font_scale", "chat_width", "custom_css",
                "theme_name", "shadow_color", "border_color", "background_color",
                "user_mes_blur_tint_color", "bot_mes_blur_tint_color"
        };
        int count = 0;
        for (String signal : signals) if (keys.contains(signal)) count++;
        return count;
    }

    static int worldBookSignalCount(JSONObject root) {
        int count = 0;
        Object entries = root.opt("entries");
        if (entries instanceof JSONArray || entries instanceof JSONObject) count += 2;
        if (root.has("scan_depth") || root.has("token_budget")) count++;
        if (entries instanceof JSONArray && ((JSONArray) entries).length() > 0) {
            Object first = ((JSONArray) entries).opt(0);
            if (first instanceof JSONObject) {
                JSONObject entry = (JSONObject) first;
                if (entry.has("keys") || entry.has("key")) count++;
                if (entry.has("content")) count++;
            }
        }
        return count;
    }

    private static void collectKeys(JSONObject object, Set<String> output) {
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) output.add(lower(iterator.next()));
    }

    private static boolean hasText(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
