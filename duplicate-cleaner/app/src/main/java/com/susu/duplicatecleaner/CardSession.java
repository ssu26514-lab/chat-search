package com.susu.duplicatecleaner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CardSession {
    private static final Map<String, CharacterCard> CARDS = new LinkedHashMap<>();
    private static String fullScreenTitle = "";
    private static String fullScreenText = "";

    private CardSession() {
    }

    static synchronized void replace(List<CharacterCard> cards) {
        CARDS.clear();
        for (CharacterCard card : cards) CARDS.put(card.key, card);
    }

    static synchronized void put(CharacterCard card) {
        CARDS.put(card.key, card);
    }

    static synchronized CharacterCard get(String key) {
        return CARDS.get(key);
    }

    static synchronized void clearCards() {
        CARDS.clear();
    }

    static synchronized void setFullText(String title, String text) {
        fullScreenTitle = title == null ? "" : title;
        fullScreenText = text == null ? "" : text;
    }

    static synchronized String fullTitle() {
        return fullScreenTitle;
    }

    static synchronized String fullText() {
        return fullScreenText;
    }
}
