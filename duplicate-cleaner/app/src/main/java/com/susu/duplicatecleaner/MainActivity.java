package com.susu.duplicatecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 1201;
    private static final String PREFS = "duplicate_cleaner";
    private static final String PREF_TREE_URI = "tree_uri";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private Button chooseButton;
    private Button scanButton;
    private Button cancelButton;
    private Button deleteButton;
    private Button copyLogButton;
    private TextView folderText;
    private TextView statusText;
    private TextView summaryText;
    private TextView detailText;
    private ProgressBar progressBar;
    private CheckBox confirmationCheck;

    private Uri treeUri;
    private List<DuplicateScanner.DuplicateGroup> groups = new ArrayList<>();
    private String lastLog = "";
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreFolderPermission();
        refreshButtons();
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("重复文件清理器");
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("只删除字节级完全一致的副本。大小筛选 → 完整 SHA-256 → 逐字节核对 → 删除前再次哈希并逐字节复核。任何不确定情况都会自动跳过。");
        description.setTextSize(15);
        description.setPadding(0, dp(8), 0, dp(14));
        root.addView(description);

        folderText = new TextView(this);
        folderText.setText("尚未授权文件夹");
        folderText.setTextSize(13);
        folderText.setTextIsSelectable(true);
        folderText.setPadding(dp(12), dp(12), dp(12), dp(12));
        folderText.setBackgroundColor(0xfff1f1f1);
        root.addView(folderText, matchWrap());

        chooseButton = button("选择并授权文件夹");
        chooseButton.setOnClickListener(v -> chooseFolder());
        root.addView(chooseButton, marginTop(12));

        scanButton = button("开始安全扫描");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, marginTop(8));

        cancelButton = button("取消当前任务");
        cancelButton.setOnClickListener(v -> {
            cancelRequested.set(true);
            statusText.setText("正在取消当前任务……");
        });
        root.addView(cancelButton, marginTop(8));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(14));

        statusText = new TextView(this);
        statusText.setText("请选择文件夹。");
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(8), 0, 0);
        root.addView(statusText);

        summaryText = new TextView(this);
        summaryText.setTextSize(17);
        summaryText.setTypeface(null, android.graphics.Typeface.BOLD);
        summaryText.setPadding(0, dp(18), 0, dp(8));
        root.addView(summaryText);

        detailText = new TextView(this);
        detailText.setTextSize(13);
        detailText.setTextIsSelectable(true);
        detailText.setPadding(dp(12), dp(12), dp(12), dp(12));
        detailText.setBackgroundColor(0xfff7f7f7);
        root.addView(detailText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        confirmationCheck = new CheckBox(this);
        confirmationCheck.setText("我已查看结果，确认每组保留 1 份，并永久删除其余已复核副本");
        confirmationCheck.setPadding(0, dp(14), 0, 0);
        confirmationCheck.setOnCheckedChangeListener((buttonView, isChecked) -> refreshButtons());
        root.addView(confirmationCheck);

        deleteButton = button("永久删除已确认的重复副本");
        deleteButton.setOnClickListener(v -> showDeleteConfirmation());
        root.addView(deleteButton, marginTop(8));

        copyLogButton = button("复制扫描 / 删除记录");
        copyLogButton.setOnClickListener(v -> copyLog());
        root.addView(copyLogButton, marginTop(8));

        TextView warning = new TextView(this);
        warning.setText("重要：删除是永久操作。第一次使用建议先选择一个小文件夹测试。本应用不会把“看起来相似”的图片当作重复文件，也不会处理读取失败、扫描后发生变化或无法再次确认的文件。");
        warning.setTextSize(13);
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning);

        setContentView(scroll);
    }

    private void restoreFolderPermission() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null);
        if (TextUtils.isEmpty(saved)) return;
        try {
            treeUri = Uri.parse(saved);
            folderText.setText("已授权：\n" + treeUri);
            statusText.setText("可以开始扫描。如果权限已经失效，请重新选择文件夹。");
        } catch (Exception ignored) {
            treeUri = null;
        }
    }

    private void chooseFolder() {
        if (busy) return;
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
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
            treeUri = selected;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_TREE_URI, selected.toString()).apply();
            groups = new ArrayList<>();
            confirmationCheck.setChecked(false);
            folderText.setText("已授权：\n" + selected);
            statusText.setText("文件夹授权成功，可以开始扫描。");
            summaryText.setText("");
            detailText.setText("");
            lastLog = "";
        } catch (Exception e) {
            treeUri = null;
            statusText.setText("无法保存读写权限：" + safeMessage(e));
        }
        refreshButtons();
    }

    private void startScan() {
        if (busy || treeUri == null) return;
        groups = new ArrayList<>();
        confirmationCheck.setChecked(false);
        lastLog = "";
        cancelRequested.set(false);
        setBusy(true, "正在枚举文件……");
        summaryText.setText("");
        detailText.setText("");

        Uri selected = treeUri;
        executor.execute(() -> {
            DuplicateScanner scanner = new DuplicateScanner(getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                DuplicateScanner.ScanResult result = scanner.scan(selected);
                runOnUiThread(() -> showScanResult(result));
            } catch (DuplicateScanner.CancelledException e) {
                runOnUiThread(() -> finishBusy("扫描已取消。"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("扫描失败：" + safeMessage(e));
                    detailText.setText("请重新授权文件夹后再试。\n\n" + e.getClass().getName() + ": " + safeMessage(e));
                });
            }
        });
    }

    private void showScanResult(DuplicateScanner.ScanResult result) {
        groups = result.groups;
        int copies = result.duplicateCopies();
        String summary = String.format(Locale.CHINA,
                "扫描完成：%d 组完全重复，%d 个可删除副本，可释放 %s",
                result.groups.size(), copies, formatBytes(result.reclaimableBytes()));
        finishBusy(summary);
        summaryText.setText(summary);

        StringBuilder report = new StringBuilder();
        report.append("扫描文件：").append(result.totalFiles).append('\n');
        report.append("大小未知而跳过：").append(result.unknownSizeFiles).append('\n');
        report.append("读取失败而跳过：").append(result.unreadableFiles).append('\n');
        report.append("逐字节比较：").append(result.byteComparisons).append(" 次\n");
        report.append("耗时：").append(String.format(Locale.CHINA, "%.1f 秒", result.elapsedMs / 1000.0)).append("\n\n");

        for (int i = 0; i < result.groups.size(); i++) {
            DuplicateScanner.DuplicateGroup group = result.groups.get(i);
            report.append("【重复组 ").append(i + 1).append("】")
                    .append(group.files.size()).append(" 个文件，单个 ")
                    .append(formatBytes(group.keeper().size)).append('\n');
            report.append("保留：").append(group.keeper().path).append('\n');
            for (int j = 1; j < group.files.size(); j++) {
                report.append("待删：").append(group.files.get(j).path).append('\n');
            }
            report.append('\n');
        }

        if (result.groups.isEmpty()) report.append("没有发现字节级完全一致的重复文件。\n");
        lastLog = "扫描时间：" + DateFormat.getDateTimeInstance().format(new Date()) + "\n" + report;
        detailText.setText(report.toString());
        saveLog(lastLog);
        refreshButtons();
    }

    private void showDeleteConfirmation() {
        if (busy || groups.isEmpty() || !confirmationCheck.isChecked()) return;
        int copies = 0;
        long bytes = 0;
        for (DuplicateScanner.DuplicateGroup group : groups) {
            copies += group.files.size() - 1;
            bytes += group.reclaimableBytes();
        }
        String message = "即将永久删除 " + copies + " 个副本，预计释放 " + formatBytes(bytes)
                + "。\n\n删除前每个文件都会重新计算完整 SHA-256，并再次逐字节比较；任何变化或读取异常都会跳过。此操作不可撤销。";

        new AlertDialog.Builder(this)
                .setTitle("确认永久删除")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> startDelete())
                .show();
    }

    private void startDelete() {
        if (busy || groups.isEmpty()) return;
        cancelRequested.set(false);
        setBusy(true, "正在删除前重新复核……");
        List<DuplicateScanner.DuplicateGroup> currentGroups = new ArrayList<>(groups);

        executor.execute(() -> {
            DuplicateScanner scanner = new DuplicateScanner(getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                DuplicateScanner.DeleteResult result = scanner.deleteVerifiedCopies(currentGroups);
                runOnUiThread(() -> showDeleteResult(result));
            } catch (DuplicateScanner.CancelledException e) {
                runOnUiThread(() -> finishBusy("删除任务已取消。已完成的删除无法撤销，未处理文件保持原样。"));
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy("删除任务异常终止：" + safeMessage(e)));
            }
        });
    }

    private void showDeleteResult(DuplicateScanner.DeleteResult result) {
        String summary = "删除完成：成功 " + result.deleted + "，安全跳过 " + result.skipped
                + "，失败 " + result.failed + "，释放 " + formatBytes(result.reclaimedBytes);
        finishBusy(summary);
        summaryText.setText(summary);
        String report = "删除时间：" + DateFormat.getDateTimeInstance().format(new Date()) + "\n"
                + summary + "\n\n" + result.log;
        lastLog = report;
        detailText.setText(report);
        saveLog(report);
        groups = new ArrayList<>();
        confirmationCheck.setChecked(false);
        refreshButtons();
    }

    private void copyLog() {
        if (TextUtils.isEmpty(lastLog)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("重复文件清理记录", lastLog));
        Toast.makeText(this, "记录已复制", Toast.LENGTH_SHORT).show();
    }

    private void saveLog(String text) {
        try {
            File directory = new File(getFilesDir(), "logs");
            if (!directory.exists()) directory.mkdirs();
            File file = new File(directory, "duplicate-cleaner-" + System.currentTimeMillis() + ".txt");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
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
        refreshButtons();
    }

    private void refreshButtons() {
        chooseButton.setEnabled(!busy);
        scanButton.setEnabled(!busy && treeUri != null);
        cancelButton.setEnabled(busy);
        deleteButton.setEnabled(!busy && !groups.isEmpty() && confirmationCheck.isChecked());
        confirmationCheck.setEnabled(!busy && !groups.isEmpty());
        copyLogButton.setEnabled(!busy && !TextUtils.isEmpty(lastLog));
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
