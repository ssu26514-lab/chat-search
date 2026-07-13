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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DuplicatePreviewActivity extends Activity {
    private final List<DuplicateScanner.DuplicateGroup> groups = new ArrayList<>();
    private GroupAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean testMode = getIntent().getBooleanExtra("test_mode", false);
        if (testMode) loadTestGroups();
        else groups.addAll(DuplicatePreviewSession.groups());

        if (groups.isEmpty()) {
            Toast.makeText(this, "重复组预览已失效，请返回重新扫描。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button back = button("← 返回查重扫描页");
        back.setContentDescription("返回查重扫描页");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        TextView title = new TextView(this);
        title.setText("完整重复组与保留选择");
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        root.addView(title);

        int copies = 0;
        long bytes = 0;
        for (DuplicateScanner.DuplicateGroup group : groups) {
            copies += Math.max(0, group.files.size() - 1);
            bytes += group.reclaimableBytes();
        }
        TextView summary = new TextView(this);
        summary.setText("共 " + groups.size() + " 组　｜　可删除副本 " + copies
                + " 个　｜　预计释放 " + formatBytes(bytes)
                + "\n每组默认选择推荐保留文件，你可以点选任意一个文件作为保留版本。整页可上下滚动。 ");
        summary.setTextSize(14);
        summary.setPadding(0, dp(4), 0, dp(10));
        root.addView(summary);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(8));
        adapter = new GroupAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadTestGroups() {
        List<DuplicateScanner.DuplicateGroup> testGroups = new ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            List<DuplicateScanner.FileEntry> files = new ArrayList<>();
            for (int j = 1; j <= 3; j++) {
                DuplicateScanner.FileEntry file = new DuplicateScanner.FileEntry(
                        Uri.parse("content://test/duplicate/" + i + "/" + j),
                        "doc-" + i + "-" + j,
                        "角色卡/分组" + i + "/版本" + j + ".png",
                        1024L * i,
                        1000L + j);
                files.add(file);
            }
            testGroups.add(new DuplicateScanner.DuplicateGroup(files));
        }
        DuplicatePreviewSession.set(testGroups);
        groups.addAll(testGroups);
    }

    private final class GroupAdapter extends BaseAdapter {
        @Override public int getCount() { return groups.size(); }
        @Override public DuplicateScanner.DuplicateGroup getItem(int position) { return groups.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            if (convertView == null) {
                card = new LinearLayout(DuplicatePreviewActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                card.setBackgroundColor(0xfff4f4f4);
                convertView = card;
            } else {
                card = (LinearLayout) convertView;
                card.removeAllViews();
            }

            DuplicateScanner.DuplicateGroup group = getItem(position);
            TextView header = new TextView(DuplicatePreviewActivity.this);
            header.setText("重复组 " + (position + 1) + "　｜　" + group.files.size()
                    + " 个文件　｜　单个 " + formatBytes(group.files.get(0).size));
            header.setTextSize(17);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(header);

            TextView help = new TextView(DuplicatePreviewActivity.this);
            help.setText("请选择这一组要保留的文件：");
            help.setTextSize(13);
            help.setPadding(0, dp(7), 0, dp(4));
            card.addView(help);

            RadioGroup radioGroup = new RadioGroup(DuplicatePreviewActivity.this);
            radioGroup.setOrientation(RadioGroup.VERTICAL);
            int selected = DuplicatePreviewSession.keeperIndex(position);
            for (int fileIndex = 0; fileIndex < group.files.size(); fileIndex++) {
                DuplicateScanner.FileEntry file = group.files.get(fileIndex);
                RadioButton radio = new RadioButton(DuplicatePreviewActivity.this);
                radio.setId(View.generateViewId());
                radio.setTag(fileIndex);
                radio.setText((fileIndex == 0 ? "推荐保留\n" : "保留此文件\n")
                        + file.path + "\n大小：" + formatBytes(file.size)
                        + "　修改时间：" + file.modified);
                radio.setTextSize(14);
                radio.setPadding(0, dp(4), 0, dp(6));
                radio.setChecked(fileIndex == selected);
                radio.setOnClickListener(v -> {
                    Object tag = v.getTag();
                    if (tag instanceof Integer) {
                        DuplicatePreviewSession.selectKeeper(position, (Integer) tag);
                    }
                });
                radioGroup.addView(radio, matchWrap());
            }
            card.addView(radioGroup);
            return convertView;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.CHINA, "%.2f %s", value, units[unit]);
    }
}
