package com.susu.duplicatecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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

public class CardBrowserV2Activity extends Activity {
    private static final int REQUEST_SOURCE = 4201;
    private static final int REQUEST_TARGET = 4202;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private Button chooseSourceButton;
    private Button scanButton;
    private Button allButton;
    private Button favoritesButton;
    private Button chooseTargetButton;
    private Button moveButton;
    private Button cancelButton;
    private TextView sourceText;
    private TextView targetText;
    private TextView statusText;
    private TextView countText;
    private ProgressBar progressBar;
    private ListView listView;

    private Uri sourceTreeUri;
    private Uri targetTreeUri;
    private List<CharacterCard> scannedCards = new ArrayList<>();
    private List<CharacterCard> visibleCards = new ArrayList<>();
    private boolean showingFavorites;
    private boolean busy;
    private boolean testMode;

    private ThumbnailLoader thumbnailLoader;
    private CardAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        buildUi();
        if (testMode) {
            loadTestCard();
        } else {
            showAllCards();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            if (showingFavorites && !testMode) refreshFavorites();
            else adapter.notifyDataSetChanged();
            updateCounts();
        }
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        thumbnailLoader.shutdown();
        CardSession.clearCards();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("角色卡浏览与收藏");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("只分析 PNG 中的 CHAR 人设和全部开场白，不显示世界书。每张卡都有独立“查看”按钮；图片和文字区域也可点开。");
        description.setTextSize(14);
        description.setPadding(0, dp(6), 0, dp(10));
        root.addView(description);

        sourceText = infoBox("尚未选择 PNG 文件夹");
        root.addView(sourceText, matchWrap());

        chooseSourceButton = button("重新选择 PNG 文件夹");
        chooseSourceButton.setOnClickListener(v -> chooseSourceFolder());
        root.addView(chooseSourceButton, marginTop(8));

