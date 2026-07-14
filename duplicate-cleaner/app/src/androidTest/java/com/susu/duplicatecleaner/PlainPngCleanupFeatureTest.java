package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class PlainPngCleanupFeatureTest {

    @Before
    public void releaseSession() {
        ToolSession.release(ToolSession.Mode.PLAIN_PNG_CLEANUP);
    }

    @Test
    public void plainPngWithoutRoleCardChunksIsClassifiedAsPlainImage() throws Exception {
        PngContentInspector.Inspection result = PngContentInspector.inspectBytes(
                pngWithOptionalText(null, null));
        assertEquals(PngContentInspector.InspectionType.PLAIN_IMAGE, result.type);
        assertEquals(512, result.width);
        assertEquals(768, result.height);
        assertTrue(result.reason.contains("没有 chara / ccv3"));
    }

    @Test
    public void malformedCharaChunkIsClassifiedAsDamaged() throws Exception {
        PngContentInspector.Inspection result = PngContentInspector.inspectBytes(
                pngWithOptionalText("chara", "not-valid-base64-or-json"));
        assertEquals(PngContentInspector.InspectionType.DAMAGED_OR_UNREADABLE,
                result.type);
        assertTrue(result.markerSummary.contains("chara"));
        assertTrue(result.reason.contains("解析失败"));
    }

    @Test
    public void validCharaChunkIsExcludedAsNormalCard() throws Exception {
        JSONObject root = new JSONObject();
        root.put("name", "正常角色卡");
        root.put("first_mes", "你好");
        String encoded = Base64.getEncoder().encodeToString(
                root.toString().getBytes(StandardCharsets.UTF_8));
        PngContentInspector.Inspection result = PngContentInspector.inspectBytes(
                pngWithOptionalText("chara", encoded));
        assertEquals(PngContentInspector.InspectionType.VALID_CARD, result.type);
    }

    @Test
    public void damagedTabKeepsMoveButHidesDelete() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, PlainPngCleanupActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<PlainPngCleanupActivity> ignored =
                     ActivityScenario.launch(intent)) {
            onView(withText("普通图片（120）")).check(matches(isDisplayed()));
            onView(withText("永久删除已选择普通图片"))
                    .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)));

            onView(withText("疑似损坏（40）")).perform(click());
            onView(withText(containsString("只能移动到待修复文件夹")))
                    .check(matches(isDisplayed()));
            onView(withText("移动已选择文件")).check(matches(isDisplayed()));
            onView(withText("永久删除已选择普通图片"))
                    .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));

            onView(withText("全选当前分类")).perform(click());
            onView(withText(containsString("已选择 40 个")))
                    .check(matches(isDisplayed()));
        }
    }

    @Test
    public void deleteManagerSkipsDamagedItemWithoutDeleting() throws Exception {
        PlainPngItem damaged = new PlainPngItem();
        damaged.uri = "content://plain-png-test/damaged.png";
        damaged.path = "待修复/损坏角色卡.png";
        damaged.type = PlainPngItem.Type.DAMAGED_OR_UNREADABLE;

        PlainPngDeleteManager manager = new PlainPngDeleteManager(
                ApplicationProvider.getApplicationContext().getContentResolver(),
                new AtomicBoolean(false), null);
        PlainPngDeleteManager.DeleteResult result = manager.deletePlainImages(
                Collections.singletonList(damaged));
        assertEquals(0, result.deleted);
        assertEquals(1, result.skipped);
        assertTrue(result.log.toString().contains("不是普通图片分类"));
    }

    private static byte[] pngWithOptionalText(String keyword, String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A});

        ByteArrayOutputStream ihdrBytes = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(ihdrBytes);
        ihdr.writeInt(512);
        ihdr.writeInt(768);
        ihdr.writeByte(8);
        ihdr.writeByte(6);
        ihdr.writeByte(0);
        ihdr.writeByte(0);
        ihdr.writeByte(0);
        writeChunk(data, "IHDR", ihdrBytes.toByteArray());

        if (keyword != null) {
            ByteArrayOutputStream textBytes = new ByteArrayOutputStream();
            textBytes.write(keyword.getBytes(StandardCharsets.ISO_8859_1));
            textBytes.write(0);
            textBytes.write(text.getBytes(StandardCharsets.ISO_8859_1));
            writeChunk(data, "tEXt", textBytes.toByteArray());
        }

        writeChunk(data, "IEND", new byte[0]);
        data.flush();
        return output.toByteArray();
    }

    private static void writeChunk(DataOutputStream output, String type,
                                   byte[] content) throws Exception {
        output.writeInt(content.length);
        output.write(type.getBytes(StandardCharsets.ISO_8859_1));
        output.write(content);
        output.writeInt(0); // inspector does not rely on CRC during classification
    }
}
