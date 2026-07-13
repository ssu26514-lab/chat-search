package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class CardMoveManager {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class MoveResult {
        int moved;
        int skipped;
        int failed;
        final List<String> completedSourceUris = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
    }

    private static final class Metadata {
        final String name;
        final long size;
        final long modified;

        Metadata(String name, long size, long modified) {
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    CardMoveManager(ContentResolver resolver, AtomicBoolean cancelled, ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    MoveResult moveFavorites(List<CharacterCard> cards, Uri targetTreeUri) throws Exception {
        MoveResult result = new MoveResult();
        String targetRootId = DocumentsContract.getTreeDocumentId(targetTreeUri);
        Uri targetRootUri = DocumentsContract.buildDocumentUriUsingTree(targetTreeUri, targetRootId);
        Set<String> usedNames = listExistingNames(targetTreeUri, targetRootId);

        for (int i = 0; i < cards.size(); i++) {
            checkCancelled();
            CharacterCard card = cards.get(i);
            progress("正在移动收藏：" + (i + 1) + " / " + cards.size());

            Uri sourceUri = Uri.parse(card.uri);
            if (sameFolder(card, targetTreeUri, targetRootId)) {
                result.skipped++;
                result.log.append("跳过（文件已在目标文件夹）：").append(card.path).append('\n');
                continue;
            }

            Metadata current = queryMetadata(sourceUri);
            if (current == null) {
                result.skipped++;
                result.log.append("跳过（源文件已不存在或不可读取）：").append(card.path).append('\n');
                continue;
            }
            if (card.size >= 0 && current.size >= 0 && card.size != current.size) {
                result.skipped++;
                result.log.append("跳过（收藏后文件大小发生变化）：").append(card.path).append('\n');
                continue;
            }
            if (card.modified > 0 && current.modified > 0 && card.modified != current.modified) {
                result.skipped++;
                result.log.append("跳过（收藏后文件发生变化）：").append(card.path).append('\n');
                continue;
            }

            String targetName = uniqueName(current.name, usedNames);
            Uri targetUri = null;
            try {
                targetUri = DocumentsContract.createDocument(
                        resolver, targetRootUri, "image/png", targetName);
                if (targetUri == null) throw new IllegalStateException("系统无法创建目标文件");

                String sourceHash = copyAndHash(sourceUri, targetUri);
                String targetHash = hash(targetUri);
                if (!sourceHash.equals(targetHash)) {
                    safeDelete(targetUri);
                    result.failed++;
                    result.log.append("移动失败（目标文件校验不一致，已撤销）：")
                            .append(card.path).append('\n');
                    continue;
                }

                Metadata targetMeta = queryMetadata(targetUri);
                if (targetMeta == null || (current.size >= 0 && targetMeta.size >= 0
                        && current.size != targetMeta.size)) {
                    safeDelete(targetUri);
                    result.failed++;
                    result.log.append("移动失败（目标文件大小校验失败，已撤销）：")
                            .append(card.path).append('\n');
                    continue;
                }

                boolean sourceDeleted = DocumentsContract.deleteDocument(resolver, sourceUri);
                if (!sourceDeleted) {
                    boolean rolledBack = safeDelete(targetUri);
                    result.failed++;
                    result.log.append("移动失败（源文件无法删除")
                            .append(rolledBack ? "，目标副本已撤销" : "，目标副本可能已保留")
                            .append("）：").append(card.path).append('\n');
                    continue;
                }

                usedNames.add(targetName.toLowerCase(Locale.ROOT));
                result.moved++;
                result.completedSourceUris.add(card.uri);
                result.log.append("已移动：").append(card.path)
                        .append(" → ").append(targetName).append('\n');
            } catch (Exception e) {
                if (targetUri != null) safeDelete(targetUri);
                result.failed++;
                result.log.append("移动失败：").append(card.path)
                        .append("；").append(safeMessage(e)).append('\n');
            }
        }
        return result;
    }

    private String copyAndHash(Uri source, Uri target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream rawIn = resolver.openInputStream(source);
             OutputStream rawOut = resolver.openOutputStream(target, "w")) {
            if (rawIn == null || rawOut == null) throw new IllegalStateException("无法打开源文件或目标文件");
            try (BufferedInputStream input = new BufferedInputStream(rawIn, buffer.length);
                 BufferedOutputStream output = new BufferedOutputStream(rawOut, buffer.length)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                output.flush();
            }
        }
        return toHex(digest.digest());
    }

    private String hash(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开校验文件");
            try (BufferedInputStream input = new BufferedInputStream(raw, buffer.length)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    digest.update(buffer, 0, read);
                }
            }
        }
        return toHex(digest.digest());
    }

    private Set<String> listExistingNames(Uri treeUri, String parentId) throws Exception {
        Set<String> names = new HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("无法读取目标文件夹");
            while (cursor.moveToNext()) {
                String name = cursor.isNull(0) ? "" : cursor.getString(0);
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Metadata queryMetadata(Uri uri) {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String name = cursor.isNull(0) ? "未命名.png" : cursor.getString(0);
            long size = cursor.isNull(1) ? -1L : cursor.getLong(1);
            long modified = cursor.isNull(2) ? 0L : cursor.getLong(2);
            return new Metadata(name, size, modified);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sameFolder(CharacterCard card, Uri targetTreeUri, String targetRootId) {
        try {
            Uri sourceTree = Uri.parse(card.treeUri);
            return sourceTree.getAuthority() != null
                    && sourceTree.getAuthority().equals(targetTreeUri.getAuthority())
                    && targetRootId.equals(card.parentDocumentId);
        } catch (Exception e) {
            return false;
        }
    }

    private static String uniqueName(String original, Set<String> usedNames) {
        String base = original;
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0) {
            base = original.substring(0, dot);
            ext = original.substring(dot);
        }
        String candidate = original;
        int counter = 1;
        while (usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "(" + counter + ")" + ext;
            counter++;
        }
        return candidate;
    }

    private boolean safeDelete(Uri uri) {
        try {
            return DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private void progress(String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
