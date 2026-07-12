package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class CardRenamer {
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TEXT_CHUNK_BYTES = 32 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class RenameItem {
        final Uri uri;
        final String parentDocumentId;
        final String path;
        final String originalName;
        final long size;
        final long modified;
        final String extension;
        String characterName;
        String targetName;
        String error;
        boolean needsRename;

        RenameItem(Uri uri, String parentDocumentId, String path, String originalName,
                   long size, long modified, String extension) {
            this.uri = uri;
            this.parentDocumentId = parentDocumentId;
            this.path = path;
            this.originalName = originalName;
            this.size = size;
            this.modified = modified;
            this.extension = extension;
        }
    }

    static final class ScanResult {
        final int totalFiles;
        final int supportedFiles;
        final int recognizedCards;
        final int unchangedCards;
        final int failedCards;
        final long elapsedMs;
        final List<RenameItem> renameItems;
        final List<RenameItem> allItems;

        ScanResult(int totalFiles, int supportedFiles, int recognizedCards,
                   int unchangedCards, int failedCards, long elapsedMs,
                   List<RenameItem> renameItems, List<RenameItem> allItems) {
            this.totalFiles = totalFiles;
            this.supportedFiles = supportedFiles;
            this.recognizedCards = recognizedCards;
            this.unchangedCards = unchangedCards;
            this.failedCards = failedCards;
            this.elapsedMs = elapsedMs;
            this.renameItems = renameItems;
            this.allItems = allItems;
        }
    }

    static final class RenameResult {
        int renamed;
        int skipped;
        int failed;
        final StringBuilder log = new StringBuilder();
    }

    private static final class FolderNode {
        final String documentId;
        final String path;

        FolderNode(String documentId, String path) {
            this.documentId = documentId;
            this.path = path;
        }
    }

    private static final class Metadata {
        final String displayName;
        final long size;
        final long modified;

        Metadata(String displayName, long size, long modified) {
            this.displayName = displayName;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final class EnumerationResult {
        final List<RenameItem> candidates = new ArrayList<>();
        final Map<String, Set<String>> existingNamesByFolder = new HashMap<>();
        int totalFiles;
    }

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    CardRenamer(ContentResolver resolver, AtomicBoolean cancelled, ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        EnumerationResult enumeration = enumerate(treeUri);
        List<RenameItem> allItems = enumeration.candidates;
        int recognized = 0;
        int failed = 0;

        for (int i = 0; i < allItems.size(); i++) {
            checkCancelled();
            RenameItem item = allItems.get(i);
            try {
                String name = "json".equals(item.extension)
                        ? readCharacterNameFromJson(item.uri)
                        : readCharacterNameFromPng(item.uri);
                name = sanitizeBaseName(name);
                if (name == null || name.isEmpty()) {
                    item.error = "没有识别到有效角色名";
                    failed++;
                } else {
                    item.characterName = name;
                    recognized++;
                }
            } catch (Exception e) {
                item.error = safeMessage(e);
                failed++;
            }
            if ((i + 1) % 10 == 0 || i + 1 == allItems.size()) {
                progress("正在读取角色卡：" + (i + 1) + " / " + allItems.size());
            }
        }

        planUniqueNames(allItems, enumeration.existingNamesByFolder);

        int unchanged = 0;
        List<RenameItem> renameItems = new ArrayList<>();
        for (RenameItem item : allItems) {
            if (item.error != null) continue;
            if (item.needsRename) renameItems.add(item);
            else unchanged++;
        }
        renameItems.sort(Comparator.comparing(item -> item.path.toLowerCase(Locale.ROOT)));

        return new ScanResult(
                enumeration.totalFiles,
                allItems.size(),
                recognized,
                unchanged,
                failed,
                System.currentTimeMillis() - started,
                renameItems,
                allItems
        );
    }

    RenameResult renameAll(List<RenameItem> items) throws Exception {
        RenameResult result = new RenameResult();
        for (int i = 0; i < items.size(); i++) {
            checkCancelled();
            RenameItem item = items.get(i);
            progress("正在原地改名：" + (i + 1) + " / " + items.size());

            try {
                Metadata current = queryMetadata(item.uri);
                if (current == null) {
                    result.skipped++;
                    result.log.append("跳过（文件已不存在）：").append(item.path).append('\n');
                    continue;
                }
                if (!item.originalName.equals(current.displayName)
                        || item.size != current.size
                        || (item.modified > 0 && current.modified > 0 && item.modified != current.modified)) {
                    result.skipped++;
                    result.log.append("跳过（扫描后文件发生变化，请重新扫描）：")
                            .append(item.path).append('\n');
                    continue;
                }

                Uri renamedUri = DocumentsContract.renameDocument(resolver, item.uri, item.targetName);
                if (renamedUri != null) {
                    result.renamed++;
                    result.log.append("已改名：").append(item.path)
                            .append(" → ").append(item.targetName).append('\n');
                } else {
                    result.failed++;
                    result.log.append("改名失败（系统未返回新文件地址）：")
                            .append(item.path).append('\n');
                }
            } catch (SecurityException e) {
                result.failed++;
                result.log.append("改名失败（权限不足）：").append(item.path)
                        .append("；").append(safeMessage(e)).append('\n');
            } catch (Exception e) {
                result.failed++;
                result.log.append("改名失败：").append(item.path)
                        .append("；").append(safeMessage(e)).append('\n');
            }
        }
        return result;
    }

    private EnumerationResult enumerate(Uri treeUri) throws Exception {
        EnumerationResult result = new EnumerationResult();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        int folderCount = 0;

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
            folderCount++;
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId);
            Set<String> existingNames = result.existingNamesByFolder.computeIfAbsent(
                    folder.documentId, ignored -> new HashSet<>());

            try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("系统未返回目录内容：" + folder.path);
                while (cursor.moveToNext()) {
                    checkCancelled();
                    String documentId = cursor.getString(0);
                    String name = cursor.isNull(1) ? "未命名" : cursor.getString(1);
                    String mime = cursor.isNull(2) ? "" : cursor.getString(2);
                    long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                    long modified = cursor.isNull(4) ? 0L : cursor.getLong(4);
                    String path = folder.path.isEmpty() ? name : folder.path + "/" + name;

                    existingNames.add(normalizeName(name));
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.addLast(new FolderNode(documentId, path));
                        continue;
                    }

                    result.totalFiles++;
                    String extension = supportedExtension(name);
                    if (extension == null) continue;
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    result.candidates.add(new RenameItem(
                            documentUri, folder.documentId, path, name, size, modified, extension));
                }
            }

            if (folderCount % 10 == 0 || queue.isEmpty()) {
                progress("正在枚举目录：" + folderCount + " 个文件夹，"
                        + result.totalFiles + " 个文件");
            }
        }
        return result;
    }

    private void planUniqueNames(List<RenameItem> items, Map<String, Set<String>> existingByFolder) {
        Map<String, List<RenameItem>> byFolder = new LinkedHashMap<>();
        for (RenameItem item : items) {
            byFolder.computeIfAbsent(item.parentDocumentId, ignored -> new ArrayList<>()).add(item);
        }

        for (Map.Entry<String, List<RenameItem>> entry : byFolder.entrySet()) {
            List<RenameItem> folderItems = entry.getValue();
            folderItems.sort(Comparator.comparing(item -> item.path.toLowerCase(Locale.ROOT)));
            Set<String> used = new HashSet<>(existingByFolder.getOrDefault(entry.getKey(), Collections.emptySet()));

            for (RenameItem item : folderItems) {
                if (item.error != null || item.characterName == null) continue;
                String desired = item.characterName + "." + item.extension;
                if (normalizeName(item.originalName).equals(normalizeName(desired))) {
                    item.targetName = item.originalName;
                    item.needsRename = false;
                    continue;
                }

                String candidate = desired;
                int suffix = 1;
                while (used.contains(normalizeName(candidate))) {
                    candidate = item.characterName + "(" + suffix + ")." + item.extension;
                    suffix++;
                }
                item.targetName = candidate;
                item.needsRename = true;
                used.add(normalizeName(candidate));
            }
        }
    }

    private String readCharacterNameFromJson(Uri uri) throws Exception {
        byte[] bytes = readAllLimited(uri, MAX_JSON_BYTES);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        return extractCharacterName(json);
    }

    private String readCharacterNameFromPng(Uri uri) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开 PNG 文件");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(raw, 64 * 1024))) {
                byte[] signature = new byte[8];
                input.readFully(signature);
                for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                    if (signature[i] != PNG_SIGNATURE[i]) throw new IllegalArgumentException("不是有效 PNG 文件");
                }

                while (true) {
                    checkCancelled();
                    long length = Integer.toUnsignedLong(input.readInt());
                    byte[] typeBytes = new byte[4];
                    input.readFully(typeBytes);
                    String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                    if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("PNG 数据块过大");

                    if ("tEXt".equals(type) && length <= MAX_TEXT_CHUNK_BYTES) {
                        byte[] data = new byte[(int) length];
                        input.readFully(data);
                        String name = parseTextChunk(data);
                        input.skipBytes(4); // CRC
                        if (name != null) return name;
                    } else {
                        skipFully(input, length + 4L); // chunk data + CRC
                    }

                    if ("IEND".equals(type)) break;
                }
            }
        }
        throw new IllegalArgumentException("PNG 内没有识别到 chara / ccv3 角色数据");
    }

    private String parseTextChunk(byte[] data) throws Exception {
        int separator = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                separator = i;
                break;
            }
        }
        if (separator <= 0 || separator >= data.length - 1) return null;
        String keyword = new String(data, 0, separator, StandardCharsets.ISO_8859_1);
        if (!"chara".equals(keyword) && !"ccv3".equals(keyword)) return null;

        String encoded = new String(data, separator + 1,
                data.length - separator - 1, StandardCharsets.ISO_8859_1).trim();
        byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
        JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        return extractCharacterName(json);
    }

    private static String extractCharacterName(JSONObject json) {
        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            String name = data.optString("name", "").trim();
            if (!name.isEmpty()) return name;
        }
        String rootName = json.optString("name", "").trim();
        return rootName.isEmpty() ? null : rootName;
    }

    private byte[] readAllLimited(Uri uri, int maxBytes) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开文件");
            try (BufferedInputStream input = new BufferedInputStream(raw, 64 * 1024);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    total += read;
                    if (total > maxBytes) throw new IllegalArgumentException("文件过大，已安全跳过");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private Metadata queryMetadata(Uri uri) {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String name = cursor.isNull(0) ? "" : cursor.getString(0);
            long size = cursor.isNull(1) ? -1L : cursor.getLong(1);
            long modified = cursor.isNull(2) ? 0L : cursor.getLong(2);
            return new Metadata(name, size, modified);
        } catch (Exception e) {
            return null;
        }
    }

    private static String supportedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ("png".equals(ext) || "json".equals(ext)) ? ext : null;
    }

    private static String sanitizeBaseName(String input) {
        if (input == null) return null;
        String value = input.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        while (value.endsWith(".") || value.endsWith(" ")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty()) return null;
        if (value.length() > 120) value = value.substring(0, 120).trim();
        return value.isEmpty() ? null : value;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void skipFully(InputStream input, long count) throws Exception {
        long remaining = count;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IllegalArgumentException("PNG 文件数据不完整");
            remaining -= read;
        }
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancelledException();
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
