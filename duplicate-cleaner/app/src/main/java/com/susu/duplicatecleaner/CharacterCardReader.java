package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class CharacterCardReader {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class ScanResult {
        final int totalPng;
        final int success;
        final int failed;
        final long elapsedMs;
        final List<CharacterCard> cards;

        ScanResult(int totalPng, int success, int failed, long elapsedMs, List<CharacterCard> cards) {
            this.totalPng = totalPng;
            this.success = success;
            this.failed = failed;
            this.elapsedMs = elapsedMs;
            this.cards = cards;
        }
    }

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int MAX_TEXT_CHUNK = 64 * 1024 * 1024;

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

    CharacterCardReader(ContentResolver resolver, AtomicBoolean cancelled, ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<CharacterCard> cards = enumeratePng(treeUri);
        int success = 0;
        int failed = 0;
        for (int i = 0; i < cards.size(); i++) {
            checkCancelled();
            CharacterCard card = cards.get(i);
            try {
                fillCardContent(card);
                success++;
            } catch (Exception e) {
                card.error = safeMessage(e);
                failed++;
            }
            if ((i + 1) % 5 == 0 || i + 1 == cards.size()) {
                progress("正在解析角色卡：" + (i + 1) + " / " + cards.size());
            }
        }
        cards.sort(NATURAL_CARD_COMPARATOR);
        return new ScanResult(cards.size(), success, failed,
                System.currentTimeMillis() - started, cards);
    }

    CharacterCard reload(CharacterCard card) throws Exception {
        CharacterCard fresh = new CharacterCard();
        fresh.key = card.key;
        fresh.treeUri = card.treeUri;
        fresh.uri = card.uri;
        fresh.parentDocumentId = card.parentDocumentId;
        fresh.path = card.path;
        fresh.fileName = card.fileName;
        fresh.size = card.size;
        fresh.modified = card.modified;
        fillCardContent(fresh);
        return fresh;
    }

    private List<CharacterCard> enumeratePng(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        List<CharacterCard> cards = new ArrayList<>();
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
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId);
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

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.addLast(new FolderNode(documentId, path));
                        continue;
                    }
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;

                    CharacterCard card = new CharacterCard();
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    card.key = documentUri.toString();
                    card.treeUri = treeUri.toString();
                    card.uri = documentUri.toString();
                    card.parentDocumentId = folder.documentId;
                    card.path = path;
                    card.fileName = name;
                    card.characterName = stripExtension(name);
                    card.size = size;
                    card.modified = modified;
                    cards.add(card);
                }
            }
            if (folders % 10 == 0 || queue.isEmpty()) {
                progress("正在枚举目录：" + folders + " 个文件夹，" + cards.size() + " 张 PNG");
            }
        }
        return cards;
    }

    private void fillCardContent(CharacterCard card) throws Exception {
        JSONObject json = readEmbeddedJson(card.contentUri());
        JSONObject data = json.optJSONObject("data");
        JSONObject source = data != null ? data : json;

        String name = clean(source.optString("name", json.optString("name", "")));
        if (!name.isEmpty()) card.characterName = name;

        List<String> personaParts = new ArrayList<>();
        appendSection(personaParts, "角色描述", source.optString("description", ""));
        appendSection(personaParts, "性格", source.optString("personality", ""));
        appendSection(personaParts, "场景", source.optString("scenario", ""));
        appendSection(personaParts, "示例对话", source.optString("mes_example", ""));
        if (personaParts.isEmpty()) {
            String fallback = source.optString("char_persona", "");
            appendSection(personaParts, "角色人设", fallback);
        }
        card.persona = personaParts.isEmpty() ? "（没有读取到人设内容）" : joinSections(personaParts);

        List<String> greetings = new ArrayList<>();
        addGreeting(greetings, source.optString("first_mes", json.optString("first_mes", "")));
        JSONArray alternates = source.optJSONArray("alternate_greetings");
        if (alternates == null) alternates = json.optJSONArray("alternate_greetings");
        if (alternates != null) {
            for (int i = 0; i < alternates.length(); i++) {
                addGreeting(greetings, alternates.optString(i, ""));
            }
        }
        card.greetings = greetings;
        card.error = null;
    }

    private JSONObject readEmbeddedJson(Uri uri) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开 PNG 文件");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(raw, 128 * 1024))) {
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

                    if ("tEXt".equals(type) && length <= MAX_TEXT_CHUNK) {
                        byte[] data = new byte[(int) length];
                        input.readFully(data);
                        JSONObject json = parseTextChunk(data);
                        skipFully(input, 4L);
                        if (json != null) return json;
                    } else {
                        skipFully(input, length + 4L);
                    }
                    if ("IEND".equals(type)) break;
                }
            }
        }
        throw new IllegalArgumentException("PNG 内没有识别到 chara / ccv3 角色数据");
    }

    private JSONObject parseTextChunk(byte[] data) throws Exception {
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
        return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
    }

    private static void appendSection(List<String> parts, String title, String value) {
        String cleaned = clean(value);
        if (!cleaned.isEmpty()) parts.add("【" + title + "】\n" + cleaned);
    }

    private static String joinSections(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) builder.append("\n\n");
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    private static void addGreeting(List<String> greetings, String value) {
        String cleaned = clean(value);
        if (!cleaned.isEmpty()) greetings.add(cleaned);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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

    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private static final Comparator<CharacterCard> NATURAL_CARD_COMPARATOR = (a, b) ->
            naturalCompare(a.path, b.path);

    private static int naturalCompare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startI = i;
                int startJ = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                String numA = left.substring(startI, i).replaceFirst("^0+(?!$)", "");
                String numB = right.substring(startJ, j).replaceFirst("^0+(?!$)", "");
                if (numA.length() != numB.length()) return Integer.compare(numA.length(), numB.length());
                int byNumber = numA.compareTo(numB);
                if (byNumber != 0) return byNumber;
                continue;
            }
            int nextI = i;
            int nextJ = j;
            while (nextI < left.length() && !Character.isDigit(left.charAt(nextI))) nextI++;
            while (nextJ < right.length() && !Character.isDigit(right.charAt(nextJ))) nextJ++;
            String partA = left.substring(i, nextI);
            String partB = right.substring(j, nextJ);
            int byText = COLLATOR.compare(partA, partB);
            if (byText != 0) return byText;
            i = nextI;
            j = nextJ;
        }
        return Integer.compare(left.length(), right.length());
    }
}
