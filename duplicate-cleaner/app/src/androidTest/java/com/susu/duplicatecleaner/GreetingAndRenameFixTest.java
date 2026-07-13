package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.BaseAdapter;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class GreetingAndRenameFixTest {

    @Test
    public void listShowsUnknownBeforeReadAndRealCountAfterRead() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, GuardedCardBrowserActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<GuardedCardBrowserActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                try {
                    Field scannedField = CardBrowserV2Activity.class.getDeclaredField("scannedCards");
                    Field adapterField = CardBrowserV2Activity.class.getDeclaredField("adapter");
                    scannedField.setAccessible(true);
                    adapterField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<CharacterCard> cards = (List<CharacterCard>) scannedField.get(activity);
                    CharacterCard card = cards.get(0);
                    card.persona = null;
                    card.greetings.clear();
                    ((BaseAdapter) adapterField.get(activity)).notifyDataSetChanged();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText("开场白：点击查看后读取")).check(matches(isDisplayed()));

            scenario.onActivity(activity -> {
                try {
                    Field scannedField = CardBrowserV2Activity.class.getDeclaredField("scannedCards");
                    Field adapterField = CardBrowserV2Activity.class.getDeclaredField("adapter");
                    scannedField.setAccessible(true);
                    adapterField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<CharacterCard> cards = (List<CharacterCard>) scannedField.get(activity);
                    CharacterCard card = cards.get(0);
                    card.persona = "已读取";
                    for (int i = 1; i <= 22; i++) card.greetings.add("开场白 " + i);
                    ((BaseAdapter) adapterField.get(activity)).notifyDataSetChanged();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText("开场白：22 个")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void detailAndFullScreenGreetingNavigationAllWork() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, GuardedCardBrowserActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<GuardedCardBrowserActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withText("查看")).perform(click());
            onView(withText("开场白 1 / 2")).check(matches(isDisplayed()));

            onView(withContentDescription("上方下一个开场白")).perform(click());
            onView(withText("开场白 2 / 2")).check(matches(isDisplayed()));

            onView(withContentDescription("下方上一个开场白")).perform(click());
            onView(withText("开场白 1 / 2")).check(matches(isDisplayed()));

            onView(withText("全屏查看开场白")).perform(click());
            onView(withText("开场白 1 / 2")).check(matches(isDisplayed()));

            onView(withContentDescription("上方下一个开场白")).perform(click());
            onView(withText("开场白 2 / 2")).check(matches(isDisplayed()));

            onView(withContentDescription("下方上一个开场白")).perform(click());
            onView(withText("开场白 1 / 2")).check(matches(isDisplayed()));

            onView(withText(containsString("这是第一条测试开场白"))).perform(swipeLeft());
            onView(withText("开场白 2 / 2")).check(matches(isDisplayed()));

            onView(withText("← 返回卡片详情")).perform(click());
            onView(withText("开场白 2 / 2")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void alreadyValidNumberedNameIsNotRenamedAgain() {
        CardRenamer.RenameItem alreadyValid = new CardRenamer.RenameItem(
                Uri.parse("content://test/valid"), "parent", "角色A (1).png",
                "角色A (1).png", 100L, 1L, "png");
        alreadyValid.characterName = "角色A";
        alreadyValid.targetName = "角色A(4).png";
        alreadyValid.needsRename = true;

        CardRenamer.RenameItem actuallyWrong = new CardRenamer.RenameItem(
                Uri.parse("content://test/wrong"), "parent", "旧名字.png",
                "旧名字.png", 100L, 1L, "png");
        actuallyWrong.characterName = "角色B";
        actuallyWrong.targetName = "角色B.png";
        actuallyWrong.needsRename = true;

        List<CardRenamer.RenameItem> pending = new ArrayList<>();
        pending.add(alreadyValid);
        pending.add(actuallyWrong);
        List<CardRenamer.RenameItem> all = new ArrayList<>(pending);

        int corrected = RenamePlanCorrector.keepAlreadyValidNumberedNames(pending, all);

        assertEquals(1, corrected);
        assertFalse(alreadyValid.needsRename);
        assertEquals("角色A (1).png", alreadyValid.targetName);
        assertEquals(1, pending.size());
        assertSame(actuallyWrong, pending.get(0));
        assertTrue(actuallyWrong.needsRename);
    }
}
