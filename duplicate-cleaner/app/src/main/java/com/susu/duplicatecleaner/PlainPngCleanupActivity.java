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
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlainPngCleanupActivity extends Activity {
    private static final int REQUEST_SOURCE = 9301;
    private static final int REQUEST_TARGET = 9302;
    private static final int CATEGORY_PLAIN = 0;
    private static final int CATEGORY_JSON = 1;
    private static final int CATEGORY_DAMAGED = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final List<PlainPngItem> plainItems = new ArrayList<>();
    private final List<PlainPngItem> jsonItems = new ArrayList<>();
    private final List<PlainPngItem> damagedItems = new ArrayList<>();
    private final List<PlainPngItem> visibleItems = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();

    private Uri sourceTreeUri;
    private Uri targetTreeUri;
    private int category = CATEGORY_PLAIN;
    private boolean busy;
    private boolean testMode;
    private boolean sessionAcquired;

    private TextView sourceText;
    private TextView targetText;
    private TextView summaryText;
    private TextView statusText;
    private Button sourceButton;
    private Button scanButton;
    private Button plainTab;
    private Button jsonTab;
    private Button damagedTab;
    private Button selectAllButton;
    private Button clearButton;
    private Button targetButton;
    private Button moveButton;
    private Button deleteButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private CleanupAdapter adapter;
    private ThumbnailLoader thumbnailLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.PLAIN_PNG_CLEANUP);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        if (testMode) loadTestItems();
        buildUi();
        showCategory(CATEGORY_PLAIN);
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.PLAIN_PNG_CLEANUP);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("无角色卡内容 PNG / JSON 筛选");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("普通 PNG 可移动或删除；能正常读取但不是角色卡的 JSON 可能是预设、美化或世界书，只允许移动；损坏 PNG / JSON 也只能移动。正常 PNG 与 JSON 角色卡都会被排除。 ");
        description.setTextSize(14);
        description.setPadding(0, dp(7), 0, dp(9));
        root.addView(description);

        sourceText = infoBox(testMode ? "测试模式" : "来源文件夹：尚未选择");
        root.addView(sourceText, matchWrap());

        LinearLayout sourceBar = new LinearLayout(this);
        sourceBar.setOrientation(LinearLayout.HORIZONTAL);
        sourceButton = button("重新选择来源文件夹");
        scanButton = button("开始筛选 PNG / JSON");
        sourceButton.setOnClickListener(v -> chooseSource());
        scanButton.setOnClickListener(v -> startScan());
        sourceBar.addView(sourceButton, weighted());
        sourceBar.addView(scanButton, weightedMargin());
        root.addView(sourceBar, marginTop(6));

        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(6));

        statusText = new TextView(this);
        statusText.setText(testMode ? "测试数据已载入。"
                : "请选择角色卡文件夹后开始筛选。 ");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(5), 0, dp(5));
        root.addView(statusText);

        summaryText = infoBox("");
        root.addView(summaryText, matchWrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        plainTab = smallButton("");
        jsonTab = smallButton("");
        damagedTab = smallButton("");
        plainTab.setOnClickListener(v -> showCategory(CATEGORY_PLAIN));
        jsonTab.setOnClickListener(v -> showCategory(CATEGORY_JSON));
        damagedTab.setOnClickListener(v -> showCategory(CATEGORY_DAMAGED));
        tabs.addView(plainTab, weighted());
        tabs.addView(jsonTab, weightedMargin());
        tabs.addView(damagedTab, weightedMargin());
        root.addView(tabs, marginTop(7));

        LinearLayout selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选当前分类");
        clearButton = button("全部取消");
        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        selectionBar.addView(selectAllButton, weighted());
        selectionBar.addView(clearButton, weightedMargin());
        root.addView(selectionBar, marginTop(6));

        targetText = infoBox("移动目标文件夹：尚未选择");
        root.addView(targetText, marginTop(6));

        targetButton = button("选择移动目标文件夹");
        targetButton.setOnClickListener(v -> chooseTarget());
        root.addView(targetButton, marginTop(5));

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        moveButton = button("移动已选择文件");
        deleteButton = button("永久删除已选择普通图片");
        moveButton.setOnClickListener(v -> confirmMove());
        deleteButton.setOnClickListener(v -> confirmDelete());
        actionBar.addView(moveButton, weighted());
        actionBar.addView(deleteButton, weightedMargin());
        root.addView(actionBar, marginTop(5));

        cancelButton = button("取消当前任务");
        cancelButton.setOnClickListener(v -> cancelRequested.set(true));
        root.addView(cancelButton, marginTop(5));

        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        adapter = new CleanupAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void chooseSource() {
        if (busy || testMode) return;
        clearAllResults();
        Intent intent = treeIntent();
        startActivityForResult(intent, REQUEST_SOURCE);
    }

    private void chooseTarget() {
        if (busy || testMode) return;
        startActivityForResult(treeIntent(), REQUEST_TARGET);
    }

    private Intent treeIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }
        if (requestCode == REQUEST_SOURCE) {
            sourceTreeUri = uri;
            sourceText.setText("来源文件夹：\n" + uri);
            statusText.setText("来源文件夹已选择，请开始筛选。");
        } else if (requestCode == REQUEST_TARGET) {
            targetTreeUri = uri;
            targetText.setText("移动目标文件夹：\n" + uri);
            statusText.setText("目标文件夹已选择，可以移动当前选中的文件。 ");
        }
        refreshButtons();
    }

    private void startScan() {
        if (busy || testMode || sourceTreeUri == null) return;
        clearAllResults();
        cancelRequested.set(false);
        setBusy(true, "正在建立 PNG / JSON 索引……");
        Uri source = sourceTreeUri;
        executor.execute(() -> {
            RoleCardAbsenceScanner scanner = new RoleCardAbsenceScanner(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                RoleCardAbsenceScanner.ScanResult result = scanner.scan(source);
                runOnUiThread(() -> showScanResult(result));
            } catch (RoleCardAbsenceScanner.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("筛选已取消，请重新扫描后再操作。");
                    clearAllResults();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("筛选失败：" + safeMessage(e));
                    clearAllResults();
                });
            }
        });
    }

    private void showScanResult(RoleCardAbsenceScanner.ScanResult result) {
        plainItems.clear();
        plainItems.addAll(result.plainImages);
        jsonItems.clear();
        jsonItems.addAll(result.nonCardJson);
        damagedItems.clear();
        damagedItems.addAll(result.damaged);
        finishBusy("筛选完成。");
        summaryText.setText(String.format(Locale.CHINA,
                "扫描 PNG / JSON：%d 个　｜　正常角色卡：%d 个（已排除）\n"
                        + "普通 PNG：%d 个，可移动或删除\n"
                        + "非角色卡 JSON：%d 个，只能移动\n"
                        + "损坏 / 无法读取：%d 个，只能移动\n耗时：%.1f 秒",
                result.totalFiles, result.validCards, result.plainImages.size(),
                result.nonCardJson.size(), result.damaged.size(),
                result.elapsedMs / 1000.0));
        showCategory(CATEGORY_PLAIN);
    }

    private void showCategory(int targetCategory) {
        category = targetCategory;
        selectedUris.clear();
        visibleItems.clear();
        if (category == CATEGORY_PLAIN) visibleItems.addAll(plainItems);
        else if (category == CATEGORY_JSON) visibleItems.addAll(jsonItems);
        else visibleItems.addAll(damagedItems);
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void selectAll() {
        selectedUris.clear();
        for (PlainPngItem item : visibleItems) selectedUris.add(item.uri);
        adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void clearSelection() {
        selectedUris.clear();
        adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void toggleSelection(PlainPngItem item, boolean checked) {
        if (checked) selectedUris.add(item.uri);
        else selectedUris.remove(item.uri);
        updateSummary();
        refreshButtons();
    }

    private List<PlainPngItem> selectedItems() {
        List<PlainPngItem> result = new ArrayList<>();
        for (PlainPngItem item : visibleItems) {
            if (selectedUris.contains(item.uri)) result.add(item);
        }
        return result;
    }

    private void confirmMove() {
        List<PlainPngItem> selected = selectedItems();
        if (busy || testMode || selected.isEmpty()) return;
        if (targetTreeUri == null) {
            Toast.makeText(this, "请先选择移动目标文件夹",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认安全移动")
                .setMessage("即将移动 " + selected.size()
                        + " 个文件。会保留 PNG / JSON 原格式，并执行复制、SHA-256 校验、大小校验，完全一致后才删除源文件。 ")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移动", (dialog, which) -> startMove(selected))
                .show();
    }

    private void startMove(List<PlainPngItem> selected) {
        cancelRequested.set(false);
        setBusy(true, "正在准备移动……");
        List<CharacterCard> movable = new ArrayList<>();
        for (PlainPngItem item : selected) movable.add(item.asMovableFile());
        Uri target = targetTreeUri;
        executor.execute(() -> {
            CardMoveManager manager = new CardMoveManager(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardMoveManager.MoveResult result = manager.moveFavorites(movable, target);
                runOnUiThread(() -> showMoveResult(result));
            } catch (CardMoveManager.CancelledException e) {
                runOnUiThread(() -> finishBusy(
                        "移动已取消。已完成文件保持移动，未完成文件仍在原处。 "));
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy("移动失败：" + safeMessage(e)));
            }
        });
    }

    private void showMoveResult(CardMoveManager.MoveResult result) {
        finishBusy("移动完成：成功 " + result.moved + "，安全跳过 "
                + result.skipped + "，失败 " + result.failed);
        removeCompleted(result.completedSourceUris);
        new AlertDialog.Builder(this)
                .setTitle("移动结果")
                .setMessage("成功 " + result.moved + "，安全跳过 " + result.skipped
                        + "，失败 " + result.failed + "\n\n" + result.log)
                .setPositiveButton("完成", null)
                .show();
    }

    private void confirmDelete() {
        List<PlainPngItem> selected = selectedItems();
        if (busy || testMode || category != CATEGORY_PLAIN || selected.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("确认永久删除普通 PNG")
                .setMessage("即将永久删除 " + selected.size()
                        + " 张普通 PNG。删除前会再次确认每个文件仍然没有 chara / ccv3。JSON 和疑似损坏文件不会进入删除流程。 ")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续确认", (dialog, which) ->
                        new AlertDialog.Builder(this)
                                .setTitle("最后确认")
                                .setMessage("删除不可撤销。确定永久删除所选普通 PNG 吗？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("永久删除",
                                        (d, w) -> startDelete(selected))
                                .show())
                .show();
    }

    private void startDelete(List<PlainPngItem> selected) {
        cancelRequested.set(false);
        setBusy(true, "正在重新确认普通 PNG 身份……");
        executor.execute(() -> {
            PlainPngDeleteManager manager = new PlainPngDeleteManager(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                PlainPngDeleteManager.DeleteResult result =
                        manager.deletePlainImages(selected);
                runOnUiThread(() -> showDeleteResult(result));
            } catch (PlainPngDeleteManager.CancelledException e) {
                runOnUiThread(() -> finishBusy(
                        "删除已取消。已经删除的文件无法恢复，其余文件仍保留。 "));
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy("删除停止：" + safeMessage(e)));
            }
        });
    }

    private void showDeleteResult(PlainPngDeleteManager.DeleteResult result) {
        finishBusy("删除完成：成功 " + result.deleted + "，安全跳过 "
                + result.skipped + "，失败 " + result.failed);
        removeCompleted(result.deletedUris);
        new AlertDialog.Builder(this)
                .setTitle("普通 PNG 删除结果")
                .setMessage("成功 " + result.deleted + "，安全跳过 "
                        + result.skipped + "，失败 " + result.failed
                        + "\n\n" + result.log)
                .setPositiveButton("完成", null)
                .show();
    }

    private void removeCompleted(List<String> uris) {
        if (uris == null || uris.isEmpty()) return;
        Set<String> removed = new HashSet<>(uris);
        plainItems.removeIf(item -> removed.contains(item.uri));
        jsonItems.removeIf(item -> removed.contains(item.uri));
        damagedItems.removeIf(item -> removed.contains(item.uri));
        selectedUris.removeAll(removed);
        showCategory(category);
    }

    private void openFullScreen(PlainPngItem item) {
        if (item.fileName == null
                || !item.fileName.toLowerCase(Locale.ROOT).endsWith(".png")) return;
        Intent intent = new Intent(this, FullScreenImageActivity.class);
        intent.putExtra("image_uri", item.uri);
        startActivity(intent);
    }

    private void clearAllResults() {
        plainItems.clear();
        jsonItems.clear();
        damagedItems.clear();
        visibleItems.clear();
        selectedUris.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void loadTestItems() {
        for (int i = 1; i <= 120; i++) {
            plainItems.add(testItem("plain-" + i,
                    "普通图片/旧照片" + i + ".png",
                    PlainPngItem.Type.PLAIN_IMAGE,
                    "PNG 内没有 chara / ccv3，属于普通图片"));
        }
        for (int i = 1; i <= 30; i++) {
            jsonItems.add(testItem("json-" + i,
                    "其他JSON/预设或美化" + i + ".json",
                    PlainPngItem.Type.NON_CARD_JSON,
                    "JSON 可读取，但不是角色卡，只允许移动"));
        }
        for (int i = 1; i <= 40; i++) {
            PlainPngItem item = testItem("damaged-" + i,
                    "待修复/损坏角色卡" + i + (i % 2 == 0 ? ".json" : ".png"),
                    PlainPngItem.Type.DAMAGED_OR_UNREADABLE,
                    "角色卡标记或 JSON 存在，但内容无法解析");
            item.markerSummary = "疑似损坏，只允许移动";
            damagedItems.add(item);
        }
    }

    private PlainPngItem testItem(String id, String path, PlainPngItem.Type type,
                                  String reason) {
        PlainPngItem item = new PlainPngItem();
        item.key = id;
        item.treeUri = "content://plain-png-test/tree";
        item.uri = "content://plain-png-test/" + id;
        item.parentDocumentId = "parent";
        item.path = path;
        item.fileName = path.substring(path.lastIndexOf('/') + 1);
        item.size = 1024L * (100 + id.length());
        item.modified = 1000L;
        item.width = item.fileName.endsWith(".png") ? 512 : 0;
        item.height = item.fileName.endsWith(".png") ? 768 : 0;
        item.type = type;
        item.reason = reason;
        item.markerSummary = type == PlainPngItem.Type.PLAIN_IMAGE
                ? "没有角色卡数据块"
                : type == PlainPngItem.Type.NON_CARD_JSON
                ? "非角色卡 JSON" : "检测到异常";
        return item;
    }

    private void updateSummary() {
        if (plainTab == null) return;
        plainTab.setText("普通图片（" + plainItems.size() + "）");
        jsonTab.setText("非角色JSON（" + jsonItems.size() + "）");
        damagedTab.setText("疑似损坏（" + damagedItems.size() + "）");
        String rule = category == CATEGORY_PLAIN
                ? "当前：普通 PNG，可移动或删除"
                : category == CATEGORY_JSON
                ? "当前：非角色卡 JSON，可能是预设/美化/世界书，只能移动"
                : "当前：疑似损坏 PNG / JSON，只能移动到待修复文件夹";
        summaryText.setText(rule + "\n当前列表 " + visibleItems.size()
                + " 个，已选择 " + selectedUris.size() + " 个。 ");
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
        if (sourceButton == null) return;
        sourceButton.setEnabled(!busy && !testMode);
        scanButton.setEnabled(!busy && !testMode && sourceTreeUri != null);
        plainTab.setEnabled(!busy);
        jsonTab.setEnabled(!busy);
        damagedTab.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && !visibleItems.isEmpty());
        clearButton.setEnabled(!busy && !selectedUris.isEmpty());
        targetButton.setEnabled(!busy && !testMode);
        moveButton.setEnabled(!busy && !testMode && targetTreeUri != null
                && !selectedUris.isEmpty());
        deleteButton.setVisibility(category == CATEGORY_PLAIN
                ? View.VISIBLE : View.GONE);
        deleteButton.setEnabled(!busy && !testMode
                && category == CATEGORY_PLAIN && !selectedUris.isEmpty());
        cancelButton.setEnabled(busy);
    }

    private final class CleanupAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleItems.size(); }
        @Override public PlainPngItem getItem(int position) {
            return visibleItems.get(position);
        }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(PlainPngCleanupActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(7), dp(7), dp(7), dp(7));
                row.setBackgroundColor(0xfff4f4f4);

                CheckBox check = new CheckBox(PlainPngCleanupActivity.this);
                row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(58)));

                ImageView image = new ImageView(PlainPngCleanupActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(image, new LinearLayout.LayoutParams(dp(86), dp(104)));

                LinearLayout text = new LinearLayout(PlainPngCleanupActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(9), 0, 0, 0);
                TextView name = new TextView(PlainPngCleanupActivity.this);
                name.setTextSize(16);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView details = new TextView(PlainPngCleanupActivity.this);
                details.setTextSize(12);
                details.setTextIsSelectable(true);
                Button fullScreen = button("全屏查看图片");
                fullScreen.setContentDescription("全屏查看筛选图片");
                text.addView(name);
                text.addView(details, marginTop(3));
                text.addView(fullScreen, marginTop(4));
                row.addView(text, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                holder = new Holder(check, image, name, details, fullScreen);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            PlainPngItem item = getItem(position);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selectedUris.contains(item.uri));
            holder.check.setOnCheckedChangeListener((buttonView, checked) ->
                    toggleSelection(item, checked));
            holder.name.setText(item.fileName);
            holder.details.setText(item.path + "\n大小：" + formatBytes(item.size)
                    + "　尺寸：" + dimension(item)
                    + "\n" + item.markerSummary + "\n原因：" + item.reason);
            boolean png = item.fileName != null
                    && item.fileName.toLowerCase(Locale.ROOT).endsWith(".png");
            holder.fullScreen.setVisibility(png ? View.VISIBLE : View.GONE);
            holder.fullScreen.setOnClickListener(v -> openFullScreen(item));
            holder.image.setOnClickListener(png ? v -> openFullScreen(item) : null);
            if (!testMode && png) {
                thumbnailLoader.load(holder.image, item.contentUri(), dp(180));
            } else {
                holder.image.setImageDrawable(null);
                holder.image.setBackgroundColor(0xffe5e5e5);
            }
            return convertView;
        }
    }

    private static final class Holder {
        final CheckBox check;
        final ImageView image;
        final TextView name;
        final TextView details;
        final Button fullScreen;

        Holder(CheckBox check, ImageView image, TextView name,
               TextView details, Button fullScreen) {
            this.check = check;
            this.image = image;
            this.name = name;
            this.details = details;
            this.fullScreen = fullScreen;
        }
    }

    private TextView infoBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextIsSelectable(true);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
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

    private Button smallButton(String text) {
        Button button = button(text);
        button.setTextSize(11);
        button.setPadding(dp(2), 0, dp(2), 0);
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
        params.leftMargin = dp(4);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String dimension(PlainPngItem item) {
        return item.width > 0 && item.height > 0
                ? item.width + "×" + item.height : "非图片 / 未知";
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
