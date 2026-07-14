package com.susu.duplicatecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TavernFileClassifierActivity extends Activity {
    private static final int REQUEST_SOURCE = 9501;
    private static final int REQUEST_TARGET = 9502;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final List<TavernFileItem> allItems = new ArrayList<>();
    private final List<TavernFileItem> visibleItems = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private final Map<TavernFileItem.Category, Integer> categoryCounts =
            new EnumMap<>(TavernFileItem.Category.class);

    private Uri sourceTreeUri;
    private Uri targetTreeUri;
    private boolean busy;
    private boolean testMode;
    private boolean sessionAcquired;

    private TextView sourceText;
    private TextView targetText;
    private TextView summaryText;
    private TextView statusText;
    private Button sourceButton;
    private Button scanButton;
    private Button selectAllButton;
    private Button clearButton;
    private Button targetButton;
    private Button moveButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private Spinner filterSpinner;
    private FileAdapter adapter;
    private ThumbnailLoader thumbnailLoader;
    private String[] filterLabels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        sessionAcquired = ToolSession.acquire(ToolSession.Mode.FILE_CLASSIFIER);
        if (!sessionAcquired) {
            Toast.makeText(this, "另一个文件工具仍在运行，请先退出后再使用。",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        resetCounts();
        if (testMode) loadTestItems();
        buildUi();
        applyFilter(0);
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        if (sessionAcquired) ToolSession.release(ToolSession.Mode.FILE_CLASSIFIER);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("酒馆文件分类整理");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("同时识别 PNG 与 JSON 角色卡、预设、主题美化、世界书、正则、插件、图片素材和混合压缩包。每个文件都会说明“为什么这样分类”。第一版只允许安全移动，不提供分类后直接删除。 ");
        description.setTextSize(14);
        description.setPadding(0, dp(7), 0, dp(9));
        root.addView(description);

        sourceText = infoBox(testMode ? "测试模式" : "来源文件夹：尚未选择");
        root.addView(sourceText, matchWrap());

        LinearLayout sourceBar = new LinearLayout(this);
        sourceBar.setOrientation(LinearLayout.HORIZONTAL);
        sourceButton = button("重新选择混合文件夹");
        scanButton = button("开始分类");
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
        statusText.setText(testMode ? "测试分类数据已载入。"
                : "请选择混合文件夹后开始分类。 ");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(5), 0, dp(5));
        root.addView(statusText);

        summaryText = infoBox("");
        root.addView(summaryText, matchWrap());

        filterLabels = buildFilterLabels();
        filterSpinner = new Spinner(this);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, filterLabels);
        spinnerAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(spinnerAdapter);
        filterSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(
                this::applyFilter));
        root.addView(filterSpinner, marginTop(7));

        LinearLayout selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选当前分类");
        clearButton = button("全部取消");
        selectAllButton.setOnClickListener(v -> selectAllVisible());
        clearButton.setOnClickListener(v -> clearSelection());
        selectionBar.addView(selectAllButton, weighted());
        selectionBar.addView(clearButton, weightedMargin());
        root.addView(selectionBar, marginTop(6));

        targetText = infoBox("移动目标文件夹：尚未选择");
        root.addView(targetText, marginTop(6));

        targetButton = button("选择当前分类的目标文件夹");
        targetButton.setOnClickListener(v -> chooseTarget());
        root.addView(targetButton, marginTop(5));

        moveButton = button("安全移动已选择文件");
        moveButton.setOnClickListener(v -> confirmMove());
        root.addView(moveButton, marginTop(5));

        cancelButton = button("取消当前任务");
        cancelButton.setOnClickListener(v -> cancelRequested.set(true));
        root.addView(cancelButton, marginTop(5));

        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshButtons();
        updateSummary();
    }

    private void chooseSource() {
        if (busy || testMode) return;
        clearResults();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SOURCE);
    }

    private void chooseTarget() {
        if (busy || testMode) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TARGET);
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
            statusText.setText("来源文件夹已选择，请开始分类。");
        } else if (requestCode == REQUEST_TARGET) {
            targetTreeUri = uri;
            targetText.setText("移动目标文件夹：\n" + uri);
            statusText.setText("目标文件夹已选择，可以安全移动所选文件。 ");
        }
        refreshButtons();
    }

    private void startScan() {
        if (busy || testMode || sourceTreeUri == null) return;
        clearResults();
        cancelRequested.set(false);
        setBusy(true, "正在建立混合文件索引……");
        Uri source = sourceTreeUri;
        executor.execute(() -> {
            TavernFileClassifier classifier = new TavernFileClassifier(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                TavernFileClassifier.ScanResult result = classifier.scan(source);
                runOnUiThread(() -> showScanResult(result));
            } catch (TavernFileClassifier.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("分类已取消，请重新扫描后再操作。");
                    clearResults();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("分类失败：" + safeMessage(e));
                    clearResults();
                });
            }
        });
    }

    private void showScanResult(TavernFileClassifier.ScanResult result) {
        allItems.clear();
        allItems.addAll(result.items);
        resetCounts();
        categoryCounts.putAll(result.counts);
        finishBusy(String.format(Locale.CHINA,
                "分类完成：%d 个文件，耗时 %.1f 秒。",
                result.totalFiles, result.elapsedMs / 1000.0));
        filterLabels = buildFilterLabels();
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, filterLabels);
        spinnerAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(spinnerAdapter);
        applyFilter(0);
    }

    private void applyFilter(int position) {
        selectedUris.clear();
        visibleItems.clear();
        if (position <= 0) {
            visibleItems.addAll(allItems);
        } else {
            TavernFileItem.Category category =
                    TavernFileItem.Category.values()[position - 1];
            for (TavernFileItem item : allItems) {
                if (item.category == category) visibleItems.add(item);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void selectAllVisible() {
        selectedUris.clear();
        for (TavernFileItem item : visibleItems) selectedUris.add(item.uri);
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

    private void toggleSelection(TavernFileItem item, boolean checked) {
        if (checked) selectedUris.add(item.uri);
        else selectedUris.remove(item.uri);
        updateSummary();
        refreshButtons();
    }

    private List<TavernFileItem> selectedItems() {
        List<TavernFileItem> result = new ArrayList<>();
        for (TavernFileItem item : visibleItems) {
            if (selectedUris.contains(item.uri)) result.add(item);
        }
        return result;
    }

    private void confirmMove() {
        List<TavernFileItem> selected = selectedItems();
        if (busy || testMode || selected.isEmpty()) return;
        if (targetTreeUri == null) {
            Toast.makeText(this, "请先选择移动目标文件夹",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认安全移动分类文件")
                .setMessage("即将移动 " + selected.size()
                        + " 个文件。会保留原扩展名和 MIME 类型，并执行复制 → SHA-256 校验 → 大小校验 → 删除源文件。分类结果不会触发直接删除。 ")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移动",
                        (dialog, which) -> startMove(selected))
                .show();
    }

    private void startMove(List<TavernFileItem> selected) {
        cancelRequested.set(false);
        setBusy(true, "正在准备安全移动……");
        List<CharacterCard> movable = new ArrayList<>();
        for (TavernFileItem item : selected) movable.add(item.asMovableFile());
        Uri target = targetTreeUri;
        executor.execute(() -> {
            CardMoveManager manager = new CardMoveManager(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardMoveManager.MoveResult result =
                        manager.moveFavorites(movable, target);
                runOnUiThread(() -> showMoveResult(result));
            } catch (CardMoveManager.CancelledException e) {
                runOnUiThread(() -> finishBusy(
                        "移动已取消。已完成文件保持移动，未完成文件仍在原处。 "));
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy(
                        "移动失败：" + safeMessage(e)));
            }
        });
    }

    private void showMoveResult(CardMoveManager.MoveResult result) {
        finishBusy("移动完成：成功 " + result.moved + "，安全跳过 "
                + result.skipped + "，失败 " + result.failed);
        removeCompleted(result.completedSourceUris);
        new AlertDialog.Builder(this)
                .setTitle("分类文件移动结果")
                .setMessage("成功 " + result.moved + "，安全跳过 "
                        + result.skipped + "，失败 " + result.failed
                        + "\n\n" + result.log)
                .setPositiveButton("完成", null)
                .show();
    }

    private void removeCompleted(List<String> uris) {
        if (uris == null || uris.isEmpty()) return;
        Set<String> removed = new HashSet<>(uris);
        allItems.removeIf(item -> removed.contains(item.uri));
        selectedUris.removeAll(removed);
        recalculateCounts();
        applyFilter(filterSpinner.getSelectedItemPosition());
    }

    private void openFullScreen(TavernFileItem item) {
        if (!item.isImageFile()) return;
        Intent intent = new Intent(this, FullScreenImageActivity.class);
        intent.putExtra("image_uri", item.uri);
        startActivity(intent);
    }

    private void clearResults() {
        allItems.clear();
        visibleItems.clear();
        selectedUris.clear();
        resetCounts();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSummary();
        refreshButtons();
    }

    private void recalculateCounts() {
        resetCounts();
        for (TavernFileItem item : allItems) {
            categoryCounts.put(item.category,
                    categoryCounts.get(item.category) + 1);
        }
    }

    private void resetCounts() {
        categoryCounts.clear();
        for (TavernFileItem.Category category : TavernFileItem.Category.values()) {
            categoryCounts.put(category, 0);
        }
    }

    private String[] buildFilterLabels() {
        String[] labels = new String[TavernFileItem.Category.values().length + 1];
        labels[0] = "全部分类（" + allItems.size() + "）";
        for (int i = 0; i < TavernFileItem.Category.values().length; i++) {
            TavernFileItem.Category category = TavernFileItem.Category.values()[i];
            labels[i + 1] = category.label + "（"
                    + categoryCounts.getOrDefault(category, 0) + "）";
        }
        return labels;
    }

    private void updateSummary() {
        if (summaryText == null) return;
        String current = filterSpinner == null || filterSpinner.getSelectedItem() == null
                ? "全部分类" : String.valueOf(filterSpinner.getSelectedItem());
        summaryText.setText("当前筛选：" + current
                + "\n列表 " + visibleItems.size() + " 个，已选择 "
                + selectedUris.size() + " 个。\n分类仅用于理解和移动，不会自动删除文件。 ");
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
        filterSpinner.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && !visibleItems.isEmpty());
        clearButton.setEnabled(!busy && !selectedUris.isEmpty());
        targetButton.setEnabled(!busy && !testMode);
        moveButton.setEnabled(!busy && !testMode && targetTreeUri != null
                && !selectedUris.isEmpty());
        cancelButton.setEnabled(busy);
    }

    private void loadTestItems() {
        addTestItem("role-png", "混合/角色A.png",
                TavernFileItem.Category.CHARACTER_CARD,
                "PNG 角色卡", "检测到可解析 chara", true);
        addTestItem("role-json", "混合/角色B.json",
                TavernFileItem.Category.CHARACTER_CARD,
                "JSON 角色卡", "检测到角色名、人设和开场白", false);
        addTestItem("preset", "混合/Claude预设.json",
                TavernFileItem.Category.PRESET,
                "聊天补全 / OpenAI 类预设", "检测到 prompts 和 prompt_order", false);
        addTestItem("theme", "混合/蓝色主题.json",
                TavernFileItem.Category.BEAUTY,
                "主题 Theme / 美化配置", "检测到颜色和 custom_css", false);
        addTestItem("book", "混合/世界书.json",
                TavernFileItem.Category.WORLD_BOOK,
                "独立世界书", "检测到 entries、keys、content", false);
        addTestItem("regex", "混合/正则.json",
                TavernFileItem.Category.REGEX_SCRIPT,
                "正则脚本集合", "检测到 findRegex 和 replaceString", false);
        addTestItem("image", "混合/背景.jpg",
                TavernFileItem.Category.IMAGE_ASSET,
                "图片素材", "普通背景图片", true);
        addTestItem("zip", "混合/整合包.zip",
                TavernFileItem.Category.MIXED_PACKAGE,
                "ZIP 压缩包", "同时包含 JSON、CSS 和图片", false);
        recalculateCounts();
    }

    private void addTestItem(String id, String path,
                             TavernFileItem.Category category,
                             String subtype, String reason, boolean image) {
        TavernFileItem item = new TavernFileItem();
        item.key = id;
        item.treeUri = "content://classifier-test/tree";
        item.uri = "content://classifier-test/" + id;
        item.parentDocumentId = "parent";
        item.path = path;
        item.fileName = path.substring(path.lastIndexOf('/') + 1);
        item.size = 1024L * (100 + id.length());
        item.modified = 1000L;
        item.category = category;
        item.subtype = subtype;
        item.reason = reason;
        item.details = image ? "可全屏查看" : "结构已读取";
        item.confidence = 95;
        allItems.add(item);
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleItems.size(); }
        @Override public TavernFileItem getItem(int position) {
            return visibleItems.get(position);
        }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(TavernFileClassifierActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(7), dp(7), dp(7), dp(7));
                row.setBackgroundColor(0xfff4f4f4);

                CheckBox check = new CheckBox(TavernFileClassifierActivity.this);
                row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(58)));

                ImageView image = new ImageView(TavernFileClassifierActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(image, new LinearLayout.LayoutParams(dp(82), dp(102)));

                LinearLayout text = new LinearLayout(TavernFileClassifierActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(9), 0, 0, 0);
                TextView type = new TextView(TavernFileClassifierActivity.this);
                type.setTextSize(16);
                type.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView name = new TextView(TavernFileClassifierActivity.this);
                name.setTextSize(14);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView details = new TextView(TavernFileClassifierActivity.this);
                details.setTextSize(12);
                details.setTextIsSelectable(true);
                Button fullScreen = button("全屏查看图片");
                fullScreen.setContentDescription("全屏查看分类图片");
                text.addView(type);
                text.addView(name, marginTop(2));
                text.addView(details, marginTop(3));
                text.addView(fullScreen, marginTop(4));
                row.addView(text, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                holder = new Holder(check, image, type, name, details, fullScreen);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            TavernFileItem item = getItem(position);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selectedUris.contains(item.uri));
            holder.check.setOnCheckedChangeListener((buttonView, checked) ->
                    toggleSelection(item, checked));
            holder.type.setText(item.category.label + " · " + item.subtype);
            holder.name.setText(item.fileName + "\n" + item.path);
            holder.details.setText("大小：" + formatBytes(item.size)
                    + "　可信度：" + item.confidence + "%\n为什么：" + item.reason
                    + "\n详情：" + item.details);
            boolean image = item.isImageFile();
            holder.fullScreen.setVisibility(image ? View.VISIBLE : View.GONE);
            holder.fullScreen.setOnClickListener(v -> openFullScreen(item));
            holder.image.setOnClickListener(image ? v -> openFullScreen(item) : null);
            if (!testMode && image) {
                thumbnailLoader.load(holder.image, item.contentUri(), dp(180));
            } else {
                holder.image.setImageDrawable(null);
                holder.image.setBackgroundColor(image ? 0xffdddddd : 0xffececec);
            }
            return convertView;
        }
    }

    private static final class Holder {
        final CheckBox check;
        final ImageView image;
        final TextView type;
        final TextView name;
        final TextView details;
        final Button fullScreen;

        Holder(CheckBox check, ImageView image, TextView type, TextView name,
               TextView details, Button fullScreen) {
            this.check = check;
            this.image = image;
            this.type = type;
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

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        interface Callback { void onSelected(int position); }
        private final Callback callback;
        SimpleItemSelectedListener(Callback callback) { this.callback = callback; }
        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent,
                                   View view, int position, long id) {
            callback.onSelected(position);
        }
        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