        scanButton = button("扫描并按文件名排序");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton, marginTop(6));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        allButton = button("全部卡片");
        favoritesButton = button("收藏夹");
        allButton.setOnClickListener(v -> showAllCards());
        favoritesButton.setOnClickListener(v -> showFavorites());
        tabs.addView(allButton, weighted());
        tabs.addView(favoritesButton, weightedWithMargin());
        root.addView(tabs, marginTop(8));

        targetText = infoBox("收藏移动目标：尚未选择");
        root.addView(targetText, marginTop(8));

        chooseTargetButton = button("选择手机目标文件夹");
        chooseTargetButton.setOnClickListener(v -> chooseTargetFolder());
        root.addView(chooseTargetButton, marginTop(6));

        moveButton = button("移动全部收藏到目标文件夹");
        moveButton.setOnClickListener(v -> confirmMoveFavorites());
        root.addView(moveButton, marginTop(6));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(8));

        cancelButton = button("取消当前任务");
        cancelButton.setOnClickListener(v -> {
            cancelRequested.set(true);
            statusText.setText("正在取消当前任务……");
        });
        root.addView(cancelButton, marginTop(6));

        statusText = new TextView(this);
        statusText.setText("请选择文件夹后扫描。收藏夹可单独打开。");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(7), 0, dp(4));
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextSize(14);
        countText.setTypeface(null, android.graphics.Typeface.BOLD);
        countText.setPadding(0, dp(4), 0, dp(6));
        root.addView(countText);

        listView = new ListView(this);
        listView.setItemsCanFocus(true);
        listView.setDividerHeight(dp(1));
        adapter = new CardAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshButtons();
    }

    private void loadTestCard() {
        CharacterCard card = new CharacterCard();
        card.key = "test-card";
        card.uri = "content://com.susu.duplicatecleaner.test/card.png";
        card.treeUri = "content://com.susu.duplicatecleaner.test/tree";
        card.parentDocumentId = "test-parent";
        card.path = "测试角色.png";
        card.fileName = "测试角色.png";
        card.characterName = "测试角色";
        card.persona = "【角色描述】\n这是自动化测试使用的人设内容。";
        card.greetings.add("这是第一条测试开场白。 ");
        card.greetings.add("这是第二条测试开场白。 ");
        scannedCards = new ArrayList<>();
        scannedCards.add(card);
        visibleCards = scannedCards;
        CardSession.replace(scannedCards);
        adapter.notifyDataSetChanged();
        statusText.setText("自动化测试卡片已载入");
        updateCounts();
        refreshButtons();
    }

    private void chooseSourceFolder() {
        if (busy || testMode) return;
        sourceTreeUri = null;
        scannedCards = new ArrayList<>();
        visibleCards = showingFavorites ? FavoriteStore.load(this) : scannedCards;
        adapter.notifyDataSetChanged();
        CardSession.clearCards();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SOURCE);
    }

    private void chooseTargetFolder() {
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
        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (Exception ignored) {
        }

        if (requestCode == REQUEST_SOURCE) {
            sourceTreeUri = selected;
            sourceText.setText("本次 PNG 文件夹：\n" + selected);
            scannedCards = new ArrayList<>();
            if (!showingFavorites) visibleCards = scannedCards;
            adapter.notifyDataSetChanged();
            statusText.setText("文件夹已选择，请点击扫描。");
        } else if (requestCode == REQUEST_TARGET) {
            targetTreeUri = selected;
            targetText.setText("收藏移动目标：\n" + selected);
            statusText.setText("目标文件夹已选择，可进入收藏夹移动全部收藏。");
        }
        updateCounts();
        refreshButtons();
    }

    private void startScan() {
        if (busy || sourceTreeUri == null || testMode) return;
        cancelRequested.set(false);
        scannedCards = new ArrayList<>();
        CardSession.clearCards();
        setBusy(true, "正在枚举 PNG 文件……");
        Uri selected = sourceTreeUri;

        executor.execute(() -> {
            CharacterCardReader reader = new CharacterCardReader(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CharacterCardReader.ScanResult result = reader.scan(selected);
                runOnUiThread(() -> showScanResult(result));
            } catch (CharacterCardReader.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("扫描已取消。再次扫描前请重新选择文件夹。");
                    sourceTreeUri = null;
                    sourceText.setText("扫描已取消，请重新选择 PNG 文件夹");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("扫描失败：" + safeMessage(e));
                    sourceTreeUri = null;
                    sourceText.setText("扫描失败，请重新选择 PNG 文件夹");
                });
            }
        });
    }

    private void showScanResult(CharacterCardReader.ScanResult result) {
        scannedCards = result.cards;
        CardSession.replace(scannedCards);
        finishBusy(String.format(Locale.CHINA,
                "扫描完成：%d 张 PNG，成功 %d，未识别 %d，耗时 %.1f 秒",
                result.totalPng, result.success, result.failed, result.elapsedMs / 1000.0));
        showAllCards();
    }

    private void showAllCards() {
        showingFavorites = false;
        visibleCards = scannedCards;
        if (adapter != null) adapter.notifyDataSetChanged();
        if (statusText != null && scannedCards.isEmpty() && !testMode) {
            statusText.setText("全部卡片列表为空，请重新选择文件夹并扫描。");
        }
        updateCounts();
        refreshButtons();
    }

    private void showFavorites() {
        if (testMode) return;
        showingFavorites = true;
        refreshFavorites();
        statusText.setText("收藏夹中的卡片可逐张查看，也可选择目标文件夹后批量移动。");
        refreshButtons();
    }

    private void refreshFavorites() {
        visibleCards = FavoriteStore.load(this);
        adapter.notifyDataSetChanged();
        updateCounts();
    }

    private void openCard(CharacterCard card) {
        if (busy) return;
        if (card.error != null) {
            Toast.makeText(this, "这张卡未识别：" + card.error, Toast.LENGTH_LONG).show();
            return;
        }
        if (card.persona != null) {
            CardSession.put(card);
            launchDetail(card.key);
            return;
        }

        cancelRequested.set(false);
        setBusy(true, "正在重新读取收藏卡片……");
        executor.execute(() -> {
            CharacterCardReader reader = new CharacterCardReader(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CharacterCard loaded = reader.reload(card);
                CardSession.put(loaded);
                runOnUiThread(() -> {
                    finishBusy("卡片读取完成。");
                    launchDetail(loaded.key);
                });
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy("无法打开收藏卡片：" + safeMessage(e)));
            }
        });
    }

    private void launchDetail(String key) {
        Intent intent = new Intent(this, CardDetailActivity.class);
        intent.putExtra("card_key", key);
        startActivity(intent);
    }

    private void toggleFavorite(CharacterCard card) {
        if (testMode) return;
        if (FavoriteStore.contains(this, card.uri)) {
            FavoriteStore.remove(this, card.uri);
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
        } else {
            FavoriteStore.add(this, card);
            Toast.makeText(this, "已暂存到收藏夹", Toast.LENGTH_SHORT).show();
        }
        if (showingFavorites) refreshFavorites();
        else adapter.notifyDataSetChanged();
        updateCounts();
        refreshButtons();
    }

    private void confirmMoveFavorites() {
        if (testMode) return;
        List<CharacterCard> favorites = FavoriteStore.load(this);
        if (favorites.isEmpty()) {
            Toast.makeText(this, "收藏夹是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        if (targetTreeUri == null) {
            Toast.makeText(this, "请先选择手机目标文件夹", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移动全部收藏")
                .setMessage("即将移动 " + favorites.size()
                        + " 张原始 PNG。每张会先复制并计算 SHA-256；目标与源文件完全一致后才删除源文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移动", (dialog, which) -> startMoveFavorites(favorites))
                .show();
    }

    private void startMoveFavorites(List<CharacterCard> favorites) {
        if (busy || targetTreeUri == null || testMode) return;
        cancelRequested.set(false);
        setBusy(true, "正在准备移动收藏……");
        Uri target = targetTreeUri;
        executor.execute(() -> {
            CardMoveManager manager = new CardMoveManager(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardMoveManager.MoveResult result = manager.moveFavorites(favorites, target);
                FavoriteStore.removeMany(this, result.completedSourceUris);
                runOnUiThread(() -> showMoveResult(result));
            } catch (CardMoveManager.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("移动已取消。已完成的移动保持不变，未完成项目仍在收藏夹。");
                    refreshFavorites();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("移动失败：" + safeMessage(e));
                    refreshFavorites();
                });
            }
        });
    }

    private void showMoveResult(CardMoveManager.MoveResult result) {
        finishBusy("移动完成：成功 " + result.moved + "，跳过 "
                + result.skipped + "，失败 " + result.failed);
        String log = "移动时间：" + DateFormat.getDateTimeInstance().format(new Date())
                + "\n成功 " + result.moved + "，跳过 " + result.skipped
                + "，失败 " + result.failed + "\n\n" + result.log;
        refreshFavorites();
        new AlertDialog.Builder(this)
                .setTitle("收藏移动结果")
                .setMessage(log)
                .setPositiveButton("完成", null)
                .show();
    }

    private void updateCounts() {
        if (countText == null) return;
        int favoriteCount = testMode ? 0 : FavoriteStore.load(this).size();
        countText.setText(showingFavorites
                ? "收藏夹：" + visibleCards.size() + " 张"
                : "当前扫描：" + scannedCards.size() + " 张　｜　收藏：" + favoriteCount + " 张");
        allButton.setText("全部卡片（" + scannedCards.size() + "）");
        favoritesButton.setText("收藏夹（" + favoriteCount + "）");
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
        if (chooseSourceButton == null) return;
        chooseSourceButton.setEnabled(!busy && !testMode);
        scanButton.setEnabled(!busy && !testMode && sourceTreeUri != null);
        allButton.setEnabled(!busy);
        favoritesButton.setEnabled(!busy && !testMode);
        chooseTargetButton.setEnabled(!busy && !testMode && showingFavorites);
        moveButton.setEnabled(!busy && !testMode && showingFavorites
                && targetTreeUri != null && !FavoriteStore.load(this).isEmpty());
        cancelButton.setEnabled(busy);
    }

    private final class CardAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleCards.size();
        }

        @Override
        public CharacterCard getItem(int position) {
            return visibleCards.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(CardBrowserV2Activity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(6), dp(8), dp(6), dp(8));
                row.setClickable(true);

                ImageView image = new ImageView(CardBrowserV2Activity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setClickable(true);
                row.addView(image, new LinearLayout.LayoutParams(dp(70), dp(82)));

                LinearLayout textArea = new LinearLayout(CardBrowserV2Activity.this);
                textArea.setOrientation(LinearLayout.VERTICAL);
                textArea.setPadding(dp(9), 0, dp(6), 0);
                textArea.setClickable(true);
                TextView name = new TextView(CardBrowserV2Activity.this);
                name.setTextSize(16);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView file = new TextView(CardBrowserV2Activity.this);
                file.setTextSize(12);
                file.setMaxLines(2);
                TextView info = new TextView(CardBrowserV2Activity.this);
                info.setTextSize(12);
                textArea.addView(name);
                textArea.addView(file);
                textArea.addView(info);
                row.addView(textArea, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout actions = new LinearLayout(CardBrowserV2Activity.this);
                actions.setOrientation(LinearLayout.VERTICAL);
                Button viewButton = button("查看");
                viewButton.setContentDescription("查看角色卡");
                Button favoriteButton = button("收藏");
                actions.addView(viewButton, new LinearLayout.LayoutParams(dp(80), dp(48)));
                LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(80), dp(48));
                favoriteParams.topMargin = dp(4);
                actions.addView(favoriteButton, favoriteParams);
                row.addView(actions);

                holder = new RowHolder(row, image, textArea, name, file, info,
                        viewButton, favoriteButton);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            CharacterCard card = getItem(position);
            holder.name.setText(TextUtils.isEmpty(card.characterName)
                    ? stripExtension(card.fileName) : card.characterName);
            holder.file.setText(card.path);
            holder.info.setText(card.error != null
                    ? "未识别：" + card.error
                    : "开场白：" + card.greetingCount() + " 个");

            if (!testMode) thumbnailLoader.load(holder.image, card.contentUri(), dp(120));
            else holder.image.setImageDrawable(null);

            View.OnClickListener openListener = v -> openCard(card);
            holder.row.setOnClickListener(openListener);
            holder.image.setOnClickListener(openListener);
            holder.textArea.setOnClickListener(openListener);
            holder.name.setOnClickListener(openListener);
            holder.file.setOnClickListener(openListener);
            holder.info.setOnClickListener(openListener);
            holder.viewButton.setOnClickListener(openListener);

            boolean favorite = !testMode && FavoriteStore.contains(CardBrowserV2Activity.this, card.uri);
            holder.favoriteButton.setText(favorite ? "取消" : "收藏");
            holder.favoriteButton.setEnabled(!testMode);
            holder.favoriteButton.setOnClickListener(v -> toggleFavorite(card));
            return convertView;
        }
    }

    private static final class RowHolder {
        final LinearLayout row;
        final ImageView image;
        final LinearLayout textArea;
        final TextView name;
        final TextView file;
        final TextView info;
        final Button viewButton;
        final Button favoriteButton;

        RowHolder(LinearLayout row, ImageView image, LinearLayout textArea,
                  TextView name, TextView file, TextView info,
                  Button viewButton, Button favoriteButton) {
            this.row = row;
            this.image = image;
            this.textArea = textArea;
            this.name = name;
            this.file = file;
            this.info = info;
            this.viewButton = viewButton;
            this.favoriteButton = favoriteButton;
        }
    }

    private TextView infoBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(9), dp(10), dp(9));
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

    private LinearLayout.LayoutParams weightedWithMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String stripExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : String.valueOf(name);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
