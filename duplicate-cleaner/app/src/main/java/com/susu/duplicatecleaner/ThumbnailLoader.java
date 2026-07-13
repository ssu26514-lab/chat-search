package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ThumbnailLoader {
    private final ContentResolver resolver;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache;

    ThumbnailLoader(ContentResolver resolver) {
        this.resolver = resolver;
        int maxKb = (int) Math.min(24 * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 8L);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(ImageView view, Uri uri, int targetPx) {
        String key = uri.toString();
        view.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        executor.execute(() -> {
            Bitmap bitmap = decodeSampled(uri, targetPx, targetPx);
            if (bitmap != null) cache.put(key, bitmap);
            view.post(() -> {
                Object tag = view.getTag();
                if (bitmap != null && key.equals(tag)) view.setImageBitmap(bitmap);
            });
        });
    }

    void shutdown() {
        executor.shutdownNow();
        cache.evictAll();
    }

    private Bitmap decodeSampled(Uri uri, int width, int height) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
