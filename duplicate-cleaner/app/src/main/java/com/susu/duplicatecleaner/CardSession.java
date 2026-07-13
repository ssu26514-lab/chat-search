package com.susu.duplicatecleaner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CardSession {
    private static final int MAX_LOADED_CARDS = 24;
    private static final LinkedHashMap<String, CharacterCard> CARDS =
            new LinkedHashMap<String, CharacterCard>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CharacterCard> eldest) {
                    return size() > MAX_LOADED_CARDS;
                }
            };
    private static String fullScreenTitle = "";
    private static String fullScreenText = "";

    private CardSession() {
    }

    static synchronized void replace(List<CharacterCard> cards) {
        CARDS.clear();
        for (CharacterCard card : cards) {
            if (card != null && card.persona != null) put(card);
        }
    }

    static synchronized void put(CharacterCard card) {
        if (card == null || card.key == null) return;
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
