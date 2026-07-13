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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CardRenamerActivity extends Activity {
    private static final int REQUEST_TREE = 2201;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private Button chooseButton;
    private Button scanButton;
    private Button cancelButton;
    private Button previewButton;
    private Button renameButton;
    private Button copyLogButton;
    private TextView folderText;
    private TextView statusText;
    private TextView summaryText;
    private ProgressBar progressBar;
    private CheckBox confirmationCheck;

    private Uri treeUri;
    private List<CardRenamer.RenameItem> pendingItems = new ArrayList<>();
    private String lastLog = "";
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
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
        title.setText("角色卡自动改名");
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("读取 PNG / JSON 内部角色名，只修改手机中原文件名。完整改名前后对照会放在独立的大列表页面中，不再挤在小窗口里。重名自动追加 (1)、(2)、(3)。");
        description.setTextSize(15);
        description.setPadding(0, dp(8), 0, dp(14));
        root.addView(description);

        folderText = infoBox("尚未选择文件夹。本功能每次使用都必须重新选择并扫描。");
        root.addView(folderText, matchWrap());

        chooseButton = button("重新选择文件夹");
        chooseButton.setOnClickListener(v -> chooseFolder());
        root.addView(chooseButton, marginTop(12));

        scanButton = button("扫描角色卡并预览名字");
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
        statusText.setText("请选择包含角色卡的文件夹。");
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(8), 0, 0);
        root.addView(statusText);

        summaryText = infoBox("扫描完成后，这里只显示摘要；完整列表请进入独立预览页查看。");
        summaryText.setTextSize(15);
        root.addView(summaryText, marginTop(14));

        previewButton = button("查看完整改名前后对照");
        previewButton.setContentDescription("查看完整改名预览");
        previewButton.setOnClickListener(v -> openPreview());
        root.addView(previewButton, marginTop(10));

        confirmationCheck = new CheckBox(this);
        confirmationCheck.setText("我已进入完整预览页核对改名前后对照，确认直接修改这些原文件的文件名");
        confirmationCheck.setPadding(0, dp(14), 0, 0);
        confirmationCheck.setOnCheckedChangeListener((buttonView, isChecked) -> refreshButtons());
        root.addView(confirmationCheck);

        renameButton = button("开始原地批量改名");
        renameButton.setOnClickListener(v -> showRenameConfirmation());
        root.addView(renameButton, marginTop(8));

        copyLogButton = button("复制扫描 / 改名记录");
        copyLogButton.setOnClickListener(v -> copyLog());
        root.addView(copyLogButton, marginTop(8));

        TextView warning = new TextView(this);
        warning.setText("改名不会改变角色卡内部角色名，也不会重新生成文件。扫描后如文件被移动、修改或目标名称被占用，该文件会安全跳过。完成后必须重新选择文件夹扫描。");
        warning.setTextSize(13);
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning);

        setContentView(scroll);
    }

    private void chooseFolder() {
        if (busy) return;
        clearCurrentSession();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

        treeUri = data.getData();
        pendingItems = new ArrayList<>();
        RenamePreviewSession.clear();
        confirmationCheck.setChecked(false);
        folderText.setText("本次已选择：\n" + treeUri);
        statusText.setText("文件夹选择成功，请点击扫描。");
        summaryText.setText("等待扫描。完整结果会进入独立预览页面。");
        lastLog = "";
        refreshButtons();
    }

    private void startScan() {
        if (busy || treeUri == null) return;
        pendingItems = new ArrayList<>();
        RenamePreviewSession.clear();
        confirmationCheck.setChecked(false);
        lastLog = "";
        cancelRequested.set(false);
        setBusy(true, "正在枚举 PNG / JSON 文件……");
        summaryText.setText("正在扫描，请稍候……");

        Uri selected = treeUri;
        executor.execute(() -> {
            CardRenamer renamer = new CardRenamer(getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardRenamer.ScanResult result = renamer.scan(selected);
                runOnUiThread(() -> showScanResult(result));
            } catch (CardRenamer.CancelledException e) {
                runOnUiThread(() -> finishBusy("扫描已取消。"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("扫描失败：" + safeMessage(e));
                    summaryText.setText("扫描失败，请重新选择文件夹。\n" + safeMessage(e));
                });
            }
        });
    }

    private void showScanResult(CardRenamer.ScanResult result) {
        pendingItems = result.renameItems;
        RenamePreviewSession.set(result.renameItems, result.allItems);
        String summary = String.format(Locale.CHINA,
                "扫描完成\n识别：%d 张\n需改名：%d 张\n已经正确：%d 张\n未识别 / 安全跳过：%d 张\n扫描全部文件：%d 个\nPNG / JSON：%d 个\n耗时：%.1f 秒",
                result.recognizedCards, result.renameItems.size(),
                result.unchangedCards, result.failedCards,
                result.totalFiles, result.supportedFiles, result.elapsedMs / 1000.0);
        finishBusy("扫描完成，请进入完整预览页核对。");
        summaryText.setText(summary);

        StringBuilder report = new StringBuilder(summary).append("\n\n");
        int number = 1;
        for (CardRenamer.RenameItem item : result.renameItems) {
            report.append("【待改名 ").append(number++).append("】\n")
                    .append("原名：").append(item.path).append('\n')
                    .append("角色名：").append(item.characterName).append('\n')
                    .append("新文件名：").append(item.targetName).append("\n\n");
        }
        lastLog = "扫描时间：" + DateFormat.getDateTimeInstance().format(new Date())
                + "\n" + report;
        refreshButtons();
    }

    private void openPreview() {
        if (busy || RenamePreviewSession.allItems().isEmpty()) return;
        startActivity(new Intent(this, RenamePreviewActivity.class));
    }

    private void showRenameConfirmation() {
        if (busy || pendingItems.isEmpty() || !confirmationCheck.isChecked()) return;
        new AlertDialog.Builder(this)
                .setTitle("确认直接修改原文件名")
                .setMessage("即将原地修改 " + pendingItems.size()
                        + " 个 PNG / JSON 文件名。不会修改卡片内部内容，也不会生成副本。改名完成后必须重新选择文件夹并重新扫描。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认改名", (dialog, which) -> startRename())
                .show();
    }

    private void startRename() {
        if (busy || pendingItems.isEmpty()) return;
        cancelRequested.set(false);
        setBusy(true, "正在检查文件是否发生变化……");
        List<CardRenamer.RenameItem> currentItems = new ArrayList<>(pendingItems);

        executor.execute(() -> {
            CardRenamer renamer = new CardRenamer(getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardRenamer.RenameResult result = renamer.renameAll(currentItems);
                runOnUiThread(() -> showRenameResult(result));
            } catch (CardRenamer.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("改名任务已取消。已完成的改名保持不变；继续操作前请重新选择并扫描。");
                    invalidateAfterOperation();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("改名任务异常终止：" + safeMessage(e));
                    invalidateAfterOperation();
                });
            }
        });
    }

    private void showRenameResult(CardRenamer.RenameResult result) {
        String summary = "改名完成：成功 " + result.renamed
                + "，安全跳过 " + result.skipped + "，失败 " + result.failed;
        finishBusy(summary);
        summaryText.setText(summary + "\n\n详细记录可点击“复制扫描 / 改名记录”保存。");
        lastLog = "改名时间：" + DateFormat.getDateTimeInstance().format(new Date())
                + "\n" + summary + "\n\n" + result.log;
        invalidateAfterOperation();
    }

    private void invalidateAfterOperation() {
        treeUri = null;
        pendingItems = new ArrayList<>();
        RenamePreviewSession.clear();
        confirmationCheck.setChecked(false);
        folderText.setText("本次操作已结束。再次使用必须重新选择文件夹并重新扫描。");
        refreshButtons();
    }

    private void clearCurrentSession() {
        treeUri = null;
        pendingItems = new ArrayList<>();
        RenamePreviewSession.clear();
        confirmationCheck.setChecked(false);
        summaryText.setText("等待扫描。完整结果会进入独立预览页面。");
        lastLog = "";
    }

    private void copyLog() {
        if (TextUtils.isEmpty(lastLog)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("角色卡改名记录", lastLog));
        Toast.makeText(this, "记录已复制", Toast.LENGTH_SHORT).show();
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
        previewButton.setEnabled(!busy && !RenamePreviewSession.allItems().isEmpty());
        confirmationCheck.setEnabled(!busy && !pendingItems.isEmpty());
        renameButton.setEnabled(!busy && !pendingItems.isEmpty() && confirmationCheck.isChecked());
        copyLogButton.setEnabled(!busy && !TextUtils.isEmpty(lastLog));
    }

    private TextView infoBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackgroundColor(0xfff3f3f3);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
