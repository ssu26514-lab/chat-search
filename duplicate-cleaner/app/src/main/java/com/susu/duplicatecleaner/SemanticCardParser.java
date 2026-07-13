package com.susu.duplicatecleaner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.InflaterInputStream;

final class SemanticCardParser {
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int MAX_TEXT_CHUNK = 96 * 1024 * 1024;

    private static final Set<String> TOP_LEVEL_IGNORED = new HashSet<>();
    private static final Set<String> EXTENSION_METADATA_IGNORED = new HashSet<>();

    static {
        Collections.addAll(TOP_LEVEL_IGNORED,
                "creator_notes", "creator", "character_version", "tags",
                "creation_date", "modification_date", "created_at", "updated_at",
                "last_modified", "source", "source_url", "avatar", "portrait",
                "thumbnail", "image", "spec", "spec_version");
        Collections.addAll(EXTENSION_METADATA_IGNORED,
                "_build", "wm", "watermark", "created_at", "updated_at",
                "source", "source_url", "chub", "fav", "favorite");
    }

    static final class CardRecord {
        String key;
        String treeUri;
        String uri;
        String parentDocumentId;
        String path;
        String fileName;
        String characterName;
        long size;
        long modified;
        int width;
        int height;
        boolean hasChara;
        boolean hasCcv3;
        boolean payloadConflict;
        String spec;
        String specVersion;
        String functionalHash;
        String personaHash;
        String greetingsHash;
        String worldbookHash;
        String extensionsHash;
        String otherHash;
        String imageDataHash;
        int greetingCount;
        int worldbookCount;
        int regexCount;
        int extensionKeyCount;
        final List<String> chunkKeywords = new ArrayList<>();

        Uri contentUri() {
            return Uri.parse(uri);
        }

        String normalizedName() {
            return normalizeName(characterName);
        }

        String compatibilityText() {
            String format;
            if (hasChara && hasCcv3) format = "chara + ccv3";
            else if (hasCcv3) format = "ccv3";
            else if (hasChara) format = "chara";
            else format = "未识别";
            if (payloadConflict) format += "（内部两份数据冲突）";
            if (specVersion != null && !specVersion.isEmpty()) format += " · v" + specVersion;
            return format;
        }

        String componentSummary() {
            return "开场白 " + greetingCount + " · 世界书 " + worldbookCount
                    + " · 正则 " + regexCount + " · 扩展键 " + extensionKeyCount;
        }
    }

    static final class ParsedPayload {
        final CardRecord record;
        final JSONObject root;
        final JSONObject data;
        final JSONObject cleanedData;
        final JSONObject persona;
        final JSONObject greetings;
        final Object worldbook;
        final Object extensions;
        final JSONObject other;

        ParsedPayload(CardRecord record, JSONObject root, JSONObject data,
                      JSONObject cleanedData, JSONObject persona, JSONObject greetings,
                      Object worldbook, Object extensions, JSONObject other) {
            this.record = record;
            this.root = root;
            this.data = data;
            this.cleanedData = cleanedData;
            this.persona = persona;
            this.greetings = greetings;
            this.worldbook = worldbook;
            this.extensions = extensions;
            this.other = other;
        }
    }

    static final class CoverComparison {
        final String status;
        final boolean exactPixels;

        CoverComparison(String status, boolean exactPixels) {
            this.status = status;
            this.exactPixels = exactPixels;
        }
    }

    private static final class PngPayload {
        JSONObject chara;
        JSONObject ccv3;
        int width;
        int height;
        String imageDataHash;
        final List<String> keywords = new ArrayList<>();
    }

    private SemanticCardParser() {
    }

