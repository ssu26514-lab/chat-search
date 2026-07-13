package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class SemanticDuplicateFeatureTest {
    private SemanticCardParser.CardRecord exactLeft;
    private SemanticCardParser.CardRecord exactRight;
    private SemanticCardParser.CardRecord changedRight;
    private JSONObject basePayload;
    private JSONObject packagingOnlyPayload;
    private JSONObject changedPayload;
    private SemanticCardScanner.Group exactGroup;
    private SemanticCardScanner.Group variantGroup;

    @Before
    public void prepare() throws Exception {
        ToolSession.release(ToolSession.Mode.SEMANTIC_DUPLICATE);
        SemanticDuplicateSession.clear();

        basePayload = buildPayload(false, "普通说明", "2026-01-01");
        packagingOnlyPayload = buildPayload(false,
                "带零宽水印\u200B\u200C但不影响聊天", "2026-07-01");
        changedPayload = buildPayload(true, "新版说明", "2026-07-02");

        exactLeft = seed("exact-left", "《测试角色》.png", 1_100_000,
                true, false, Arrays.asList("chara"));
        exactRight = seed("exact-right", "《测试角色》(2).png", 2_000_000,
                true, true, Arrays.asList("_build", "ccv3", "chara", "wm"));
        changedRight = seed("changed-right", "《测试角色》新版.png", 2_100_000,
                true, true, Arrays.asList("ccv3", "chara"));

        exactLeft = SemanticCardParser.fromJsonForTest(exactLeft, basePayload).record;
        exactRight = SemanticCardParser.fromJsonForTest(exactRight, packagingOnlyPayload).record;
        changedRight = SemanticCardParser.fromJsonForTest(changedRight, changedPayload).record;

        exactGroup = new SemanticCardScanner.Group(
                "exact:test", SemanticCardScanner.GroupType.EXACT_CONTENT,
                "《测试角色》", new ArrayList<>(Arrays.asList(exactLeft, exactRight)), true);
        variantGroup = new SemanticCardScanner.Group(
                "variant:test", SemanticCardScanner.GroupType.RELATED_VARIANTS,
                "《测试角色》", new ArrayList<>(Arrays.asList(exactLeft, changedRight)), false);

        Map<String, JSONObject> payloads = new HashMap<>();
        payloads.put(exactLeft.uri, basePayload);
        payloads.put(exactRight.uri, packagingOnlyPayload);
        payloads.put(changedRight.uri, changedPayload);
        SemanticDuplicateSession.setGroupsForTest(
                Arrays.asList(exactGroup, variantGroup), payloads);
    }

    @Test
    public void metadataAndPackagingDifferencesStillProduceSameFunctionalHash() throws Exception {
        assertEquals(exactLeft.functionalHash, exactRight.functionalHash);
        assertNotEquals(exactLeft.extensionsHash, "");
        assertNotEquals(exactLeft.chunkKeywords, exactRight.chunkKeywords);
        assertNotEquals(exactLeft.size, exactRight.size);
    }

    @Test
    public void realContentChangesProduceReadableComponentDifferences() throws Exception {
        assertNotEquals(exactLeft.functionalHash, changedRight.functionalHash);
        SemanticCardParser.ParsedPayload left =
                SemanticCardParser.fromJsonForTest(exactLeft, basePayload);
        SemanticCardParser.ParsedPayload right =
                SemanticCardParser.fromJsonForTest(changedRight, changedPayload);
        SemanticDiffEngine.DiffReport report = SemanticDiffEngine.compare(left, right);
        assertFalse(report.effectiveContentSame);
        assertTrue(report.greetings.contains("仅右侧有第 3 个"));
        assertTrue(report.worldbook.contains("仅右侧有"));
        assertTrue(report.regex.contains("仅右侧有"));
        assertTrue(report.extensions.contains("内容改变"));
    }

    @Test
    public void exactGroupAllowsExplicitKeeperSelection() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, SemanticGroupActivity.class);
        intent.putExtra("group_id", exactGroup.id);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<SemanticGroupActivity> ignored = ActivityScenario.launch(intent)) {
            // 默认推荐索引 1（同时包含 chara + ccv3）；点击另一项后必须切换到索引 0。
            assertEquals(1, SemanticDuplicateSession.keeperIndex(exactGroup.id));
            onView(withText("保留这个文件")).check(matches(isDisplayed())).perform(click());
            assertEquals(0, SemanticDuplicateSession.keeperIndex(exactGroup.id));
            onView(withText(containsString("当前保留："))).check(matches(isDisplayed()));
        }
    }

    @Test
    public void variantGroupShowsDifferencesButNoDeleteConfirmation() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, SemanticGroupActivity.class);
        intent.putExtra("group_id", variantGroup.id);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<SemanticGroupActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withText(containsString("只能查看差异，不提供一键删除")))
                    .check(matches(isDisplayed()));
            onView(withText("重新验证后删除其余语义重复副本"))
                    .check(doesNotExist());
        }
    }

    @Test
    public void mainPageSeparatesExactAndChangedGroups() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, SemanticDuplicateActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<SemanticDuplicateActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withText("内容相同（1）")).check(matches(isDisplayed()));
            onView(withText("有内容变化（1）")).check(matches(isDisplayed())).perform(click());
            onView(withText(containsString("同名但实际内容有变化")))
                    .check(matches(isDisplayed()));
        }
    }

    @Test
    public void exactPairReportExplainsPackagingOnlyDifference() throws Exception {
        SemanticCardParser.ParsedPayload left =
                SemanticCardParser.fromJsonForTest(exactLeft, basePayload);
        SemanticCardParser.ParsedPayload right =
                SemanticCardParser.fromJsonForTest(exactRight, packagingOnlyPayload);
        SemanticDiffEngine.DiffReport report = SemanticDiffEngine.compare(left, right);
        assertTrue(report.effectiveContentSame);
        assertTrue(report.verdict.contains("有效内容完全一致"));
        assertTrue(report.persona.contains("完全相同"));
        assertTrue(report.greetings.contains("完全相同"));
        assertTrue(report.worldbook.contains("完全相同"));
        assertTrue(report.regex.contains("完全相同"));
        assertTrue(report.packaging.contains("文件大小不同"));
        assertTrue(report.packaging.contains("PNG 数据块不同"));
    }

    @Test
    public void deletionEngineRejectsChangedVariantGroupBeforeTouchingFiles() throws Exception {
        SemanticDeleteManager manager = new SemanticDeleteManager(
                ApplicationProvider.getApplicationContext().getContentResolver(),
                new java.util.concurrent.atomic.AtomicBoolean(false), null);
        try {
            manager.deleteNonKeeper(variantGroup, exactLeft);
            fail("内容有变化的组必须拒绝删除");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("只有有效内容完全一致"));
        }
    }

    private static SemanticCardParser.CardRecord seed(String id, String fileName,
                                                       long size, boolean chara,
                                                       boolean ccv3,
                                                       List<String> chunks) {
        SemanticCardParser.CardRecord record = new SemanticCardParser.CardRecord();
        record.key = id;
        record.uri = "content://semantic-test/" + id + ".png";
        record.treeUri = "content://semantic-test/tree";
        record.parentDocumentId = "parent";
        record.path = "测试/" + fileName;
        record.fileName = fileName;
        record.characterName = "《测试角色》";
        record.size = size;
        record.modified = 1000L;
        record.width = 512;
        record.height = 768;
        record.hasChara = chara;
        record.hasCcv3 = ccv3;
        record.imageDataHash = id + "-image";
        record.chunkKeywords.addAll(chunks);
        return record;
    }

    private static JSONObject buildPayload(boolean changed, String creatorNotes,
                                           String createdAt) throws Exception {
        JSONObject root = new JSONObject();
        root.put("spec", "chara_card_v3");
        root.put("spec_version", "3.0");
        JSONObject data = new JSONObject();
        data.put("name", "《测试角色》");
        data.put("description", "完全相同的人设正文");
        data.put("personality", "安静、理性");
        data.put("scenario", "测试场景");
        data.put("first_mes", "第一条开场白");
        JSONArray greetings = new JSONArray();
        greetings.put("第二条开场白");
        if (changed) greetings.put("新版新增的第三条开场白");
        data.put("alternate_greetings", greetings);
        data.put("creator_notes", creatorNotes);
        data.put("creation_date", createdAt);
        data.put("tags", new JSONArray().put("测试"));

        JSONObject book = new JSONObject();
        JSONArray entries = new JSONArray();
        entries.put(new JSONObject()
                .put("name", "基础设定")
                .put("keys", new JSONArray().put("测试"))
                .put("content", "基础世界书内容"));
        if (changed) {
            entries.put(new JSONObject()
                    .put("name", "新版追加设定")
                    .put("keys", new JSONArray().put("新增"))
                    .put("content", "新版新增世界书内容"));
        }
        book.put("entries", entries);
        data.put("character_book", book);

        JSONObject extensions = new JSONObject();
        JSONArray regexScripts = new JSONArray();
        regexScripts.put(new JSONObject()
                .put("scriptName", "基础替换")
                .put("findRegex", "测试")
                .put("replaceString", "替换"));
        if (changed) {
            regexScripts.put(new JSONObject()
                    .put("scriptName", "新版手机界面")
                    .put("findRegex", "手机")
                    .put("replaceString", "<div>手机</div>"));
        }
        extensions.put("regex_scripts", regexScripts);
        extensions.put("tavern_helper", new JSONObject()
                .put("enabled", true)
                .put("version", changed ? 2 : 1));
        extensions.put("_build", createdAt);
        data.put("extensions", extensions);
        root.put("data", data);
        return root;
    }
}
