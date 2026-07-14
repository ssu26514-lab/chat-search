package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class PlainPngDeleteManager {
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

    PlainPngDeleteManager(ContentResolver resolver, AtomicBoolean cancelled,
                          ProgressListener listener) {
        this.resolver = resolver;
        this.cancelled = cancelled;
        this.listener = listener;
    }

    DeleteResult deletePlainImages(List<PlainPngItem> items) throws Exception {
        DeleteResult result = new DeleteResult();
        PngContentInspector inspector = new PngContentInspector(
                resolver, cancelled, null);

        for (int i = 0; i < items.size(); i++) {
            checkCancelled();
            PlainPngItem item = items.get(i);
            progress("正在重新确认并删除：" + (i + 1) + " / " + items.size()
                    + "\n" + item.path);

            if (item.type != PlainPngItem.Type.PLAIN_IMAGE) {
                result.skipped++;
                result.log.append("安全跳过（不是普通图片分类）：")
                        .append(item.path).append('\n');
                continue;
            }

            try {
                PngContentInspector.Inspection current = inspector.inspect(item.contentUri());
                if (current.type != PngContentInspector.InspectionType.PLAIN_IMAGE) {
                    result.skipped++;
                    result.log.append("安全跳过（重新检查后不再是普通图片）：")
                            .append(item.path).append('\n');
                    continue;
                }
                boolean deleted = DocumentsContract.deleteDocument(
                        resolver, Uri.parse(item.uri));
                if (deleted) {
                    result.deleted++;
                    result.deletedUris.add(item.uri);
                    result.log.append("已删除普通图片：")
                            .append(item.path).append('\n');
                } else {
                    result.failed++;
                    result.log.append("删除失败（系统未授权或文件被占用）：")
                            .append(item.path).append('\n');
                }
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                result.failed++;
                result.log.append("处理失败：").append(item.path)
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