    static ParsedPayload parse(ContentResolver resolver, CardRecord seed) throws Exception {
        PngPayload png = readPng(resolver, Uri.parse(seed.uri));
        JSONObject preferred = png.ccv3 != null ? png.ccv3 : png.chara;
        if (preferred == null) {
            throw new IllegalArgumentException("PNG 内没有识别到 chara / ccv3 角色卡数据");
        }

        CardRecord record = copySeed(seed);
        record.width = png.width;
        record.height = png.height;
        record.hasChara = png.chara != null;
        record.hasCcv3 = png.ccv3 != null;
        record.imageDataHash = png.imageDataHash;
        record.chunkKeywords.clear();
        record.chunkKeywords.addAll(png.keywords);

        ParsedPayload parsed = buildParsed(record, preferred);
        if (png.chara != null && png.ccv3 != null) {
            ParsedPayload charaParsed = buildParsed(copySeed(record), png.chara);
            ParsedPayload ccv3Parsed = buildParsed(copySeed(record), png.ccv3);
            record.payloadConflict = !charaParsed.record.functionalHash
                    .equals(ccv3Parsed.record.functionalHash);
            parsed.record.payloadConflict = record.payloadConflict;
        }
        return parsed;
    }

    static ParsedPayload fromJsonForTest(CardRecord seed, JSONObject root) throws Exception {
        CardRecord record = copySeed(seed);
        return buildParsed(record, root);
    }

    private static ParsedPayload buildParsed(CardRecord record, JSONObject root) throws Exception {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;

        record.spec = cleanString(root.optString("spec", ""));
        record.specVersion = cleanString(root.optString("spec_version", ""));
        String name = cleanString(data.optString("name", root.optString("name", "")));
        if (!name.isEmpty()) record.characterName = name;
        if (record.characterName == null || record.characterName.trim().isEmpty()) {
            record.characterName = stripExtension(record.fileName);
        }

        JSONObject cleaned = cleanDataObject(data);
        JSONObject persona = selectPersona(cleaned);
        JSONObject greetings = selectGreetings(cleaned);
        Object worldbook = cleaned.has("character_book")
                ? cleaned.opt("character_book") : JSONObject.NULL;
        Object extensions = cleaned.has("extensions")
                ? cleaned.opt("extensions") : JSONObject.NULL;
        JSONObject other = selectOther(cleaned);

        record.functionalHash = hashCanonical(cleaned);
        record.personaHash = hashCanonical(persona);
        record.greetingsHash = hashCanonical(greetings);
        record.worldbookHash = hashCanonical(worldbook);
        record.extensionsHash = hashCanonical(extensions);
        record.otherHash = hashCanonical(other);
        record.greetingCount = greetingCount(greetings);
        record.worldbookCount = worldbookCount(worldbook);
        record.regexCount = collectRegexScripts(extensions).size();
        record.extensionKeyCount = extensions instanceof JSONObject
                ? ((JSONObject) extensions).length() : 0;

        return new ParsedPayload(record, root, data, cleaned, persona, greetings,
                worldbook, extensions, other);
    }

