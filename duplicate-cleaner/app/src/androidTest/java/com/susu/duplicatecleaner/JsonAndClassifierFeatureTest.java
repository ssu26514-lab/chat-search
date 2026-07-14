package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class JsonAndClassifierFeatureTest {

    @Before
    public void releaseSessions() {
        for (ToolSession.Mode mode : ToolSession.Mode.values()) {
            ToolSession.release(mode);
        }
        CardSession.clearCards();
    }

    @Test
    public void roleCardHeuristicsAcceptsJsonCardsButRejectsPresetAndTheme()
            throws Exception {
        JSONObject card = roleCardPayload();
        assertTrue(RoleCardHeuristics.inspect(card).roleCard);

        JSONObject preset = new JSONObject()
                .put("name", "Claude 预设")
                .put("temperature", 0.8)
                .put("top_p", 0.9)
                .put("chat_completion_source", "claude")
                .put("model", "claude")
                .put("prompts", new JSONArray())
                .put("prompt_order", new JSONArray());
        assertFalse(RoleCardHeuristics.inspect(preset).roleCard);
        assertTrue(RoleCardHeuristics.presetSignalCount(preset, preset) >= 4);

        JSONObject theme = new JSONObject()
                .put("theme_name", "蓝色主题")
                .put("main_text_color", "#ffffff")
                .put("blur_strength", 8)
                .put("font_scale", 1.0)
                .put("chat_width", 80)
                .put("custom_css", "body{}");
        assertFalse(RoleCardHeuristics.inspect(theme).roleCard);
        assertTrue(RoleCardHeuristics.themeSignalCount(theme) >= 4);
    }

    @Test
    public void pngAndJsonWithSamePayloadHaveSameSemanticFingerprint()
            throws Exception {
        JSONObject payload = roleCardPayload();
        SemanticCardParser.CardRecord png = seed("card.png", "png");
        png.hasChara = true;
        SemanticCardParser.CardRecord json = seed("card.json", "json");
        json.hasCcv3 = true;
        json.chunkKeywords.add("JSON 文件");

        SemanticCardParser.ParsedPayload pngParsed =
                SemanticCardParser.fromJsonForTest(png, payload);
        SemanticCardParser.ParsedPayload jsonParsed =
                SemanticCardParser.fromJsonForTest(json, payload);
        assertEquals(pngParsed.record.functionalHash,
                jsonParsed.record.functionalHash);
        assertEquals(pngParsed.record.greetingsHash,
                jsonParsed.record.greetingsHash);
    }

    @Test
    public void jsonDetailShowsContentWithoutImageFullscreen() {
        CharacterCard card = new CharacterCard();
        card.key = "json-detail";
        card.uri = "content://json-test/card.json";
        card.treeUri = "content://json-test/tree";
        card.parentDocumentId = "parent";
        card.path = "角色卡/JSON角色.json";
        card.fileName = "JSON角色.json";
        card.fileFormat = "JSON";
        card.characterName = "JSON角色";
        card.persona = "【角色描述】\n这是 JSON 角色卡人设。";
        card.greetings.add("JSON 第一条开场白");
        CardSession.put(card);

        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, CardDetailActivity.class);
        intent.putExtra("card_key", card.key);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<CardDetailActivity> ignored =
                     ActivityScenario.launch(intent)) {
            onView(withText(containsString("JSON 角色卡没有内嵌封面")))
                    .check(matches(isDisplayed()));
            onView(withText("图片全屏")).check(doesNotExist());
            onView(withText("开场白 1 / 1")).check(matches(isDisplayed()));
            onView(withText(containsString("这是 JSON 角色卡人设")))
                    .check(matches(isDisplayed()));
        }
    }

    @Test
    public void nonCardJsonCanMoveButCannotDeleteInCleanupPage() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, PlainPngCleanupActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<PlainPngCleanupActivity> ignored =
                     ActivityScenario.launch(intent)) {
            onView(withText("非角色JSON（30）")).check(matches(isDisplayed()))
                    .perform(click());
            onView(withText(containsString("当前：非角色卡 JSON")))
                    .check(matches(isDisplayed()));
            onView(withText("移动已选择文件")).check(matches(isDisplayed()));
            onView(withText("永久删除已选择普通图片"))
                    .check(matches(withEffectiveVisibility(
                            ViewMatchers.Visibility.GONE)));
        }
    }

    @Test
    public void classifierPageContainsAllExpectedCategoriesAndNoDeleteAction() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, TavernFileClassifierActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<TavernFileClassifierActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                try {
                    Field field = TavernFileClassifierActivity.class
                            .getDeclaredField("allItems");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<TavernFileItem> items =
                            (List<TavernFileItem>) field.get(activity);
                    Set<TavernFileItem.Category> categories = new HashSet<>();
                    for (TavernFileItem item : items) categories.add(item.category);
                    assertTrue(categories.contains(TavernFileItem.Category.CHARACTER_CARD));
                    assertTrue(categories.contains(TavernFileItem.Category.PRESET));
                    assertTrue(categories.contains(TavernFileItem.Category.BEAUTY));
                    assertTrue(categories.contains(TavernFileItem.Category.WORLD_BOOK));
                    assertTrue(categories.contains(TavernFileItem.Category.REGEX_SCRIPT));
                    assertTrue(categories.contains(TavernFileItem.Category.IMAGE_ASSET));
                    assertTrue(categories.contains(TavernFileItem.Category.MIXED_PACKAGE));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            onView(withText("角色卡 · PNG 角色卡"))
                    .check(matches(isDisplayed()));
            onView(withText("安全移动已选择文件"))
                    .check(matches(isDisplayed()));
            onView(withText(containsString("永久删除"))).check(doesNotExist());
        }
    }

    @Test
    public void moveManagerPreservesMimeForJsonAndOtherClassifiedFiles() {
        assertEquals("application/json", CardMoveManager.mimeForName("角色卡.json"));
        assertEquals("image/png", CardMoveManager.mimeForName("角色卡.png"));
        assertEquals("text/css", CardMoveManager.mimeForName("主题.css"));
        assertEquals("application/zip", CardMoveManager.mimeForName("整合包.zip"));
    }

    private static JSONObject roleCardPayload() throws Exception {
        JSONObject root = new JSONObject();
        root.put("spec", "chara_card_v3");
        root.put("spec_version", "3.0");
        JSONObject data = new JSONObject();
        data.put("name", "测试角色");
        data.put("description", "完整角色描述");
        data.put("personality", "安静");
        data.put("scenario", "测试场景");
        data.put("first_mes", "第一条开场白");
        data.put("alternate_greetings", new JSONArray().put("第二条开场白"));
        data.put("character_book", new JSONObject().put("entries", new JSONArray()));
        data.put("extensions", new JSONObject());
        root.put("data", data);
        return root;
    }

    private static SemanticCardParser.CardRecord seed(String fileName, String id) {
        SemanticCardParser.CardRecord record = new SemanticCardParser.CardRecord();
        record.key = id;
        record.uri = "content://json-semantic-test/" + fileName;
        record.treeUri = "content://json-semantic-test/tree";
        record.parentDocumentId = "parent";
        record.path = "测试/" + fileName;
        record.fileName = fileName;
        record.characterName = "测试角色";
        record.size = 1000L;
        record.modified = 1L;
        return record;
    }
}
