package com.susu.duplicatecleaner;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toast;

public class GuardedCardBrowserActivity extends CardBrowserActivity {
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

        // 列表行内包含“收藏”按钮。Android 默认会让可聚焦子控件拦截整行点击，
        // 导致“点击查看”没有反应。关闭列表子项聚焦后，整行查看与收藏按钮可同时使用。
        ListView listView = findListView(getWindow().getDecorView());
        if (listView != null) {
            listView.setItemsCanFocus(false);
            listView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            listView.setClickable(true);
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

    @Override
    protected void onDestroy() {
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.BROWSER);
        super.onDestroy();
    }
}