    private static PngPayload readPng(ContentResolver resolver, Uri uri) throws Exception {
        PngPayload payload = new PngPayload();
        MessageDigest imageDigest = MessageDigest.getInstance("SHA-256");
        boolean sawImageData = false;

        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法打开 PNG 文件");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(raw, 256 * 1024))) {
                byte[] signature = new byte[8];
                input.readFully(signature);
                for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                    if (signature[i] != PNG_SIGNATURE[i]) {
                        throw new IllegalArgumentException("不是有效 PNG 文件");
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
                        byte[] bytes = readExact(input, (int) length);
                        if (bytes.length >= 8) {
                            payload.width = readInt(bytes, 0);
                            payload.height = readInt(bytes, 4);
                        }
                    } else if ("IDAT".equals(type)) {
                        digestStream(input, length, imageDigest);
                        sawImageData = true;
                    } else if (("tEXt".equals(type) || "iTXt".equals(type)
                            || "zTXt".equals(type)) && length <= MAX_TEXT_CHUNK) {
                        byte[] bytes = readExact(input, (int) length);
                        TextEntry entry = parseTextEntry(type, bytes);
                        if (entry != null) {
                            payload.keywords.add(entry.keyword);
                            if ("chara".equals(entry.keyword) || "ccv3".equals(entry.keyword)) {
                                JSONObject json = decodeCharacterJson(entry.text);
                                if ("chara".equals(entry.keyword)) payload.chara = json;
                                else payload.ccv3 = json;
                            }
                        }
                    } else {
                        skipFully(input, length);
                    }
                    skipFully(input, 4L); // CRC
                    if ("IEND".equals(type)) break;
                }
            }
        }

        Collections.sort(payload.keywords);
        payload.imageDataHash = sawImageData ? toHex(imageDigest.digest()) : "";
        return payload;
    }

    private static final class TextEntry {
        final String keyword;
        final String text;

        TextEntry(String keyword, String text) {
            this.keyword = keyword;
            this.text = text;
        }
    }

    private static TextEntry parseTextEntry(String type, byte[] bytes) throws Exception {
        int firstZero = indexOfZero(bytes, 0);
        if (firstZero <= 0) return null;
        String keyword = new String(bytes, 0, firstZero, StandardCharsets.ISO_8859_1);

        if ("tEXt".equals(type)) {
            String text = new String(bytes, firstZero + 1,
                    bytes.length - firstZero - 1, StandardCharsets.ISO_8859_1);
            return new TextEntry(keyword, text);
        }

        if ("zTXt".equals(type)) {
            int compressedStart = firstZero + 2;
            if (compressedStart > bytes.length) return null;
            byte[] inflated = inflate(bytes, compressedStart, bytes.length - compressedStart);
            return new TextEntry(keyword, new String(inflated, StandardCharsets.ISO_8859_1));
        }

        int position = firstZero + 1;
        if (position + 2 > bytes.length) return null;
        int compressionFlag = bytes[position] & 0xff;
        position += 2; // compression flag + method
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
        return new TextEntry(keyword, new String(textBytes, StandardCharsets.UTF_8));
    }

    private static JSONObject decodeCharacterJson(String encoded) throws Exception {
        String value = encoded == null ? "" : encoded.trim();
        try {
            byte[] decoded = Base64.decode(value, Base64.DEFAULT);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject(value);
        }
    }

    private static JSONObject cleanDataObject(JSONObject data) throws Exception {
        JSONObject result = new JSONObject();
        List<String> keys = sortedKeys(data);
        for (String key : keys) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (TOP_LEVEL_IGNORED.contains(lower)) continue;
            Object value = data.opt(key);
            if ("extensions".equals(lower) && value instanceof JSONObject) {
                result.put(key, cleanExtensions((JSONObject) value));
            } else {
                result.put(key, cleanValue(value, false));
            }
        }
        return result;
    }

    private static JSONObject cleanExtensions(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : sortedKeys(source)) {
            if (EXTENSION_METADATA_IGNORED.contains(key.toLowerCase(Locale.ROOT))) continue;
            result.put(key, cleanValue(source.opt(key), true));
        }
        return result;
    }

    private static Object cleanValue(Object value, boolean insideExtensions) throws Exception {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject result = new JSONObject();
            for (String key : sortedKeys(source)) {
                if (insideExtensions
                        && EXTENSION_METADATA_IGNORED.contains(key.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                result.put(key, cleanValue(source.opt(key), insideExtensions));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray result = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                result.put(cleanValue(source.opt(i), insideExtensions));
            }
            return result;
        }
        if (value instanceof String) return cleanString((String) value);
        return value;
    }

    private static JSONObject selectPersona(JSONObject cleaned) throws Exception {
        JSONObject result = new JSONObject();
        String[] keys = new String[]{
                "name", "nickname", "description", "personality", "scenario",
                "mes_example", "system_prompt", "post_history_instructions",
                "group_only_greetings"
        };
        for (String key : keys) {
            if (cleaned.has(key)) result.put(key, cleaned.opt(key));
        }
        return result;
    }

    private static JSONObject selectGreetings(JSONObject cleaned) throws Exception {
        JSONObject result = new JSONObject();
        result.put("first_mes", cleaned.opt("first_mes"));
        Object alternates = cleaned.opt("alternate_greetings");
        result.put("alternate_greetings",
                alternates instanceof JSONArray ? alternates : new JSONArray());
        return result;
    }

    private static JSONObject selectOther(JSONObject cleaned) throws Exception {
        Set<String> excluded = new HashSet<>();
        Collections.addAll(excluded,
                "name", "nickname", "description", "personality", "scenario",
                "mes_example", "system_prompt", "post_history_instructions",
                "group_only_greetings", "first_mes", "alternate_greetings",
                "character_book", "extensions");
        JSONObject result = new JSONObject();
        for (String key : sortedKeys(cleaned)) {
            if (!excluded.contains(key)) result.put(key, cleaned.opt(key));
        }
        return result;
    }

    static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            StringBuilder builder = new StringBuilder("{");
            List<String> keys = sortedKeys(object);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) builder.append(',');
                String key = keys.get(i);
                builder.append(JSONObject.quote(key)).append(':')
                        .append(canonical(object.opt(key)));
            }
            return builder.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) builder.append(',');
                builder.append(canonical(array.opt(i)));
            }
            return builder.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(cleanString(String.valueOf(value)));
    }

    static String hashCanonical(Object value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
    }

    static List<String> greetingTexts(ParsedPayload payload) {
        List<String> result = new ArrayList<>();
        String first = cleanString(payload.greetings.optString("first_mes", ""));
        if (!first.isEmpty()) result.add(first);
        JSONArray array = payload.greetings.optJSONArray("alternate_greetings");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = cleanString(array.optString(i, ""));
                if (!value.isEmpty()) result.add(value);
            }
        }
        return result;
    }

    static Map<String, String> worldbookEntries(ParsedPayload payload) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(payload.worldbook instanceof JSONObject)) return result;
        JSONArray entries = ((JSONObject) payload.worldbook).optJSONArray("entries");
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            Object value = entries.opt(i);
            if (!(value instanceof JSONObject)) continue;
            JSONObject object = (JSONObject) value;
            String label = firstNonEmpty(
                    object.optString("name", ""),
                    object.optString("comment", ""),
                    object.optString("id", ""),
                    object.optString("uid", ""));
            if (label.isEmpty()) label = "条目 " + (i + 1);
            String unique = label;
            int suffix = 2;
            while (result.containsKey(unique)) unique = label + " #" + suffix++;
            result.put(unique, canonical(object));
        }
        return result;
    }

    static Map<String, String> collectRegexScripts(Object extensions) {
        Map<String, String> result = new LinkedHashMap<>();
        collectRegexRecursive(extensions, "扩展", result);
        return result;
    }

    private static void collectRegexRecursive(Object value, String path,
                                              Map<String, String> result) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            boolean looksLikeRegex = false;
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next().toLowerCase(Locale.ROOT);
                if (key.contains("regex") || key.contains("pattern")
                        || key.contains("findregex") || key.contains("replacestring")) {
                    looksLikeRegex = true;
                }
            }
            if (looksLikeRegex) {
                String label = firstNonEmpty(
                        object.optString("scriptName", ""),
                        object.optString("name", ""),
                        object.optString("id", ""), path);
                String unique = label;
                int suffix = 2;
                while (result.containsKey(unique)) unique = label + " #" + suffix++;
                result.put(unique, canonical(object));
                return;
            }
            for (String key : sortedKeys(object)) {
                collectRegexRecursive(object.opt(key), path + "/" + key, result);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectRegexRecursive(array.opt(i), path + "[" + i + "]", result);
            }
        }
    }

    static CoverComparison compareCoverPixels(ContentResolver resolver, Uri left, Uri right) {
        Bitmap a = null;
        Bitmap b = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = resolver.openInputStream(left)) {
                if (in != null) a = BitmapFactory.decodeStream(in, null, options);
            }
            try (InputStream in = resolver.openInputStream(right)) {
                if (in != null) b = BitmapFactory.decodeStream(in, null, options);
            }
            if (a == null || b == null) return new CoverComparison("封面无法完整解码", false);
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                return new CoverComparison("封面尺寸不同：" + a.getWidth() + "×" + a.getHeight()
                        + " vs " + b.getWidth() + "×" + b.getHeight(), false);
            }
            int width = a.getWidth();
            int height = a.getHeight();
            int[] rowA = new int[width];
            int[] rowB = new int[width];
            long different = 0;
            long totalDelta = 0;
            int maxDelta = 0;
            for (int y = 0; y < height; y++) {
                a.getPixels(rowA, 0, width, 0, y, width, 1);
                b.getPixels(rowB, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int pa = rowA[x];
                    int pb = rowB[x];
                    if (pa == pb) continue;
                    different++;
                    int delta = channelDelta(pa, pb);
                    totalDelta += delta;
                    if (delta > maxDelta) maxDelta = delta;
                }
            }
            if (different == 0) {
                return new CoverComparison("封面逐像素完全相同", true);
            }
            double ratio = different * 100.0 / ((long) width * height);
            double average = different == 0 ? 0 : totalDelta / (double) different;
            return new CoverComparison(String.format(Locale.CHINA,
                    "封面存在像素差异：%.2f%% 像素不同，差异像素平均色差 %.2f，最大色差 %d",
                    ratio, average, maxDelta), false);
        } catch (OutOfMemoryError error) {
            return new CoverComparison("封面过大，未做逐像素比较；可根据封面数据指纹判断是否重新编码", false);
        } catch (Exception e) {
            return new CoverComparison("封面比较失败：" + safeMessage(e), false);
        } finally {
            if (a != null) a.recycle();
            if (b != null) b.recycle();
        }
    }

    private static int channelDelta(int a, int b) {
        int da = Math.abs(((a >>> 24) & 0xff) - ((b >>> 24) & 0xff));
        int dr = Math.abs(((a >>> 16) & 0xff) - ((b >>> 16) & 0xff));
        int dg = Math.abs(((a >>> 8) & 0xff) - ((b >>> 8) & 0xff));
        int db = Math.abs((a & 0xff) - (b & 0xff));
        return Math.max(Math.max(da, dr), Math.max(dg, db));
    }

    private static int greetingCount(JSONObject greetings) {
        int count = cleanString(greetings.optString("first_mes", "")).isEmpty() ? 0 : 1;
        JSONArray alternates = greetings.optJSONArray("alternate_greetings");
        if (alternates != null) {
            for (int i = 0; i < alternates.length(); i++) {
                if (!cleanString(alternates.optString(i, "")).isEmpty()) count++;
            }
        }
        return count;
    }

    private static int worldbookCount(Object worldbook) {
        if (!(worldbook instanceof JSONObject)) return 0;
        JSONArray entries = ((JSONObject) worldbook).optJSONArray("entries");
        return entries == null ? 0 : entries.length();
    }

    private static CardRecord copySeed(CardRecord source) {
        CardRecord result = new CardRecord();
        result.key = source.key;
        result.treeUri = source.treeUri;
        result.uri = source.uri;
        result.parentDocumentId = source.parentDocumentId;
        result.path = source.path;
        result.fileName = source.fileName;
        result.characterName = source.characterName;
        result.size = source.size;
        result.modified = source.modified;
        result.width = source.width;
        result.height = source.height;
        result.hasChara = source.hasChara;
        result.hasCcv3 = source.hasCcv3;
        result.payloadConflict = source.payloadConflict;
        result.spec = source.spec;
        result.specVersion = source.specVersion;
        result.imageDataHash = source.imageDataHash;
        result.chunkKeywords.addAll(source.chunkKeywords);
        return result;
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        return keys;
    }

    private static String cleanString(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]", "");
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) builder.append('\n');
            builder.append(lines[i].replaceFirst("[ \\t]+$", ""));
        }
        return builder.toString().trim();
    }

    private static String normalizeName(String value) {
        return cleanString(value).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String name) {
        if (name == null) return "未命名";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String cleaned = cleanString(value);
            if (!cleaned.isEmpty()) return cleaned;
        }
        return "";
    }

    private static int indexOfZero(byte[] bytes, int start) {
        for (int i = Math.max(0, start); i < bytes.length; i++) {
            if (bytes[i] == 0) return i;
        }
        return -1;
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

    private static void digestStream(InputStream input, long length,
                                     MessageDigest digest) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IllegalArgumentException("PNG 文件数据不完整");
            digest.update(buffer, 0, read);
            remaining -= read;
        }
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

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
