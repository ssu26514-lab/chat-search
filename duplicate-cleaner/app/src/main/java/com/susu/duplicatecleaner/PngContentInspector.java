package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;

final class PngContentInspector {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    enum InspectionType {
        VALID_CARD,
        PLAIN_IMAGE,
        DAMAGED_OR_UNREADABLE
    }

    static final class Inspection {
        final InspectionType type;
        final int width;
        final int height;
        final String reason;
        final String markerSummary;

        Inspection(InspectionType type, int width, int height,
                   String reason, String markerSummary) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.reason = reason;
            this.markerSummary = markerSummary;
        }
    }

    static final class ScanResult {
        final int totalPng;
        final int validCards;
        final int plainImages;
        final int damagedFiles;
        final long elapsedMs;
        final List<PlainPngItem> plain;
        final List<PlainPngItem> damaged;

        ScanResult(int totalPng, int validCards, int plainImages, int damagedFiles,
                   long elapsedMs, List<PlainPngItem> plain,
                   List<PlainPngItem> damaged) {
            this.totalPng = totalPng;
            this.validCards = validCards;
            this.plainImages = plainImages;
            this.damagedFiles = damagedFiles;
            this.elapsedMs = elapsedMs;
            this.plain = plain;
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

    private static final class TextEntry {
        final String keyword;
        final String text;

        TextEntry(String keyword, String text) {
            this.keyword = keyword;
            this.text = text;
        }
    }

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int MAX_TEXT_CHUNK = 96 * 1024 * 1024;

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    PngContentInspector(ContentResolver resolver, AtomicBoolean cancelled,
                        ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<PlainPngItem> seeds = enumeratePng(treeUri);
        List<PlainPngItem> plain = new ArrayList<>();
        List<PlainPngItem> damaged = new ArrayList<>();
        int valid = 0;

        for (int i = 0; i < seeds.size(); i++) {
            checkCancelled();
            PlainPngItem item = seeds.get(i);
            progress("正在检查 PNG 内容：" + (i + 1) + " / " + seeds.size()
                    + "\n" + item.path);
            Inspection inspection;
            try {
                inspection = inspect(item.contentUri());
            } catch (Exception e) {
                inspection = new Inspection(
                        InspectionType.DAMAGED_OR_UNREADABLE,
                        0, 0,
                        "PNG 无法完整读取：" + safeMessage(e),
                        "未能完成数据块检查");
            }
            item.width = inspection.width;
            item.height = inspection.height;
            item.reason = inspection.reason;
            item.markerSummary = inspection.markerSummary;
            if (inspection.type == InspectionType.VALID_CARD) {
                valid++;
            } else if (inspection.type == InspectionType.PLAIN_IMAGE) {
                item.type = PlainPngItem.Type.PLAIN_IMAGE;
                plain.add(item);
            } else {
                item.type = PlainPngItem.Type.DAMAGED_OR_UNREADABLE;
                damaged.add(item);
            }
        }

        progress("检查完成：普通图片 " + plain.size() + " 张，疑似损坏 "
                + damaged.size() + " 张，正常角色卡 " + valid + " 张");
        return new ScanResult(seeds.size(), valid, plain.size(), damaged.size(),
                System.currentTimeMillis() - started, plain, damaged);
    }

    Inspection inspect(Uri uri) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开文件");
            return inspectStream(raw);
        }
    }

    static Inspection inspectBytes(byte[] bytes) throws Exception {
        return inspectStream(new ByteArrayInputStream(bytes));
    }

    private static Inspection inspectStream(InputStream raw) throws Exception {
        int width = 0;
        int height = 0;
        boolean sawIend = false;
        int roleMarkers = 0;
        int validPayloads = 0;
        List<String> markerNames = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(raw, 128 * 1024))) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (signature[i] != PNG_SIGNATURE[i]) {
                    throw new IllegalArgumentException("文件扩展名是 PNG，但实际字节不是 PNG");
                }
            }

            while (true) {
                long length = Integer.toUnsignedLong(input.readInt());
                byte[] typeBytes = new byte[4];
                input.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                if (length > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("PNG 数据块过大");
                }

                if ("IHDR".equals(type)) {
                    byte[] data = readExact(input, (int) length);
                    if (data.length >= 8) {
                        width = readInt(data, 0);
                        height = readInt(data, 4);
                    }
                } else if (("tEXt".equals(type) || "iTXt".equals(type)
                        || "zTXt".equals(type)) && length <= MAX_TEXT_CHUNK) {
                    byte[] data = readExact(input, (int) length);
                    TextEntry entry = parseTextEntry(type, data);
                    if (entry != null && ("chara".equals(entry.keyword)
                            || "ccv3".equals(entry.keyword))) {
                        roleMarkers++;
                        markerNames.add(entry.keyword);
                        try {
                            decodePayload(entry.text);
                            validPayloads++;
                        } catch (Exception e) {
                            errors.add(entry.keyword + " 解析失败：" + safeMessage(e));
                        }
                    }
                } else {
                    skipFully(input, length);
                }
                skipFully(input, 4L); // CRC
                if ("IEND".equals(type)) {
                    sawIend = true;
                    break;
                }
            }
        }

        if (!sawIend) {
            return new Inspection(InspectionType.DAMAGED_OR_UNREADABLE,
                    width, height, "PNG 缺少结束数据块，文件可能不完整",
                    markerSummary(markerNames));
        }
        if (validPayloads > 0) {
            return new Inspection(InspectionType.VALID_CARD,
                    width, height, "已识别到可解析角色卡数据",
                    markerSummary(markerNames));
        }
        if (roleMarkers > 0) {
            String reason = errors.isEmpty()
                    ? "检测到角色卡标记，但内部内容为空或无法解析"
                    : String.join("；", errors);
            return new Inspection(InspectionType.DAMAGED_OR_UNREADABLE,
                    width, height, reason, markerSummary(markerNames));
        }
        return new Inspection(InspectionType.PLAIN_IMAGE,
                width, height,
                "PNG 内没有 chara / ccv3，属于普通图片，不是可导入角色卡",
                "没有角色卡数据块");
    }

    private List<PlainPngItem> enumeratePng(Uri treeUri) throws Exception {
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
            try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
                if (cursor == null) {
                    throw new IllegalStateException("系统未返回目录内容：" + folder.path);
                }
                while (cursor.moveToNext()) {
                    checkCancelled();
                    String documentId = cursor.getString(0);
                    String name = cursor.isNull(1) ? "未命名" : cursor.getString(1);
                    String mime = cursor.isNull(2) ? "" : cursor.getString(2);
                    long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                    long modified = cursor.isNull(4) ? 0L : cursor.getLong(4);
                    String path = folder.path.isEmpty() ? name : folder.path + "/" + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.addLast(new FolderNode(documentId, path));
                        continue;
                    }
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;

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
                progress("正在建立 PNG 索引：" + folders + " 个文件夹，"
                        + result.size() + " 张 PNG");
            }
        }
        result.sort((left, right) -> left.path.compareToIgnoreCase(right.path));
        return result;
    }

    private static JSONObject decodePayload(String encoded) throws Exception {
        String value = encoded == null ? "" : encoded.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("内容为空");
        try {
            byte[] decoded = Base64.decode(value, Base64.DEFAULT);
            String json = new String(decoded, StandardCharsets.UTF_8).trim();
            if (!json.startsWith("{")) throw new IllegalArgumentException("Base64 内容不是 JSON");
            return new JSONObject(json);
        } catch (Exception base64Error) {
            if (value.startsWith("{")) return new JSONObject(value);
            throw base64Error;
        }
    }

    private static TextEntry parseTextEntry(String type, byte[] bytes) throws Exception {
        int firstZero = indexOfZero(bytes, 0);
        if (firstZero <= 0) return null;
        String keyword = new String(bytes, 0, firstZero,
                StandardCharsets.ISO_8859_1);

        if ("tEXt".equals(type)) {
            return new TextEntry(keyword, new String(bytes, firstZero + 1,
                    bytes.length - firstZero - 1, StandardCharsets.ISO_8859_1));
        }
        if ("zTXt".equals(type)) {
            int start = firstZero + 2;
            if (start > bytes.length) return null;
            byte[] inflated = inflate(bytes, start, bytes.length - start);
            return new TextEntry(keyword,
                    new String(inflated, StandardCharsets.ISO_8859_1));
        }

        int position = firstZero + 1;
        if (position + 2 > bytes.length) return null;
        int compressionFlag = bytes[position] & 0xff;
        position += 2;
        int languageEnd = indexOfZero(bytes, position);
        if (languageEnd < 0) return null;
        position = languageEnd + 1;
        int translatedEnd = indexOfZero(bytes, position);
        if (translatedEnd < 0) return null;
        position = translatedEnd + 1;
        byte[] textBytes;
        if (compressionFlag == 1) {
            textBytes = inflate(bytes, position, bytes.length - position);
        } else {
            textBytes = new byte[bytes.length - position];
            System.arraycopy(bytes, position, textBytes, 0, textBytes.length);
        }
        return new TextEntry(keyword,
                new String(textBytes, StandardCharsets.UTF_8));
    }

    private static String markerSummary(List<String> names) {
        if (names.isEmpty()) return "没有角色卡数据块";
        List<String> copy = new ArrayList<>(names);
        Collections.sort(copy);
        return "检测到：" + String.join("、", copy);
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static byte[] inflate(byte[] bytes, int offset, int length) throws Exception {
        try (InflaterInputStream inflater = new InflaterInputStream(
                new ByteArrayInputStream(bytes, offset, length));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inflater.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static byte[] readExact(DataInputStream input, int length) throws Exception {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void skipFully(InputStream input, long count) throws Exception {
        byte[] buffer = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IllegalArgumentException("PNG 文件数据不完整");
            remaining -= read;
        }
    }

    private static int indexOfZero(byte[] bytes, int start) {
        for (int i = Math.max(0, start); i < bytes.length; i++) {
            if (bytes[i] == 0) return i;
        }
        return -1;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
