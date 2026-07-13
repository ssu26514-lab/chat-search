package com.susu.duplicatecleaner;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GuardedCardBrowserActivity extends CardBrowserV2Activity {
    private static final int REQUEST_MOVE_PAGE = 7201;
    private boolean sessionAcquired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.BROWSER);
        super.onCreate(savedInstanceState);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        addLargeMovePageButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncMovedCards();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) syncMovedCards();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MOVE_PAGE) syncMovedCards();
    }

    private void addLargeMovePageButton() {
        View content = findViewById(android.R.id.content);
        ListView list = findListView(content);
        if (list == null || !(list.getParent() instanceof LinearLayout)) return;

        LinearLayout parent = (LinearLayout) list.getParent();
        Button button = new Button(this);
        button.setText("打开收藏移动大页面");
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("打开收藏移动大页面");
        button.setOnClickListener(v -> {
            if (FavoriteStore.load(this).isEmpty()) {
                Toast.makeText(this, "收藏夹是空的，请先收藏卡片。", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(new Intent(this, FavoriteMoveActivity.class), REQUEST_MOVE_PAGE);
        });

        int index = parent.indexOfChild(list);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        parent.addView(button, Math.max(0, index), params);
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
            if (showingFavorites) {
                visibleField.set(this, FavoriteStore.load(this));
            } else {
                visibleField.set(this, scanned);
            }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.BROWSER);
        super.onDestroy();
    }
}
