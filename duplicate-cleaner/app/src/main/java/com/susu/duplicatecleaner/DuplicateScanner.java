package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class DuplicateScanner {
    static final int BUFFER_SIZE = 1024 * 1024;

    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class FileEntry {
        final Uri uri;
        final String documentId;
        final String path;
        final long size;
        final long modified;
        String sha256;
        String error;

        FileEntry(Uri uri, String documentId, String path, long size, long modified) {
            this.uri = uri;
            this.documentId = documentId;
            this.path = path;
            this.size = size;
            this.modified = modified;
        }
    }

    static final class DuplicateGroup {
        final List<FileEntry> files;

        DuplicateGroup(List<FileEntry> files) {
            this.files = files;
        }

        FileEntry keeper() {
            return files.get(0);
        }

        long reclaimableBytes() {
            return keeper().size * Math.max(0, files.size() - 1L);
        }
    }

    static final class ScanResult {
        final int totalFiles;
        final int unknownSizeFiles;
        final int unreadableFiles;
        final int byteComparisons;
        final long elapsedMs;
        final List<DuplicateGroup> groups;

        ScanResult(int totalFiles, int unknownSizeFiles, int unreadableFiles,
                   int byteComparisons, long elapsedMs, List<DuplicateGroup> groups) {
            this.totalFiles = totalFiles;
            this.unknownSizeFiles = unknownSizeFiles;
            this.unreadableFiles = unreadableFiles;
            this.byteComparisons = byteComparisons;
            this.elapsedMs = elapsedMs;
            this.groups = groups;
        }

        int duplicateCopies() {
            int count = 0;
            for (DuplicateGroup group : groups) count += group.files.size() - 1;
            return count;
        }

        long reclaimableBytes() {
            long total = 0;
            for (DuplicateGroup group : groups) total += group.reclaimableBytes();
            return total;
        }
    }

    static final class DeleteResult {
        int deleted;
        int skipped;
        int failed;
        long reclaimedBytes;
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

    private static final Comparator<FileEntry> KEEPER_ORDER = (a, b) -> {
        int byDepth = Integer.compare(pathDepth(a.path), pathDepth(b.path));
        if (byDepth != 0) return byDepth;
        int byLength = Integer.compare(a.path.length(), b.path.length());
        if (byLength != 0) return byLength;
        return a.path.compareToIgnoreCase(b.path);
    };

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    DuplicateScanner(ContentResolver resolver, AtomicBoolean cancelled, ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    ScanResult scan(Uri treeUri) throws Exception {
        long started = System.currentTimeMillis();
        List<FileEntry> allFiles = enumerate(treeUri);
        checkCancelled();

        Map<Long, List<FileEntry>> bySize = new HashMap<>();
        int unknownSize = 0;
        for (FileEntry file : allFiles) {
            if (file.size < 0) {
                unknownSize++;
                continue;
            }
            bySize.computeIfAbsent(file.size, ignored -> new ArrayList<>()).add(file);
        }

        List<FileEntry> candidates = new ArrayList<>();
        for (List<FileEntry> sameSize : bySize.values()) {
            if (sameSize.size() > 1) candidates.addAll(sameSize);
        }
        progress("共发现 " + allFiles.size() + " 个文件；" + candidates.size() + " 个进入完整哈希校验。");

        Map<String, List<FileEntry>> bySizeAndHash = new LinkedHashMap<>();
        int unreadable = 0;
        for (int i = 0; i < candidates.size(); i++) {
            checkCancelled();
            FileEntry file = candidates.get(i);
            try {
                file.sha256 = sha256(file.uri);
                String key = file.size + ":" + file.sha256;
                bySizeAndHash.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            } catch (Exception e) {
                file.error = safeMessage(e);
                unreadable++;
            }
            if ((i + 1) % 10 == 0 || i + 1 == candidates.size()) {
                progress("正在计算完整 SHA-256：" + (i + 1) + " / " + candidates.size());
            }
        }

        List<DuplicateGroup> exactGroups = new ArrayList<>();
        int comparisons = 0;
        for (List<FileEntry> hashGroup : bySizeAndHash.values()) {
            checkCancelled();
            if (hashGroup.size() < 2) continue;

            List<List<FileEntry>> exactPartitions = new ArrayList<>();
            for (FileEntry file : hashGroup) {
                checkCancelled();
                boolean matched = false;
                for (List<FileEntry> partition : exactPartitions) {
                    comparisons++;
                    if (exactlyEqual(partition.get(0).uri, file.uri)) {
                        partition.add(file);
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    List<FileEntry> partition = new ArrayList<>();
                    partition.add(file);
                    exactPartitions.add(partition);
                }
                if (comparisons % 10 == 0) {
                    progress("正在逐字节核对：已完成 " + comparisons + " 次比较");
                }
            }

            for (List<FileEntry> partition : exactPartitions) {
                if (partition.size() > 1) {
                    partition.sort(KEEPER_ORDER);
                    exactGroups.add(new DuplicateGroup(partition));
                }
            }
        }

        exactGroups.sort((a, b) -> Long.compare(b.reclaimableBytes(), a.reclaimableBytes()));
        return new ScanResult(allFiles.size(), unknownSize, unreadable, comparisons,
                System.currentTimeMillis() - started, exactGroups);
    }

    DeleteResult deleteVerifiedCopies(List<DuplicateGroup> groups) throws Exception {
        DeleteResult result = new DeleteResult();
        int total = 0;
        for (DuplicateGroup group : groups) total += Math.max(0, group.files.size() - 1);
        int processed = 0;

        for (DuplicateGroup group : groups) {
            checkCancelled();
            FileEntry keeper = group.keeper();
            for (int i = 1; i < group.files.size(); i++) {
                checkCancelled();
                FileEntry candidate = group.files.get(i);
                processed++;
                progress("删除前再次复核：" + processed + " / " + total);

                try {
                    Metadata keeperNow = queryMetadata(keeper.uri);
                    Metadata candidateNow = queryMetadata(candidate.uri);
                    if (keeperNow == null || candidateNow == null) {
                        result.skipped++;
                        result.log.append("跳过（文件已不存在）：").append(candidate.path).append('\n');
                        continue;
                    }
                    if (keeperNow.size < 0 || candidateNow.size < 0 || keeperNow.size != candidateNow.size) {
                        result.skipped++;
                        result.log.append("跳过（大小已变化或未知）：").append(candidate.path).append('\n');
                        continue;
                    }
                    if (keeperNow.size != keeper.size || candidateNow.size != candidate.size) {
                        result.skipped++;
                        result.log.append("跳过（扫描后大小发生变化）：").append(candidate.path).append('\n');
                        continue;
                    }

                    String keeperHashNow = sha256(keeper.uri);
                    String candidateHashNow = sha256(candidate.uri);
                    if (!keeper.sha256.equals(keeperHashNow)
                            || !candidate.sha256.equals(candidateHashNow)
                            || !keeperHashNow.equals(candidateHashNow)) {
                        result.skipped++;
                        result.log.append("跳过（扫描后内容发生变化）：").append(candidate.path).append('\n');
                        continue;
                    }
                    if (!exactlyEqual(keeper.uri, candidate.uri)) {
                        result.skipped++;
                        result.log.append("跳过（逐字节复核不一致）：").append(candidate.path).append('\n');
                        continue;
                    }

                    boolean deleted = DocumentsContract.deleteDocument(resolver, candidate.uri);
                    if (deleted) {
                        result.deleted++;
                        result.reclaimedBytes += candidate.size;
                        result.log.append("已删除：").append(candidate.path)
                                .append("；保留：").append(keeper.path).append('\n');
                    } else {
                        result.failed++;
                        result.log.append("删除失败（系统返回 false）：").append(candidate.path).append('\n');
                    }
                } catch (SecurityException e) {
                    result.failed++;
                    result.log.append("删除失败（权限不足）：").append(candidate.path)
                            .append("；").append(safeMessage(e)).append('\n');
                } catch (Exception e) {
                    result.failed++;
                    result.log.append("删除失败：").append(candidate.path)
                            .append("；").append(safeMessage(e)).append('\n');
                }
            }
        }
        return result;
    }

    private List<FileEntry> enumerate(Uri treeUri) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, ""));
        List<FileEntry> files = new ArrayList<>();
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
                    } else {
                        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                        files.add(new FileEntry(documentUri, documentId, path, size, modified));
                    }
                }
            }

            if (folderCount % 10 == 0 || queue.isEmpty()) {
                progress("正在枚举目录：" + folderCount + " 个文件夹，" + files.size() + " 个文件");
            }
        }
        return files;
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开文件");
            try (BufferedInputStream input = new BufferedInputStream(raw, BUFFER_SIZE)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private boolean exactlyEqual(Uri first, Uri second) throws Exception {
        byte[] a = new byte[BUFFER_SIZE];
        byte[] b = new byte[BUFFER_SIZE];
        try (InputStream rawA = resolver.openInputStream(first);
             InputStream rawB = resolver.openInputStream(second)) {
            if (rawA == null || rawB == null) throw new IllegalStateException("无法打开待比较文件");
            try (BufferedInputStream inA = new BufferedInputStream(rawA, BUFFER_SIZE);
                 BufferedInputStream inB = new BufferedInputStream(rawB, BUFFER_SIZE)) {
                while (true) {
                    checkCancelled();
                    int readA = readChunk(inA, a);
                    int readB = readChunk(inB, b);
                    if (readA != readB) return false;
                    if (readA == -1) return true;
                    for (int i = 0; i < readA; i++) if (a[i] != b[i]) return false;
                }
            }
        }
    }

    private static int readChunk(InputStream input, byte[] buffer) throws Exception {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read == -1) return total == 0 ? -1 : total;
            total += read;
        }
        return total;
    }

    private Metadata queryMetadata(Uri uri) {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            long size = cursor.isNull(0) ? -1L : cursor.getLong(0);
            long modified = cursor.isNull(1) ? 0L : cursor.getLong(1);
            return new Metadata(size, modified);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Metadata {
        final long size;
        final long modified;

        Metadata(long size, long modified) {
            this.size = size;
            this.modified = modified;
        }
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static int pathDepth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
