package com.susu.duplicatecleaner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class SemanticDiffEngine {
    static final class DiffReport {
        final boolean effectiveContentSame;
        final String verdict;
        final String persona;
        final String greetings;
        final String worldbook;
        final String regex;
        final String extensions;
        final String other;
        final String packaging;

        DiffReport(boolean effectiveContentSame, String verdict, String persona,
                   String greetings, String worldbook, String regex,
                   String extensions, String other, String packaging) {
            this.effectiveContentSame = effectiveContentSame;
            this.verdict = verdict;
            this.persona = persona;
            this.greetings = greetings;
            this.worldbook = worldbook;
            this.regex = regex;
            this.extensions = extensions;
            this.other = other;
            this.packaging = packaging;
        }
    }

    private SemanticDiffEngine() {
    }

    static DiffReport compare(SemanticCardParser.ParsedPayload left,
                              SemanticCardParser.ParsedPayload right) {
        boolean same = left.record.functionalHash.equals(right.record.functionalHash);
        String verdict = same
                ? "有效内容完全一致：导入后的人设、开场白、世界书、正则、扩展和其他功能字段相同。差异只来自封面或 PNG 封装，可以只保留一个。"
                : "角色名相同或相近，但有效内容存在变化。下面会逐项指出变化位置；这种组不会提供一键删除。";

        String persona = comparePersona(left, right);
        String greetings = compareGreetings(left, right);
        String worldbook = compareNamedMaps("世界书",
                SemanticCardParser.worldbookEntries(left),
                SemanticCardParser.worldbookEntries(right));
        String regex = compareNamedMaps("正则脚本",
                SemanticCardParser.collectRegexScripts(left.extensions),
                SemanticCardParser.collectRegexScripts(right));
        String extensions = compareTopLevelObject("扩展功能", left.extensions, right.extensions);
        String other = compareTopLevelObject("其他有效字段", left.other, right.other);
        String packaging = comparePackaging(left.record, right.record);
        return new DiffReport(same, verdict, persona, greetings, worldbook,
                regex, extensions, other, packaging);
    }

    private static String comparePersona(SemanticCardParser.ParsedPayload left,
                                         SemanticCardParser.ParsedPayload right) {
        String[] labels = new String[]{
                "name|角色名", "nickname|昵称", "description|角色描述",
                "personality|性格", "scenario|场景", "mes_example|示例对话",
                "system_prompt|系统提示词",
                "post_history_instructions|历史后指令",
                "group_only_greetings|群聊开场设置"
        };
        List<String> changes = new ArrayList<>();
        for (String item : labels) {
            String[] parts = item.split("\\|", 2);
            Object a = left.persona.opt(parts[0]);
            Object b = right.persona.opt(parts[0]);
            String ca = SemanticCardParser.canonical(a);
            String cb = SemanticCardParser.canonical(b);
            if (!ca.equals(cb)) {
                changes.add("• " + parts[1] + "不同：左侧 " + valueSummary(a)
                        + "；右侧 " + valueSummary(b));
            }
        }
        if (changes.isEmpty()) return "✓ 人设相关字段完全相同。";
        return "✗ 人设存在 " + changes.size() + " 处变化：\n" + join(changes);
    }

    private static String compareGreetings(SemanticCardParser.ParsedPayload left,
                                           SemanticCardParser.ParsedPayload right) {
        List<String> a = SemanticCardParser.greetingTexts(left);
        List<String> b = SemanticCardParser.greetingTexts(right);
        int common = Math.min(a.size(), b.size());
        List<String> changes = new ArrayList<>();
        for (int i = 0; i < common; i++) {
            if (!a.get(i).equals(b.get(i))) {
                changes.add("• 第 " + (i + 1) + " 个开场白内容不同：\n"
                        + "  左：" + preview(a.get(i)) + "\n"
                        + "  右：" + preview(b.get(i)));
            }
        }
        if (a.size() > common) {
            for (int i = common; i < a.size(); i++) {
                changes.add("• 仅左侧有第 " + (i + 1) + " 个：" + preview(a.get(i)));
            }
        }
        if (b.size() > common) {
            for (int i = common; i < b.size(); i++) {
                changes.add("• 仅右侧有第 " + (i + 1) + " 个：" + preview(b.get(i)));
            }
        }
        if (changes.isEmpty()) {
            return "✓ 开场白完全相同，共 " + a.size() + " 个。";
        }
        return "✗ 开场白不同：左侧 " + a.size() + " 个，右侧 " + b.size()
                + " 个。\n" + limitLines(changes, 24);
    }

    private static String compareNamedMaps(String label, Map<String, String> left,
                                           Map<String, String> right) {
        Set<String> names = new TreeSet<>();
        names.addAll(left.keySet());
        names.addAll(right.keySet());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String name : names) {
            boolean hasLeft = left.containsKey(name);
            boolean hasRight = right.containsKey(name);
            if (hasLeft && !hasRight) removed.add(name);
            else if (!hasLeft) added.add(name);
            else if (!left.get(name).equals(right.get(name))) changed.add(name);
        }
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            return "✓ " + label + "完全相同，共 " + left.size() + " 项。";
        }
        StringBuilder builder = new StringBuilder("✗ ").append(label)
                .append("不同：左侧 ").append(left.size()).append(" 项，右侧 ")
                .append(right.size()).append(" 项。\n");
        if (!added.isEmpty()) builder.append("• 仅右侧有：")
                .append(joinNames(added)).append('\n');
        if (!removed.isEmpty()) builder.append("• 仅左侧有：")
                .append(joinNames(removed)).append('\n');
        if (!changed.isEmpty()) builder.append("• 同名但内容改变：")
                .append(joinNames(changed)).append('\n');
        return builder.toString().trim();
    }

    private static String compareTopLevelObject(String label, Object left, Object right) {
        if (SemanticCardParser.canonical(left).equals(SemanticCardParser.canonical(right))) {
            int count = left instanceof JSONObject ? ((JSONObject) left).length() : 0;
            return "✓ " + label + "完全相同" + (count > 0 ? "，顶层共 " + count + " 项。" : "。 ");
        }
        if (!(left instanceof JSONObject) || !(right instanceof JSONObject)) {
            return "✗ " + label + "整体不同：左侧 " + valueSummary(left)
                    + "；右侧 " + valueSummary(right);
        }
        JSONObject a = (JSONObject) left;
        JSONObject b = (JSONObject) right;
        Set<String> keys = new TreeSet<>();
        keys.addAll(keysOf(a));
        keys.addAll(keysOf(b));
        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!a.has(key)) onlyB.add(key);
            else if (!b.has(key)) onlyA.add(key);
            else if (!SemanticCardParser.canonical(a.opt(key))
                    .equals(SemanticCardParser.canonical(b.opt(key)))) changed.add(key);
        }
        StringBuilder builder = new StringBuilder("✗ ").append(label).append("不同。\n");
        if (!onlyA.isEmpty()) builder.append("• 仅左侧有：").append(joinNames(onlyA)).append('\n');
        if (!onlyB.isEmpty()) builder.append("• 仅右侧有：").append(joinNames(onlyB)).append('\n');
        if (!changed.isEmpty()) builder.append("• 内容改变的项目：")
                .append(joinNames(changed)).append('\n');
        return builder.toString().trim();
    }

    private static String comparePackaging(SemanticCardParser.CardRecord left,
                                           SemanticCardParser.CardRecord right) {
        List<String> differences = new ArrayList<>();
        if (left.size != right.size) {
            differences.add("• 文件大小不同：" + formatBytes(left.size)
                    + " vs " + formatBytes(right.size));
        }
        if (left.width != right.width || left.height != right.height) {
            differences.add("• 封面尺寸不同：" + left.width + "×" + left.height
                    + " vs " + right.width + "×" + right.height);
        }
        if (!safeEquals(left.imageDataHash, right.imageDataHash)) {
            differences.add("• PNG 封面数据指纹不同：可能是封面像素变化，也可能只是重新编码；详细页会继续做逐像素比较。");
        }
        if (!left.chunkKeywords.equals(right.chunkKeywords)) {
            differences.add("• PNG 数据块不同：左侧 " + left.chunkKeywords
                    + "；右侧 " + right.chunkKeywords);
        }
        if (left.hasChara != right.hasChara || left.hasCcv3 != right.hasCcv3) {
            differences.add("• 角色卡封装兼容性不同：左侧 " + left.compatibilityText()
                    + "；右侧 " + right.compatibilityText());
        }
        if (!safeEquals(left.specVersion, right.specVersion)
                || !safeEquals(left.spec, right.spec)) {
            differences.add("• 卡片规范标记不同：左侧 " + specText(left)
                    + "；右侧 " + specText(right));
        }
        if (left.payloadConflict || right.payloadConflict) {
            differences.add("• 至少一个文件内部的 chara 与 ccv3 内容不一致，不建议自动删除。");
        }
        if (differences.isEmpty()) {
            return "✓ 未发现明显封装差异；文件仍可能因压缩参数或无关元数据而字节不同。";
        }
        return "封装与文件层差异：\n" + join(differences);
    }

    private static String specText(SemanticCardParser.CardRecord record) {
        String spec = record.spec == null || record.spec.isEmpty() ? "未标记" : record.spec;
        String version = record.specVersion == null || record.specVersion.isEmpty()
                ? "" : " v" + record.specVersion;
        return spec + version;
    }

    private static Set<String> keysOf(JSONObject object) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    private static String valueSummary(Object value) {
        if (value == null || value == JSONObject.NULL) return "无";
        if (value instanceof String) {
            String text = (String) value;
            return "“" + preview(text) + "”（" + text.length() + " 字）";
        }
        if (value instanceof JSONArray) return "数组 " + ((JSONArray) value).length() + " 项";
        if (value instanceof JSONObject) return "对象 " + ((JSONObject) value).length() + " 项";
        return String.valueOf(value);
    }

    private static String preview(String value) {
        if (value == null) return "";
        String text = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    private static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append('\n');
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static String limitLines(List<String> lines, int max) {
        if (lines.size() <= max) return join(lines);
        List<String> limited = new ArrayList<>(lines.subList(0, max));
        limited.add("• 还有 " + (lines.size() - max) + " 项差异未在摘要中展开");
        return join(limited);
    }

    private static String joinNames(List<String> values) {
        if (values.size() <= 12) return String.join("、", values);
        return String.join("、", values.subList(0, 12))
                + " 等共 " + values.size() + " 项";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.CHINA, "%.2f %s", value, units[index]);
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
