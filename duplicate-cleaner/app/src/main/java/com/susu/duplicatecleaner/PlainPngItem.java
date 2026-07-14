package com.susu.duplicatecleaner;

import android.net.Uri;

final class PlainPngItem {
    enum Type {
        PLAIN_IMAGE,
        NON_CARD_JSON,
        DAMAGED_OR_UNREADABLE
    }

    String key;
    String treeUri;
    String uri;
    String parentDocumentId;
    String path;
    String fileName;
    long size;
    long modified;
    int width;
    int height;
    Type type;
    String reason;
    String markerSummary;

    Uri contentUri() {
        return Uri.parse(uri);
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
        card.fileFormat = fileName != null && fileName.toLowerCase().endsWith(".json")
                ? "JSON" : "PNG";
        return card;
    }
}
