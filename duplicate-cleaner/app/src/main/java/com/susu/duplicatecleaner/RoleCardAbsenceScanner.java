package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class RoleCardAbsenceScanner {
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
        final int validCards;
        final long elapsedMs;
        final List<PlainPngItem> plainImages;
        final List<PlainPngItem> nonCardJson;
        final List<PlainPngItem> damaged;

        ScanResult(int totalFiles, int validCards, long elapsedMs,
                   List<PlainPngItem> plainImages,
                   List<PlainPngItem> nonCardJson,
                   List<PlainPngItem> damaged) {
            this.totalFiles = totalFiles;
            this.validCards = validCards;
            this.elapsedMs = elapsedMs;
            this.plainImages = plainImages;
            this.nonCardJson = nonCardJson;
            this.damaged = damaged;
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

    RoleCardAbsenceScanner(ContentResolver resolver, AtomicBoolean cancelled,
                           ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<PlainPngItem> seeds = enumerate(treeUri);
        List<PlainPngItem> plain = new ArrayList<>();
        List<PlainPngItem> json = new ArrayList<>();
        List<PlainPngItem> damaged = new ArrayList<>();
        int valid = 0;

        PngContentInspector pngInspector = new PngContentInspector(
                resolver, cancelled, null);
        for (int i = 0; i < seeds.size(); i++) {
            checkCancelled();
            PlainPngItem item = seeds.get(i);
            progress("正在检查 PNG / JSON：" + (i + 1) + " / " + seeds.size()
                    + "\n" + item.path);
            String lower = item.fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) {
                try {
                    Object value = JsonDocumentReader.readAny(resolver, item.contentUri());
                    if (!(value instanceof JSONObject)) {
                        item.type = PlainPngItem.Type.NON_CARD_JSON;
                        item.reason = "JSON 顶层不是角色卡对象结构";
                        item.markerSummary = "非角色卡 JSON，只允许移动";
                        json.add(item);
                        continue;
                    }
                    RoleCardHeuristics.Result result =
                            RoleCardHeuristics.inspect((JSONObject) value);
                    if (result.roleCard) {
                        valid++;
                    } else {
                        item.type = PlainPngItem.Type.NON_CARD_JSON;
                        item.reason = result.reason;
                        item.markerSummary = "JSON 可正常读取，但不是角色卡；可能是预设、美化、世界书或其他配置";
                        json.add(item);
                    }
                } catch (Exception e) {
                    item.type = PlainPngItem.Type.DAMAGED_OR_UNREADABLE;
                    item.reason = "JSON 无法解析：" + safeMessage(e);
                    item.markerSummary = "损坏或编码异常的 JSON，只允许移动";
                    damaged.add(item);
                }
            } else {
                try {
                    PngContentInspector.Inspection inspection =
                            pngInspector.inspect(item.contentUri());
                    item.width = inspection.width;
                    item.height = inspection.height;
                    item.reason = inspection.reason;
                    item.markerSummary = inspection.markerSummary;
                    if (inspection.type == PngContentInspector.InspectionType.VALID_CARD) {
                        valid++;
                    } else if (inspection.type
                            == PngContentInspector.InspectionType.PLAIN_IMAGE) {
                        item.type = PlainPngItem.Type.PLAIN_IMAGE;
                        plain.add(item);
                    } else {
                        item.type = PlainPngItem.Type.DAMAGED_OR_UNREADABLE;
                        damaged.add(item);
                    }
                } catch (Exception e) {
                    item.type = PlainPngItem.Type.DAMAGED_OR_UNREADABLE;
                    item.reason = "PNG 无法完整读取：" + safeMessage(e);
                    item.markerSummary = "损坏或格式异常的 PNG，只允许移动";
                    damaged.add(item);
                }
            }
        }
        return new ScanResult(seeds.size(), valid,
                System.currentTimeMillis() - started, plain, json, damaged);
    }

    private List<PlainPngItem> enumerate(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        List<PlainPngItem> result = new ArrayList<>();
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
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".png") && !lower.endsWith(".json")) continue;
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, documentId);
                    PlainPngItem item = new PlainPngItem();
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
                progress("正在建立 PNG / JSON 索引：" + folders
                        + " 个文件夹，" + result.size() + " 个文件");
            }
        }
        result.sort((left, right) -> left.path.compareToIgnoreCase(right.path));
        return result;
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
