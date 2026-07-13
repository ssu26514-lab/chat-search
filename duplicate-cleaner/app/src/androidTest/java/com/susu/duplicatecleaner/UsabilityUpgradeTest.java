package com.susu.duplicatecleaner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class UsabilityUpgradeTest {

    @Test
    public void detailShowsSideFavoriteAndTopFullScreenControls() {
        Context context = ApplicationProvider.getApplicationContext();
        FavoriteStore.clear(context);
        MovedCardStore.consume(context);
        Intent intent = new Intent(context, GuardedCardBrowserActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<GuardedCardBrowserActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withText("查看")).perform(click());
            onView(withText("☆ 收藏")).check(matches(isDisplayed()));
            onView(withText("图片全屏")).check(matches(isDisplayed()));
            onView(withText("全屏查看人设")).check(matches(isDisplayed()));
            onView(withText("全屏查看开场白")).check(matches(isDisplayed()));
            onView(withText("← 返回卡片列表")).perform(click());
            onView(withText("查看")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void movedCardIsRemovedFromScannedListAndFavorites() {
        Context context = ApplicationProvider.getApplicationContext();
        FavoriteStore.clear(context);
        MovedCardStore.consume(context);

        CharacterCard card = new CharacterCard();
        card.key = "test-card";
        card.uri = "content://com.susu.duplicatecleaner.test/card.png";
        card.treeUri = "content://com.susu.duplicatecleaner.test/tree";
        card.parentDocumentId = "test-parent";
        card.path = "测试角色.png";
        card.fileName = "测试角色.png";
        card.characterName = "测试角色";
        FavoriteStore.add(context, card);
        FavoriteStore.removeMany(context, Collections.singletonList(card.uri));
        assertEquals(0, FavoriteStore.load(context).size());

        Intent intent = new Intent(context, GuardedCardBrowserActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<GuardedCardBrowserActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withText("全部卡片（0）")).check(matches(isDisplayed()));
            onView(withText("收藏夹（0）")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void favoriteMovePageDisplaysAndScrollsLargeList() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, FavoriteMoveActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<FavoriteMoveActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withText("收藏移动管理")).check(matches(isDisplayed()));
            onView(withText("收藏共 200 张　｜　已选择 200 张\n列表区域占满剩余页面，可一直向下滚动查看全部卡片。 "))
                    .check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertEquals(200, list.getAdapter().getCount());
                list.setSelectionFromTop(199, 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertTrue(list.getLastVisiblePosition() >= 199
                        || list.getSelectedItemPosition() == 199);
            });
        }
    }

    @Test
    public void renamePreviewDisplaysAndScrollsFiveHundredRows() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, RenamePreviewActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<RenamePreviewActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withText("完整改名前后对照")).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertEquals(500, list.getAdapter().getCount());
                list.setSelectionFromTop(499, 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertTrue(list.getLastVisiblePosition() >= 499
                        || list.getSelectedItemPosition() == 499);
            });
        }
    }

    @Test
    public void duplicatePreviewScrollsAndChangesKeeperSelection() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, DuplicatePreviewActivity.class);
        intent.putExtra("test_mode", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<DuplicatePreviewActivity> scenario = ActivityScenario.launch(intent)) {
            onView(withText("完整重复组与保留选择")).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertEquals(80, list.getAdapter().getCount());
                View first = list.getChildAt(0);
                RadioGroup group = findRadioGroup(first);
                assertNotNull(group);
                RadioButton second = (RadioButton) group.getChildAt(1);
                second.performClick();
                assertEquals(1, DuplicatePreviewSession.keeperIndex(0));
                list.setSelectionFromTop(79, 0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ListView list = findListView(activity.getWindow().getDecorView());
                assertNotNull(list);
                assertTrue(list.getLastVisiblePosition() >= 79
                        || list.getSelectedItemPosition() == 79);
                assertEquals(1, DuplicatePreviewSession.keeperIndex(0));
            });
        }
    }

    private static ListView findListView(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ListView found = findListView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static RadioGroup findRadioGroup(View view) {
        if (view instanceof RadioGroup) return (RadioGroup) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            RadioGroup found = findRadioGroup(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
