package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class SemanticDeleteManager {
    interface ProgressListener {
        void onProgress(String message);
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("任务已取消");
        }
    }

    static final class DeleteResult {
        int deleted;
        int skipped;
        int failed;
        final List<String> deletedUris = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
    }

    private final ContentResolver resolver;
    private final AtomicBoolean cancelled;
    private final ProgressListener listener;

    SemanticDeleteManager(ContentResolver resolver, AtomicBoolean cancelled,
                          ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    DeleteResult deleteNonKeeper(SemanticCardScanner.Group group,
                                  SemanticCardParser.CardRecord keeper) throws Exception {
        DeleteResult result = new DeleteResult();
        if (group.type != SemanticCardScanner.GroupType.EXACT_CONTENT) {
            throw new IllegalArgumentException("只有有效内容完全一致的组才能批量删除");
        }
        if (!group.safeDelete || keeper.payloadConflict) {
            throw new IllegalArgumentException("该组内部数据存在冲突，不允许自动删除");
        }

        checkCancelled();
        progress("正在重新解析保留文件……");
        SemanticCardParser.ParsedPayload currentKeeper =
                UniversalSemanticCardParser.parse(resolver, keeper);
        if (!keeper.functionalHash.equals(currentKeeper.record.functionalHash)
                || currentKeeper.record.payloadConflict) {
            throw new IllegalStateException("保留文件在扫描后发生变化，已停止删除");
        }

        int total = Math.max(0, group.cards.size() - 1);
        int index = 0;
        for (SemanticCardParser.CardRecord card : group.cards) {
            if (card.uri.equals(keeper.uri)) continue;
            checkCancelled();
            index++;
            progress("正在重新验证并删除：" + index + " / " + total
                    + "\n" + card.path);
            try {
                SemanticCardParser.ParsedPayload current =
                        UniversalSemanticCardParser.parse(resolver, card);
                if (!card.functionalHash.equals(current.record.functionalHash)) {
                    result.skipped++;
                    result.log.append("安全跳过（文件在扫描后发生变化）：")
                            .append(card.path).append('\n');
                    continue;
                }
                if (current.record.payloadConflict) {
                    result.skipped++;
                    result.log.append("安全跳过（内部角色卡数据冲突）：")
                            .append(card.path).append('\n');
                    continue;
                }
                if (!currentKeeper.record.functionalHash
                        .equals(current.record.functionalHash)) {
                    result.skipped++;
                    result.log.append("安全跳过（有效内容不再与保留文件一致）：")
                            .append(card.path).append('\n');
                    continue;
                }

                boolean deleted = DocumentsContract.deleteDocument(
                        resolver, Uri.parse(card.uri));
                if (deleted) {
                    result.deleted++;
                    result.deletedUris.add(card.uri);
                    result.log.append("已删除语义重复副本：")
                            .append(card.path).append('\n');
                } else {
                    result.failed++;
                    result.log.append("删除失败（系统未授权或文件被占用）：")
                            .append(card.path).append('\n');
                }
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                result.failed++;
                result.log.append("处理失败：").append(card.path)
                        .append("；").append(safeMessage(e)).append('\n');
            }
        }
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
