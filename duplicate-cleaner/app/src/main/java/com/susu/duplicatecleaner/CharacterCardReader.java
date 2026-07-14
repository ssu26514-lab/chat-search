package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

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
        final int totalFiles;
        final int success;
        final int failed;
        final long elapsedMs;
        final List<CharacterCard> cards;

        ScanResult(int totalFiles, int success, int failed,
                   long elapsedMs, List<CharacterCard> cards) {
            this.totalPng = totalFiles;
            this.totalFiles = totalFiles;
            this.success = success;
            this.failed = failed;
            this.elapsedMs = elapsedMs;
            this.cards = cards;
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

    private static final class EnumerationResult {
        final List<CharacterCard> cards = new ArrayList<>();
        int supportedFiles;
        int skippedJson;
    }

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    CharacterCardReader(ContentResolver resolver, AtomicBoolean cancelled,
                        ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        EnumerationResult enumeration = enumerateCards(treeUri);
        enumeration.cards.sort(NATURAL_CARD_COMPARATOR);
        progress("轻量索引完成：" + enumeration.cards.size()
                + " 张 PNG / JSON 角色卡；PNG 的人设和开场白将在点击查看时读取");
        return new ScanResult(enumeration.supportedFiles,
                enumeration.cards.size(), enumeration.skippedJson,
                System.currentTimeMillis() - started, enumeration.cards);
    }

    CharacterCard reload(CharacterCard card) throws Exception {
        SemanticCardParser.CardRecord seed = toSemanticSeed(card);
        SemanticCardParser.ParsedPayload payload =
                UniversalSemanticCardParser.parse(resolver, seed);
        fillCardContent(card, payload.root, payload.data);
        card.fileFormat = isJson(card.fileName) ? "JSON" : "PNG";
        return card;
    }

    private EnumerationResult enumerateCards(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        EnumerationResult result = new EnumerationResult();
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
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, folder.documentId);
            try (Cursor cursor = resolver.query(childrenUri, projection,
                    null, null, null)) {
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
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".png") && !lower.endsWith(".json")) continue;
                    result.supportedFiles++;

                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, documentId);
                    CharacterCard card = new CharacterCard();
                    card.key = documentUri.toString();
                    card.treeUri = treeUri.toString();
                    card.uri = documentUri.toString();
                    card.parentDocumentId = folder.documentId;
                    card.path = path;
                    card.fileName = name;
                    card.characterName = stripExtension(name);
                    card.size = size;
                    card.modified = modified;
                    card.fileFormat = lower.endsWith(".json") ? "JSON" : "PNG";

                    if (lower.endsWith(".json")) {
                        try {
                            JSONObject root = JsonDocumentReader.readObject(
                                    resolver, documentUri);
                            RoleCardHeuristics.Result role = RoleCardHeuristics.inspect(root);
                            if (!role.roleCard) {
                                result.skippedJson++;
                                continue;
                            }
                            fillCardContent(card, role.root, role.data);
                        } catch (Exception e) {
                            result.skippedJson++;
                            continue;
                        }
                    }
                    result.cards.add(card);
                }
            }
            if (folders % 10 == 0 || queue.isEmpty()) {
                progress("正在建立 PNG / JSON 角色卡索引：" + folders
                        + " 个文件夹，" + result.cards.size() + " 张角色卡");
            }
        }
        return result;
    }

    private static SemanticCardParser.CardRecord toSemanticSeed(CharacterCard card) {
        SemanticCardParser.CardRecord seed = new SemanticCardParser.CardRecord();
        seed.key = card.key;
        seed.treeUri = card.treeUri;
        seed.uri = card.uri;
        seed.parentDocumentId = card.parentDocumentId;
        seed.path = card.path;
        seed.fileName = card.fileName;
        seed.characterName = card.characterName;
        seed.size = card.size;
        seed.modified = card.modified;
        return seed;
    }

    private static void fillCardContent(CharacterCard card, JSONObject root,
                                        JSONObject source) {
        if (source == null) source = root;
        String name = clean(source.optString("name", root.optString("name", "")));
        if (!name.isEmpty()) card.characterName = name;

        List<String> personaParts = new ArrayList<>();
        appendSection(personaParts, "角色描述", source.optString("description", ""));
        appendSection(personaParts, "性格", source.optString("personality", ""));
        appendSection(personaParts, "场景", source.optString("scenario", ""));
        appendSection(personaParts, "示例对话", source.optString("mes_example", ""));
        if (personaParts.isEmpty()) {
            appendSection(personaParts, "角色人设",
                    source.optString("char_persona", ""));
        }
        card.persona = personaParts.isEmpty()
                ? "（没有读取到人设内容）" : joinSections(personaParts);

        List<String> greetings = new ArrayList<>();
        addGreeting(greetings, source.optString("first_mes",
                root.optString("first_mes", "")));
        JSONArray alternates = source.optJSONArray("alternate_greetings");
        if (alternates == null) alternates = root.optJSONArray("alternate_greetings");
        if (alternates != null) {
            for (int i = 0; i < alternates.length(); i++) {
                addGreeting(greetings, alternates.optString(i, ""));
            }
        }
        card.greetings = greetings;
        card.error = null;
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

    private static boolean isJson(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private static final Comparator<CharacterCard> NATURAL_CARD_COMPARATOR =
            (a, b) -> naturalCompare(a.path, b.path);

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
                if (numA.length() != numB.length()) {
                    return Integer.compare(numA.length(), numB.length());
                }
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
