package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

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
        CharacterCard card = testCard();
        CardSession.clearCards();
        CardSession.put(card);

        Context context = ApplicationProvider.getApplicationContext();
        Intent detailIntent = new Intent(context, CardDetailActivity.class);
        detailIntent.putExtra("card_key", card.key);
        detailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<CardDetailActivity> scenario = ActivityScenario.launch(detailIntent)) {
            scenario.onActivity(activity -> {
                assertEquals("开场白 1 / 2", textOfField(activity, "greetingTitle"));

                clickByDescription(activity, "上方下一个开场白");
                assertEquals("开场白 2 / 2", textOfField(activity, "greetingTitle"));

                clickByDescription(activity, "下方上一个开场白");
                assertEquals("开场白 1 / 2", textOfField(activity, "greetingTitle"));

                TextView greetingText = (TextView) fieldValue(activity, "greetingText");
                dispatchHorizontalSwipe(greetingText, true);
                assertEquals("开场白 2 / 2", textOfField(activity, "greetingTitle"));
            });
        }

        CardSession.put(card);
        Intent fullIntent = new Intent(context, GreetingFullScreenActivity.class);
        fullIntent.putExtra(GreetingFullScreenActivity.EXTRA_CARD_KEY, card.key);
        fullIntent.putExtra(GreetingFullScreenActivity.EXTRA_GREETING_INDEX, 0);
        fullIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<GreetingFullScreenActivity> scenario = ActivityScenario.launch(fullIntent)) {
            scenario.onActivity(activity -> {
                assertEquals("开场白 1 / 2", textOfField(activity, "title"));

                clickByDescription(activity, "上方下一个开场白");
                assertEquals("开场白 2 / 2", textOfField(activity, "title"));

                clickByDescription(activity, "下方上一个开场白");
                assertEquals("开场白 1 / 2", textOfField(activity, "title"));

                TextView content = (TextView) fieldValue(activity, "content");
                dispatchHorizontalSwipe(content, true);
                assertEquals("开场白 2 / 2", textOfField(activity, "title"));
            });
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

    private static CharacterCard testCard() {
        CharacterCard card = new CharacterCard();
        card.key = "greeting-navigation-test";
        card.uri = "content://com.susu.duplicatecleaner.test/greeting.png";
        card.treeUri = "content://com.susu.duplicatecleaner.test/tree";
        card.parentDocumentId = "test-parent";
        card.path = "测试开场白.png";
        card.fileName = "测试开场白.png";
        card.characterName = "测试开场白";
        card.persona = "【角色描述】\n用于开场白导航测试。";
        card.greetings.add("这是第一条测试开场白。");
        card.greetings.add("这是第二条测试开场白。");
        return card;
    }

    private static void clickByDescription(Activity activity, String description) {
        View view = findByDescription(activity.findViewById(android.R.id.content), description);
        assertNotNull("未找到按钮：" + description, view);
        assertTrue("按钮点击未执行：" + description, view.performClick());
    }

    private static View findByDescription(View view, String description) {
        CharSequence current = view.getContentDescription();
        if (current != null && description.contentEquals(current)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findByDescription(group.getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private static Object fieldValue(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String textOfField(Object owner, String name) {
        TextView view = (TextView) fieldValue(owner, name);
        return view.getText().toString();
    }

    private static void dispatchHorizontalSwipe(View view, boolean toLeft) {
        int width = Math.max(view.getWidth(), 800);
        int height = Math.max(view.getHeight(), 400);
        float startX = toLeft ? width * 0.82f : width * 0.18f;
        float endX = toLeft ? width * 0.18f : width * 0.82f;
        float y = height * 0.5f;
        long downTime = SystemClock.uptimeMillis();

        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, startX, y, 0);
        MotionEvent move = MotionEvent.obtain(downTime, downTime + 35,
                MotionEvent.ACTION_MOVE, (startX + endX) / 2f, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 70,
                MotionEvent.ACTION_UP, endX, y, 0);
        try {
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(move);
            view.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            move.recycle();
            up.recycle();
        }
    }
}
