package com.susu.duplicatecleaner;

import android.app.Activity;
import android.net.Uri;
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

import java.util.ArrayList;
import java.util.List;

public class RenamePreviewActivity extends Activity {
    private final List<CardRenamer.RenameItem> rows = new ArrayList<>();
    private PreviewAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean testMode = getIntent().getBooleanExtra("test_mode", false);
        if (testMode) loadTestRows();
        else rows.addAll(RenamePreviewSession.allItems());

        if (rows.isEmpty()) {
            Toast.makeText(this, "改名预览已失效，请返回重新扫描。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button back = button("← 返回改名扫描页");
        back.setContentDescription("返回改名扫描页");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        TextView title = new TextView(this);
        title.setText("完整改名前后对照");
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        root.addView(title);

        int pending = 0;
        int skipped = 0;
        for (CardRenamer.RenameItem item : rows) {
            if (item.needsRename && item.error == null) pending++;
            if (item.error != null) skipped++;
        }
        TextView summary = new TextView(this);
        summary.setText("共 " + rows.size() + " 张　｜　待改名 " + pending
                + " 张　｜　安全跳过 " + skipped + " 张\n整页可上下滚动，所有原名、角色名和新文件名都会完整显示。");
        summary.setTextSize(14);
        summary.setPadding(0, dp(4), 0, dp(10));
        root.addView(summary);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(8));
        adapter = new PreviewAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadTestRows() {
        for (int i = 1; i <= 500; i++) {
            CardRenamer.RenameItem item = new CardRenamer.RenameItem(
                    Uri.parse("content://test/rename/" + i),
                    "parent", "folder/原文件" + i + ".png",
                    "原文件" + i + ".png", 100L + i, 0L, "png");
            item.characterName = "测试角色 " + i;
            item.targetName = "测试角色 " + i + ".png";
            item.needsRename = true;
            rows.add(item);
        }
    }

    private final class PreviewAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public CardRenamer.RenameItem getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(RenamePreviewActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                card.setBackgroundColor(0xfff4f4f4);

                TextView number = new TextView(RenamePreviewActivity.this);
                number.setTextSize(16);
                number.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView original = new TextView(RenamePreviewActivity.this);
                original.setTextSize(14);
                original.setTextIsSelectable(true);
                TextView character = new TextView(RenamePreviewActivity.this);
                character.setTextSize(14);
                character.setTextIsSelectable(true);
                TextView target = new TextView(RenamePreviewActivity.this);
                target.setTextSize(14);
                target.setTextIsSelectable(true);
                TextView status = new TextView(RenamePreviewActivity.this);
                status.setTextSize(13);

                card.addView(number);
                card.addView(original, marginTop(5));
                card.addView(character, marginTop(3));
                card.addView(target, marginTop(3));
                card.addView(status, marginTop(5));
                holder = new RowHolder(number, original, character, target, status);
                card.setTag(holder);
                convertView = card;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            CardRenamer.RenameItem item = getItem(position);
            holder.number.setText(String.format("%04d", position + 1));
            holder.original.setText("原名：" + item.path);
            holder.character.setText("角色名：" + safe(item.characterName));
            holder.target.setText("新文件名：" + safe(item.targetName));
            if (item.error != null) {
                holder.status.setText("安全跳过：" + item.error);
            } else if (item.needsRename) {
                holder.status.setText("状态：待改名");
            } else {
                holder.status.setText("状态：文件名已经正确");
            }
            return convertView;
        }
    }

    private static final class RowHolder {
        final TextView number;
        final TextView original;
        final TextView character;
        final TextView target;
        final TextView status;

        RowHolder(TextView number, TextView original, TextView character,
                  TextView target, TextView status) {
            this.number = number;
            this.original = original;
            this.character = character;
            this.target = target;
            this.status = status;
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "（无）" : value;
    }
}
