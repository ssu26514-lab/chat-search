package com.susu.duplicatecleaner;

import android.content.Intent;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GuardedCardBrowserActivity extends CardBrowserV2Activity {
    private static final int REQUEST_MOVE_PAGE = 7201;
    private boolean sessionAcquired;

    private View sourceText;
    private View chooseSourceButton;
    private View scanButton;
    private View targetText;
    private View chooseTargetButton;
    private View originalMoveButton;
    private Button scanAreaToggle;
    private Button quickMoveToggle;
    private boolean scanAreaExpanded = true;
    private boolean quickMoveExpanded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.BROWSER);
        super.onCreate(savedInstanceState);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        installCompactControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncMovedCards();
        autoCollapseAfterScan();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            syncMovedCards();
            autoCollapseAfterScan();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MOVE_PAGE) syncMovedCards();
    }

    private void installCompactControls() {
        try {
            sourceText = readViewField("sourceText");
            chooseSourceButton = readViewField("chooseSourceButton");
            scanButton = readViewField("scanButton");
            targetText = readViewField("targetText");
            chooseTargetButton = readViewField("chooseTargetButton");
            originalMoveButton = readViewField("moveButton");
        } catch (Exception e) {
            Toast.makeText(this, "浏览操作区加载失败，原功能仍可使用。", Toast.LENGTH_LONG).show();
            return;
        }

        ListView list = findListView(findViewById(android.R.id.content));
        if (list == null || !(list.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) list.getParent();

        LinearLayout compactBar = new LinearLayout(this);
        compactBar.setOrientation(LinearLayout.HORIZONTAL);
        compactBar.setGravity(Gravity.CENTER_VERTICAL);

        scanAreaToggle = compactButton("收起扫描设置");
        scanAreaToggle.setContentDescription("展开或收起扫描设置");
        scanAreaToggle.setOnClickListener(v -> {
            scanAreaExpanded = !scanAreaExpanded;
            applyScanAreaVisibility();
        });
        compactBar.addView(scanAreaToggle, weighted());

        Button movePageButton = compactButton("收藏移动大页面");
        movePageButton.setContentDescription("打开收藏移动大页面");
        movePageButton.setOnClickListener(v -> {
            if (FavoriteStore.load(this).isEmpty()) {
                Toast.makeText(this, "收藏夹是空的，请先收藏卡片。", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(new Intent(this, FavoriteMoveActivity.class), REQUEST_MOVE_PAGE);
        });
        compactBar.addView(movePageButton, weightedMargin());

        quickMoveToggle = compactButton("展开快速移动");
        quickMoveToggle.setContentDescription("展开或收起原快速移动操作");
        quickMoveToggle.setOnClickListener(v -> {
            quickMoveExpanded = !quickMoveExpanded;
            applyQuickMoveVisibility();
        });
        compactBar.addView(quickMoveToggle, weightedMargin());

        int index = parent.indexOfChild(list);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        params.bottomMargin = dp(5);
        parent.addView(compactBar, Math.max(0, index), params);

        quickMoveExpanded = false;
        applyQuickMoveVisibility();

        BaseAdapter adapter = readAdapter();
        if (adapter != null) {
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    autoCollapseAfterScan();
                }
            });
        }
        autoCollapseAfterScan();
    }

    private void autoCollapseAfterScan() {
        BaseAdapter adapter = readAdapter();
        if (adapter != null && adapter.getCount() > 0 && scanAreaExpanded) {
            scanAreaExpanded = false;
            applyScanAreaVisibility();
        }
    }

    private void applyScanAreaVisibility() {
        int visibility = scanAreaExpanded ? View.VISIBLE : View.GONE;
        if (sourceText != null) sourceText.setVisibility(visibility);
        if (chooseSourceButton != null) chooseSourceButton.setVisibility(visibility);
        if (scanButton != null) scanButton.setVisibility(visibility);
        if (scanAreaToggle != null) {
            scanAreaToggle.setText(scanAreaExpanded ? "收起扫描设置" : "展开扫描设置");
        }
    }

    private void applyQuickMoveVisibility() {
        int visibility = quickMoveExpanded ? View.VISIBLE : View.GONE;
        if (targetText != null) targetText.setVisibility(visibility);
        if (chooseTargetButton != null) chooseTargetButton.setVisibility(visibility);
        if (originalMoveButton != null) originalMoveButton.setVisibility(visibility);
        if (quickMoveToggle != null) {
            quickMoveToggle.setText(quickMoveExpanded ? "收起快速移动" : "展开快速移动");
        }
    }

    @SuppressWarnings("unchecked")
    private void syncMovedCards() {
        List<String> movedUris = MovedCardStore.consume(this);
        if (movedUris.isEmpty()) return;
        Set<String> moved = new HashSet<>(movedUris);
        try {
            Field scannedField = CardBrowserV2Activity.class.getDeclaredField("scannedCards");
            Field visibleField = CardBrowserV2Activity.class.getDeclaredField("visibleCards");
            Field adapterField = CardBrowserV2Activity.class.getDeclaredField("adapter");
            Field favoritesField = CardBrowserV2Activity.class.getDeclaredField("showingFavorites");
            scannedField.setAccessible(true);
            visibleField.setAccessible(true);
            adapterField.setAccessible(true);
            favoritesField.setAccessible(true);

            List<CharacterCard> scanned = (List<CharacterCard>) scannedField.get(this);
            scanned.removeIf(card -> moved.contains(card.uri));
            boolean showingFavorites = favoritesField.getBoolean(this);
            if (showingFavorites) visibleField.set(this, FavoriteStore.load(this));
            else visibleField.set(this, scanned);
            ((BaseAdapter) adapterField.get(this)).notifyDataSetChanged();

            Method updateCounts = CardBrowserV2Activity.class.getDeclaredMethod("updateCounts");
            Method refreshButtons = CardBrowserV2Activity.class.getDeclaredMethod("refreshButtons");
            updateCounts.setAccessible(true);
            refreshButtons.setAccessible(true);
            updateCounts.invoke(this);
            refreshButtons.invoke(this);
        } catch (Exception e) {
            Toast.makeText(this, "移动已完成；列表同步失败时请重新扫描。", Toast.LENGTH_LONG).show();
        }
    }

    private View readViewField(String name) throws Exception {
        Field field = CardBrowserV2Activity.class.getDeclaredField(name);
        field.setAccessible(true);
        return (View) field.get(this);
    }

    private BaseAdapter readAdapter() {
        try {
            Field field = CardBrowserV2Activity.class.getDeclaredField("adapter");
            field.setAccessible(true);
            return (BaseAdapter) field.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    private ListView findListView(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ListView found = findListView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(3), 0, dp(3), 0);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(50), 1f);
    }

    private LinearLayout.LayoutParams weightedMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(4);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.BROWSER);
        super.onDestroy();
    }
}
