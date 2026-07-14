package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class JsonDocumentReader {
    private static final int MAX_JSON_BYTES = 128 * 1024 * 1024;

    private JsonDocumentReader() {
    }

    static Object readAny(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("无法打开 JSON 文件");
            return parse(readText(input));
        }
    }

    static JSONObject readObject(ContentResolver resolver, Uri uri) throws Exception {
        Object value = readAny(resolver, uri);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException("JSON 顶层不是对象结构");
        }
        return (JSONObject) value;
    }

    static Object parse(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("JSON 内容为空");
        String cleaned = stripBom(text).trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("JSON 内容为空");
        Object value = new JSONTokener(cleaned).nextValue();
        if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
            throw new IllegalArgumentException("JSON 顶层必须是对象或数组");
        }
        return value;
    }

    static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("JSON 文件超过 128 MB，已停止读取");
            }
            output.write(buffer, 0, read);
        }
        return stripBom(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\ufeff') {
            return value.substring(1);
        }
        return value;
    }
}
