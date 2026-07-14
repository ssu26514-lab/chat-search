package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class TavernFileClassifier {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class ScanResult {
        final int totalFiles;
        final long elapsedMs;
        final List<TavernFileItem> items;
        final Map<TavernFileItem.Category, Integer> counts;

        ScanResult(int totalFiles, long elapsedMs, List<TavernFileItem> items,
                   Map<TavernFileItem.Category, Integer> counts) {
            this.totalFiles = totalFiles;
            this.elapsedMs = elapsedMs;
            this.items = items;
            this.counts = counts;
        }
    }

    private static final class FolderNode {
        final String documentId;
        final String path;

        FolderNode(String documentId, String path) {
            this.documentId = documentId;
            this.path = path;
        }
    }

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    TavernFileClassifier(ContentResolver resolver, AtomicBoolean cancelled,
                         ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<TavernFileItem> files = enumerateFiles(treeUri);
        for (int i = 0; i < files.size(); i++) {
            checkCancelled();
            TavernFileItem item = files.get(i);
            progress("正在分类酒馆文件：" + (i + 1) + " / " + files.size()
                    + "\n" + item.path);
            classify(item);
        }
        files.sort((left, right) -> {
            int byCategory = left.category.label.compareTo(right.category.label);
            if (byCategory != 0) return byCategory;
            return left.path.compareToIgnoreCase(right.path);
        });
        Map<TavernFileItem.Category, Integer> counts =
                new EnumMap<>(TavernFileItem.Category.class);
        for (TavernFileItem.Category category : TavernFileItem.Category.values()) {
            counts.put(category, 0);
        }
        for (TavernFileItem item : files) {
            counts.put(item.category, counts.get(item.category) + 1);
        }
        progress("分类完成：共 " + files.size() + " 个可识别文件");
        return new ScanResult(files.size(), System.currentTimeMillis() - started,
                files, counts);
    }

    private List<TavernFileItem> enumerateFiles(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        List<TavernFileItem> result = new ArrayList<>();
        int folders = 0;

        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        while (!queue.isEmpty()) {
            checkCancelled();
            FolderNode folder = queue.removeFirst();
            folders++;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, folder.documentId);
            try (Cursor cursor = resolver.query(children, projection,
                    null, null, null)) {
                if (cursor == null) {
                    throw new IllegalStateException(
                            "系统未返回目录内容：" + folder.path);
                }
                while (cursor.moveToNext()) {
                    checkCancelled();
                    String documentId = cursor.getString(0);
                    String name = cursor.isNull(1) ? "未命名" : cursor.getString(1);
                    String mime = cursor.isNull(2) ? "" : cursor.getString(2);
                    long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                    long modified = cursor.isNull(4) ? 0L : cursor.getLong(4);
                    String path = folder.path.isEmpty()
                            ? name : folder.path + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.addLast(new FolderNode(documentId, path));
                        continue;
                    }
                    if (!isSupported(name)) continue;
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, documentId);
                    TavernFileItem item = new TavernFileItem();
                    item.key = documentUri.toString();
                    item.treeUri = treeUri.toString();
                    item.uri = documentUri.toString();
                    item.parentDocumentId = folder.documentId;
                    item.path = path;
                    item.fileName = name;
                    item.size = size;
                    item.modified = modified;
                    result.add(item);
                }
            }
            if (folders % 10 == 0 || queue.isEmpty()) {
                progress("正在建立文件索引：" + folders + " 个文件夹，"
                        + result.size() + " 个候选文件");
            }
        }
        return result;
    }

    private void classify(TavernFileItem item) {
        String lower = item.fileName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".png")) {
                classifyPng(item);
            } else if (lower.endsWith(".json")) {
                classifyJson(item);
            } else if (lower.endsWith(".css")) {
                set(item, TavernFileItem.Category.BEAUTY,
                        "自定义 CSS / 样式表",
                        "文件扩展名为 .css，属于界面美化样式",
                        "可移动到美化或主题文件夹", 100);
            } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                set(item, TavernFileItem.Category.BEAUTY,
                        "HTML 美化 / 界面模板",
                        "HTML 文件通常用于自定义界面、消息模板或美化组件",
                        "建议单独放入美化或模板文件夹", 80);
            } else if (lower.endsWith(".js")) {
                set(item, TavernFileItem.Category.EXTENSION_PLUGIN,
                        "JavaScript 插件 / 脚本",
                        "文件扩展名为 .js，通常属于插件、扩展或功能脚本",
                        "建议与对应 manifest、CSS 一起移动", 85);
            } else if (lower.endsWith(".zip")) {
                classifyZip(item);
            } else if (isImage(lower)) {
                set(item, TavernFileItem.Category.IMAGE_ASSET,
                        "图片素材",
                        "这是普通图片文件，不包含 PNG 角色卡数据",
                        "可能是背景、头像、封面或美化素材", 95);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                set(item, TavernFileItem.Category.UNKNOWN,
                        "文本说明 / 无法自动确定",
                        "文本文件不能仅凭后缀判断是否属于预设或美化",
                        "建议查看内容后手动归类", 40);
            } else {
                set(item, TavernFileItem.Category.UNKNOWN,
                        "不支持的文件类型",
                        "当前版本无法可靠识别这种文件结构",
                        "只允许移动，不会删除", 10);
            }
        } catch (Exception e) {
            set(item, TavernFileItem.Category.DAMAGED,
                    "损坏或无法读取",
                    "读取失败：" + safeMessage(e),
                    "只能移动到待检查文件夹", 100);
        }
    }

    private void classifyPng(TavernFileItem item) throws Exception {
        PngContentInspector inspector = new PngContentInspector(
                resolver, cancelled, null);
        PngContentInspector.Inspection inspection = inspector.inspect(item.contentUri());
        if (inspection.type == PngContentInspector.InspectionType.VALID_CARD) {
            set(item, TavernFileItem.Category.CHARACTER_CARD,
                    "PNG 角色卡",
                    inspection.reason,
                    inspection.markerSummary, 100);
        } else if (inspection.type == PngContentInspector.InspectionType.PLAIN_IMAGE) {
            set(item, TavernFileItem.Category.IMAGE_ASSET,
                    "普通 PNG 图片 / 素材",
                    inspection.reason,
                    "可能是背景、头像、封面或美化素材", 100);
        } else {
            set(item, TavernFileItem.Category.DAMAGED,
                    "疑似损坏 PNG 角色卡",
                    inspection.reason,
                    inspection.markerSummary + "；建议移动到待修复文件夹", 100);
        }
    }

    private void classifyJson(TavernFileItem item) throws Exception {
        Object value = JsonDocumentReader.readAny(resolver, item.contentUri());
        if (value instanceof JSONArray) {
            classifyJsonArray(item, (JSONArray) value);
            return;
        }
        JSONObject root = (JSONObject) value;
        RoleCardHeuristics.Result role = RoleCardHeuristics.inspect(root);
        if (role.roleCard) {
            String spec = root.optString("spec", "");
            set(item, TavernFileItem.Category.CHARACTER_CARD,
                    "JSON 角色卡",
                    role.reason,
                    spec.isEmpty() ? "旧版或平铺 JSON 角色卡结构"
                            : "规范：" + spec, 100);
            return;
        }

        int theme = RoleCardHeuristics.themeSignalCount(root);
        int preset = RoleCardHeuristics.presetSignalCount(root,
                root.optJSONObject("data") == null ? root : root.optJSONObject("data"));
        int world = RoleCardHeuristics.worldBookSignalCount(root);
        int regex = SemanticRegexExtractor.count(root);
        int plugin = pluginSignalCount(root);

        int strongTypes = 0;
        if (theme >= 4) strongTypes++;
        if (preset >= 4) strongTypes++;
        if (world >= 3) strongTypes++;
        if (regex > 0) strongTypes++;
        if (plugin >= 3) strongTypes++;

        if (strongTypes > 1) {
            set(item, TavernFileItem.Category.MIXED_PACKAGE,
                    "混合 JSON 包",
                    "同一个 JSON 同时包含多种强特征，不能安全地只归入一个类型",
                    mixedDetails(theme, preset, world, regex, plugin), 70);
        } else if (theme >= 4) {
            set(item, TavernFileItem.Category.BEAUTY,
                    "主题 Theme / 美化配置",
                    "检测到颜色、模糊、字体、聊天宽度或 custom_css 等主题字段",
                    "主题特征 " + theme + " 项", 95);
        } else if (preset >= 4) {
            set(item, TavernFileItem.Category.PRESET,
                    presetSubtype(root),
                    "检测到模型、采样参数、提示词顺序或指令模板等预设字段",
                    "预设特征 " + preset + " 项", 95);
        } else if (world >= 3) {
            set(item, TavernFileItem.Category.WORLD_BOOK,
                    "独立世界书 / Lorebook",
                    "检测到 entries、keys、content 等世界书结构",
                    worldBookDetails(root), 95);
        } else if (regex > 0) {
            set(item, TavernFileItem.Category.REGEX_SCRIPT,
                    "正则脚本集合",
                    "检测到实际匹配式和替换式结构",
                    "识别到正则脚本 " + regex + " 项", 95);
        } else if (plugin >= 3) {
            set(item, TavernFileItem.Category.EXTENSION_PLUGIN,
                    "插件 / 扩展 manifest",
                    "检测到 display_name、loading_order、js、css 等插件字段",
                    "插件特征 " + plugin + " 项", 90);
        } else {
            set(item, TavernFileItem.Category.UNKNOWN,
                    "无法确定的 JSON",
                    role.reason,
                    "没有达到角色卡、预设、主题、世界书、正则或插件的可靠阈值",
                    35);
        }
    }

    private void classifyJsonArray(TavernFileItem item, JSONArray array) {
        int regex = SemanticRegexExtractor.count(array);
        if (regex > 0) {
            set(item, TavernFileItem.Category.REGEX_SCRIPT,
                    "正则脚本数组",
                    "JSON 顶层是数组，并识别到实际正则匹配与替换结构",
                    "识别到正则脚本 " + regex + " 项", 95);
            return;
        }
        if (array.length() > 0 && array.opt(0) instanceof JSONObject) {
            JSONObject first = array.optJSONObject(0);
            if (first != null && (first.has("keys") || first.has("key"))
                    && first.has("content")) {
                set(item, TavernFileItem.Category.WORLD_BOOK,
                        "世界书条目数组",
                        "JSON 数组中的对象包含 keys 和 content",
                        "条目数量：" + array.length(), 90);
                return;
            }
        }
        set(item, TavernFileItem.Category.UNKNOWN,
                "无法确定的 JSON 数组",
                "顶层是数组，但没有足够的预设、世界书或正则结构",
                "数组项目：" + array.length(), 35);
    }

    private void classifyZip(TavernFileItem item) throws Exception {
        int entries = 0;
        int json = 0;
        int css = 0;
        int images = 0;
        int scripts = 0;
        List<String> preview = new ArrayList<>();
        try (InputStream raw = resolver.openInputStream(item.contentUri());
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && entries < 500) {
                checkCancelled();
                if (entry.isDirectory()) continue;
                entries++;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (preview.size() < 12) preview.add(entry.getName());
                if (name.endsWith(".json")) json++;
                else if (name.endsWith(".css") || name.endsWith(".html")) css++;
                else if (name.endsWith(".js")) scripts++;
                else if (isImage(name)) images++;
            }
        }
        String details = "文件数：" + entries + "；JSON " + json
                + "；CSS/HTML " + css + "；脚本 " + scripts
                + "；图片 " + images;
        if (!preview.isEmpty()) details += "\n示例：" + String.join("、", preview);
        set(item, TavernFileItem.Category.MIXED_PACKAGE,
                "ZIP 压缩包 / 可能的整合包",
                "压缩包可能同时包含角色卡、预设、美化和素材，不能只归入一个类型",
                details, 100);
    }

    private static String presetSubtype(JSONObject root) {
        if (root.has("chat_completion_source") || root.has("prompt_order")
                || root.has("prompts")) return "聊天补全 / OpenAI 类预设";
        if (root.has("input_sequence") || root.has("output_sequence")
                || root.has("system_sequence")) return "Instruct 指令预设";
        if (root.has("story_string") || root.has("example_separator")
                || root.has("chat_start")) return "Context 上下文预设";
        if (root.has("sampler_order") || root.has("repetition_penalty")) {
            return "TextGen / Kobold 生成预设";
        }
        return "生成参数预设";
    }

    private static int pluginSignalCount(JSONObject root) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = root.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next().toLowerCase(Locale.ROOT));
        }
        String[] signals = new String[]{
                "display_name", "loading_order", "requires", "optional",
                "js", "css", "author", "version", "homepage", "auto_update"
        };
        int count = 0;
        for (String signal : signals) if (keys.contains(signal)) count++;
        return count;
    }

    private static String worldBookDetails(JSONObject root) {
        Object entries = root.opt("entries");
        if (entries instanceof JSONArray) {
            return "世界书条目：" + ((JSONArray) entries).length();
        }
        if (entries instanceof JSONObject) {
            return "世界书条目：" + ((JSONObject) entries).length();
        }
        return "检测到世界书结构";
    }

    private static String mixedDetails(int theme, int preset, int world,
                                       int regex, int plugin) {
        return "主题特征 " + theme + "；预设特征 " + preset
                + "；世界书特征 " + world + "；正则 " + regex
                + "；插件特征 " + plugin;
    }

    private static void set(TavernFileItem item, TavernFileItem.Category category,
                            String subtype, String reason, String details,
                            int confidence) {
        item.category = category;
        item.subtype = subtype;
        item.reason = reason;
        item.details = details;
        item.confidence = confidence;
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".json")
                || lower.endsWith(".css") || lower.endsWith(".html")
                || lower.endsWith(".htm") || lower.endsWith(".js")
                || lower.endsWith(".zip") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".txt")
                || lower.endsWith(".md");
    }

    private static boolean isImage(String lowerName) {
        return lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif");
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
