package com.susu.duplicatecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SemanticDuplicateActivity extends Activity {
    private static final int REQUEST_TREE = 9101;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private Uri treeUri;
    private boolean busy;
    private boolean sessionAcquired;
    private boolean showingExact = true;
    private boolean testMode;

    private Button chooseButton;
    private Button scanButton;
    private Button cancelButton;
    private Button exactButton;
    private Button variantButton;
    private Button failureButton;
    private TextView folderText;
    private TextView statusText;
    private TextView summaryText;
    private ProgressBar progressBar;
    private ListView listView;
    private GroupAdapter adapter;
    private List<SemanticCardScanner.Group> visibleGroups = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.SEMANTIC_DUPLICATE);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
        if (testMode) {
            refreshFromSession();
            statusText.setText("测试数据已载入。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFromSession();
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.SEMANTIC_DUPLICATE);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("角色卡有效内容查重");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("专门识别“文件不同，但导入后实际内容相同”的角色卡。会分别比较人设、开场白、世界书、正则、扩展和其他有效字段；封面、chara/ccv3、构建标记和水印单独作为封装差异展示。现有严格字节查重不会受到影响。");
        description.setTextSize(14);
        description.setPadding(0, dp(7), 0, dp(10));
        root.addView(description);

        folderText = infoBox(testMode ? "测试模式" : "尚未选择角色卡文件夹");
        root.addView(folderText, matchWrap());

        chooseButton = button("重新选择角色卡文件夹");
        chooseButton.setOnClickListener(v -> chooseFolder());
        root.addView(chooseButton, marginTop(7));

        scanButton = button("开始分析角色卡有效内容");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, marginTop(6));

        cancelButton = button("取消当前任务");
        cancelButton.setOnClickListener(v -> {
            cancelRequested.set(true);
            statusText.setText("正在取消……");
        });
        root.addView(cancelButton, marginTop(6));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(8));

        statusText = new TextView(this);
        statusText.setText("请选择文件夹后扫描。这个过程会读取每张 PNG 内部角色卡数据，速度会比普通浏览扫描慢。 ");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(7), 0, dp(5));
        root.addView(statusText);

        summaryText = infoBox("等待扫描");
        root.addView(summaryText, matchWrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        exactButton = button("内容相同，可清理");
        variantButton = button("同名但有变化");
        exactButton.setOnClickListener(v -> showExact());
        variantButton.setOnClickListener(v -> showVariants());
        tabs.addView(exactButton, weighted());
        tabs.addView(variantButton, weightedMargin());
        root.addView(tabs, marginTop(8));

        failureButton = button("查看未识别 / 失败文件");
        failureButton.setOnClickListener(v -> showFailures());
        root.addView(failureButton, marginTop(6));

        listView = new ListView(this);
        listView.setDividerHeight(dp(7));
        adapter = new GroupAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshButtons();
    }

    private void chooseFolder() {
        if (busy || testMode) return;
        SemanticDuplicateSession.clear();
        treeUri = null;
        refreshFromSession();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        treeUri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {
        }
        folderText.setText("本次角色卡文件夹：\n" + treeUri);
        statusText.setText("文件夹已选择，请开始分析。");
        summaryText.setText("等待扫描");
        refreshButtons();
    }

    private void startScan() {
        if (busy || treeUri == null || testMode) return;
        cancelRequested.set(false);
        SemanticDuplicateSession.clear();
        setBusy(true, "正在建立 PNG 索引……");
        Uri selected = treeUri;
        executor.execute(() -> {
            SemanticCardScanner scanner = new SemanticCardScanner(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                SemanticCardScanner.ScanResult result = scanner.scan(selected);
                SemanticDuplicateSession.set(result);
                runOnUiThread(() -> showScanResult(result));
            } catch (SemanticCardScanner.CancelledException e) {
                runOnUiThread(() -> {
                    SemanticDuplicateSession.clear();
                    finishBusy("分析已取消。再次使用前请重新扫描。");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    SemanticDuplicateSession.clear();
                    finishBusy("分析失败：" + safeMessage(e));
                });
            }
        });
    }

    private void showScanResult(SemanticCardScanner.ScanResult result) {
        finishBusy("分析完成。");
        summaryText.setText(String.format(Locale.CHINA,
                "扫描 PNG：%d 张　｜　成功解析：%d 张　｜　未识别：%d 张\n"
                        + "有效内容完全相同：%d 组，可清理副本 %d 个\n"
                        + "同名但内容有变化：%d 组，只展示差异，不自动删除\n耗时：%.1f 秒",
                result.totalPng, result.parsedCards, result.failedCards,
                result.exactGroups.size(), result.exactDuplicateCopies(),
                result.variantGroups.size(), result.elapsedMs / 1000.0));
        showingExact = true;
        refreshFromSession();
    }

    private void showExact() {
        showingExact = true;
        refreshFromSession();
    }

    private void showVariants() {
        showingExact = false;
        refreshFromSession();
    }

    private void refreshFromSession() {
        SemanticCardScanner.GroupType type = showingExact
                ? SemanticCardScanner.GroupType.EXACT_CONTENT
                : SemanticCardScanner.GroupType.RELATED_VARIANTS;
        visibleGroups = SemanticDuplicateSession.groups(type);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (exactButton != null) {
            exactButton.setText("内容相同（" + SemanticDuplicateSession.groups(
                    SemanticCardScanner.GroupType.EXACT_CONTENT).size() + "）");
            variantButton.setText("有内容变化（" + SemanticDuplicateSession.groups(
                    SemanticCardScanner.GroupType.RELATED_VARIANTS).size() + "）");
            failureButton.setText("未识别 / 失败（"
                    + SemanticDuplicateSession.failures().size() + "）");
        }
        refreshButtons();
    }

    private void showFailures() {
        List<SemanticCardScanner.Failure> failures = SemanticDuplicateSession.failures();
        if (failures.isEmpty()) {
            Toast.makeText(this, "没有未识别文件", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(100, failures.size());
        for (int i = 0; i < limit; i++) {
            SemanticCardScanner.Failure failure = failures.get(i);
            builder.append("【").append(i + 1).append("】")
                    .append(failure.path).append('\n')
                    .append(failure.reason).append("\n\n");
        }
        if (failures.size() > limit) {
            builder.append("还有 ").append(failures.size() - limit)
                    .append(" 个未在对话框中展开。");
        }
        new AlertDialog.Builder(this)
                .setTitle("未识别 / 解析失败")
                .setMessage(builder.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openGroup(SemanticCardScanner.Group group) {
        Intent intent = new Intent(this, SemanticGroupActivity.class);
        intent.putExtra("group_id", group.id);
        intent.putExtra("test_mode", testMode);
        startActivity(intent);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        statusText.setText(message);
        refreshButtons();
    }

    private void finishBusy(String message) {
        busy = false;
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        refreshFromSession();
    }

    private void refreshButtons() {
        if (chooseButton == null) return;
        chooseButton.setEnabled(!busy && !testMode);
        scanButton.setEnabled(!busy && !testMode && treeUri != null);
        cancelButton.setEnabled(busy);
        exactButton.setEnabled(!busy);
        variantButton.setEnabled(!busy);
        failureButton.setEnabled(!busy && !SemanticDuplicateSession.failures().isEmpty());
    }

    private final class GroupAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleGroups.size(); }
        @Override public SemanticCardScanner.Group getItem(int position) {
            return visibleGroups.get(position);
        }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(SemanticDuplicateActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackgroundColor(0xfff4f4f4);

                TextView title = new TextView(SemanticDuplicateActivity.this);
                title.setTextSize(18);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView type = new TextView(SemanticDuplicateActivity.this);
                type.setTextSize(14);
                type.setPadding(0, dp(4), 0, 0);
                TextView summary = new TextView(SemanticDuplicateActivity.this);
                summary.setTextSize(13);
                summary.setPadding(0, dp(4), 0, dp(6));
                Button open = button("查看封面、差异与保留选择");
                open.setContentDescription("查看角色卡内容差异");
                row.addView(title);
                row.addView(type);
                row.addView(summary);
                row.addView(open, matchWrap());
                holder = new Holder(title, type, summary, open);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }
            SemanticCardScanner.Group group = getItem(position);
            SemanticCardParser.CardRecord first = group.cards.get(0);
            holder.title.setText(group.title);
            holder.type.setText(group.type == SemanticCardScanner.GroupType.EXACT_CONTENT
                    ? "✓ 有效内容完全一致 · " + group.cards.size()
                    + " 份 · 可选择保留一个"
                    : "⚠ 同名但实际内容有变化 · " + group.cards.size()
                    + " 份 · 只比较，不自动删除");
            holder.summary.setText(first.componentSummary()
                    + "\n文件大小范围：" + sizeRange(group.cards)
                    + (group.safeDelete ? "" : "\n该组存在内部数据冲突或内容变化，禁止自动删除"));
            holder.open.setOnClickListener(v -> openGroup(group));
            convertView.setOnClickListener(v -> openGroup(group));
            return convertView;
        }
    }

    private static final class Holder {
        final TextView title;
        final TextView type;
        final TextView summary;
        final Button open;
        Holder(TextView title, TextView type, TextView summary, Button open) {
            this.title = title;
            this.type = type;
            this.summary = summary;
            this.open = open;
        }
    }

    private String sizeRange(List<SemanticCardParser.CardRecord> cards) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (SemanticCardParser.CardRecord card : cards) {
            if (card.size < 0) continue;
            min = Math.min(min, card.size);
            max = Math.max(max, card.size);
        }
        if (min == Long.MAX_VALUE) return "未知";
        if (min == max) return formatBytes(min);
        return formatBytes(min) + " ～ " + formatBytes(max);
    }

    private TextView infoBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackgroundColor(0xfff1f1f1);
        return view;
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

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.CHINA, "%.2f %s", value, units[index]);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
