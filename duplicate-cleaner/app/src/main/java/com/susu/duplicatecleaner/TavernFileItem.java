package com.susu.duplicatecleaner;

import android.net.Uri;

import java.util.Locale;

final class TavernFileItem {
    enum Category {
        CHARACTER_CARD("角色卡"),
        PRESET("预设"),
        BEAUTY("美化 / 主题"),
        WORLD_BOOK("世界书"),
        REGEX_SCRIPT("正则脚本"),
        EXTENSION_PLUGIN("插件 / 扩展"),
        IMAGE_ASSET("普通图片 / 素材"),
        MIXED_PACKAGE("混合包 / 压缩包"),
        UNKNOWN("无法确定"),
        DAMAGED("损坏 / 无法读取");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    String key;
    String treeUri;
    String uri;
    String parentDocumentId;
    String path;
    String fileName;
    long size;
    long modified;
    Category category;
    String subtype;
    String reason;
    String details;
    int confidence;

    Uri contentUri() {
        return Uri.parse(uri);
    }

    boolean isImageFile() {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    CharacterCard asMovableFile() {
        CharacterCard card = new CharacterCard();
        card.key = key;
        card.treeUri = treeUri;
        card.uri = uri;
        card.parentDocumentId = parentDocumentId;
        card.path = path;
        card.fileName = fileName;
        card.characterName = fileName;
        card.size = size;
        card.modified = modified;
        return card;
    }
}
