package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class SemanticCardScanner {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    enum GroupType {
        EXACT_CONTENT,
        RELATED_VARIANTS
    }

    static final class Group {
        final String id;
        final GroupType type;
        final String title;
        final List<SemanticCardParser.CardRecord> cards;
        final boolean safeDelete;

        Group(String id, GroupType type, String title,
              List<SemanticCardParser.CardRecord> cards, boolean safeDelete) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.cards = cards;
            this.safeDelete = safeDelete;
        }

        int duplicateCopies() {
            return type == GroupType.EXACT_CONTENT
                    ? Math.max(0, cards.size() - 1) : 0;
        }
    }

    static final class Failure {
        final String path;
        final String reason;

        Failure(String path, String reason) {
            this.path = path;
            this.reason = reason;
        }
    }

    static final class ScanResult {
        final int totalPng;
        final int totalFiles;
        final int parsedCards;
        final int failedCards;
        final long elapsedMs;
        final List<Group> exactGroups;
        final List<Group> variantGroups;
        final List<Failure> failures;

        ScanResult(int totalFiles, int parsedCards, int failedCards, long elapsedMs,
                   List<Group> exactGroups, List<Group> variantGroups,
                   List<Failure> failures) {
            this.totalPng = totalFiles;
            this.totalFiles = totalFiles;
            this.parsedCards = parsedCards;
            this.failedCards = failedCards;
            this.elapsedMs = elapsedMs;
            this.exactGroups = exactGroups;
            this.variantGroups = variantGroups;
            this.failures = failures;
        }

        int exactDuplicateCopies() {
            int count = 0;
            for (Group group : exactGroups) count += group.duplicateCopies();
            return count;
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

    SemanticCardScanner(ContentResolver resolver, AtomicBoolean cancelled,
                        ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<SemanticCardParser.CardRecord> seeds = enumerateSupported(treeUri);
        List<SemanticCardParser.CardRecord> parsed = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (int i = 0; i < seeds.size(); i++) {
            checkCancelled();
            SemanticCardParser.CardRecord seed = seeds.get(i);
            progress("正在解析 PNG / JSON 角色卡有效内容："
                    + (i + 1) + " / " + seeds.size() + "\n" + seed.path);
            try {
                SemanticCardParser.ParsedPayload payload =
                        UniversalSemanticCardParser.parse(resolver, seed);
                payload.record.regexCount =
                        SemanticRegexExtractor.count(payload.extensions);
                parsed.add(payload.record);
            } catch (Exception e) {
                failures.add(new Failure(seed.path, safeMessage(e)));
            }
        }

        List<Group> exact = buildExactGroups(parsed);
        List<Group> variants = buildVariantGroups(parsed);
        sortGroups(exact);
        sortGroups(variants);
        progress("分析完成：有效内容相同 " + exact.size()
                + " 组，存在差异 " + variants.size() + " 组");
        return new ScanResult(seeds.size(), parsed.size(), failures.size(),
                System.currentTimeMillis() - started, exact, variants, failures);
    }

    private List<SemanticCardParser.CardRecord> enumerateSupported(Uri treeUri)
            throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        List<SemanticCardParser.CardRecord> result = new ArrayList<>();
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

                    SemanticCardParser.CardRecord record =
                            new SemanticCardParser.CardRecord();
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, documentId);
                    record.key = documentUri.toString();
                    record.treeUri = treeUri.toString();
                    record.uri = documentUri.toString();
                    record.parentDocumentId = folder.documentId;
                    record.path = path;
                    record.fileName = name;
                    record.characterName = stripExtension(name);
                    record.size = size;
                    record.modified = modified;
                    result.add(record);
                }
            }
            if (folders % 10 == 0 || queue.isEmpty()) {
                progress("正在建立 PNG / JSON 索引：" + folders
                        + " 个文件夹，" + result.size() + " 个候选文件");
            }
        }
        result.sort(Comparator.comparing(record -> record.path,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static List<Group> buildExactGroups(
            List<SemanticCardParser.CardRecord> cards) {
        Map<String, List<SemanticCardParser.CardRecord>> byHash =
                new LinkedHashMap<>();
        for (SemanticCardParser.CardRecord card : cards) {
            byHash.computeIfAbsent(card.functionalHash,
                    ignored -> new ArrayList<>()).add(card);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<SemanticCardParser.CardRecord>> entry
                : byHash.entrySet()) {
            List<SemanticCardParser.CardRecord> members = entry.getValue();
            if (members.size() < 2) continue;
            boolean safe = true;
            for (SemanticCardParser.CardRecord member : members) {
                if (member.payloadConflict) safe = false;
            }
            groups.add(new Group("exact:" + entry.getKey(),
                    GroupType.EXACT_CONTENT, bestTitle(members),
                    new ArrayList<>(members), safe));
        }
        return groups;
    }

    private static List<Group> buildVariantGroups(
            List<SemanticCardParser.CardRecord> cards) {
        Map<String, List<SemanticCardParser.CardRecord>> byName =
                new LinkedHashMap<>();
        for (SemanticCardParser.CardRecord card : cards) {
            String name = card.normalizedName();
            if (name.isEmpty() || "未命名".equals(name)
                    || "unknown".equals(name)) continue;
            byName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(card);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<SemanticCardParser.CardRecord>> entry
                : byName.entrySet()) {
            List<SemanticCardParser.CardRecord> members = entry.getValue();
            if (members.size() < 2) continue;
            Set<String> hashes = new HashSet<>();
            for (SemanticCardParser.CardRecord card : members) {
                hashes.add(card.functionalHash);
            }
            if (hashes.size() < 2) continue;
            groups.add(new Group("variant:" + entry.getKey(),
                    GroupType.RELATED_VARIANTS, bestTitle(members),
                    new ArrayList<>(members), false));
        }
        return groups;
    }

    private static String bestTitle(List<SemanticCardParser.CardRecord> cards) {
        Map<String, Integer> counts = new HashMap<>();
        for (SemanticCardParser.CardRecord card : cards) {
            String name = card.characterName == null
                    ? "" : card.characterName.trim();
            if (!name.isEmpty()) {
                counts.put(name, counts.getOrDefault(name, 0) + 1);
            }
        }
        String best = "未命名角色卡";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static void sortGroups(List<Group> groups) {
        groups.sort((left, right) -> {
            int bySize = Integer.compare(right.cards.size(), left.cards.size());
            if (bySize != 0) return bySize;
            return left.title.compareToIgnoreCase(right.title);
        });
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static String stripExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : String.valueOf(name);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
