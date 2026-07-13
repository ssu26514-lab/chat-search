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

public class CardBrowserActivity extends Activity {
    private static final int REQUEST_SOURCE = 3201;
    private static final int REQUEST_TARGET = 3202;

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
    private String lastMoveLog = "";

    private ThumbnailLoader thumbnailLoader;
    private CardAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        buildUi();
        showAllCards();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            if (showingFavorites) refreshFavorites();
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
        description.setText("只分析 PNG 中的人设和全部开场白，不显示世界书。每次进入都要重新选择文件夹并扫描；收藏夹独立保存在应用中，移动成功后自动移除收藏。");
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
        tabs.setGravity(Gravity.CENTER);
        allButton = button("全部卡片");
        favoritesButton = button("收藏夹");
        allButton.setOnClickListener(v -> showAllCards());
        favoritesButton.setOnClickListener(v -> showFavorites());
        tabs.addView(allButton, weighted());
        tabs.addView(favoritesButton, weightedWithMargin());
        root.addView(tabs, marginTop(10));

        LinearLayout moveArea = new LinearLayout(this);
        moveArea.setOrientation(LinearLayout.VERTICAL);
        moveArea.setPadding(0, dp(8), 0, 0);
        targetText = infoBox("收藏移动目标：尚未选择");
        moveArea.addView(targetText, matchWrap());
        chooseTargetButton = button("选择手机目标文件夹");
        chooseTargetButton.setOnClickListener(v -> chooseTargetFolder());
        moveArea.addView(chooseTargetButton, marginTop(6));
        moveButton = button("移动全部收藏到目标文件夹");
        moveButton.setOnClickListener(v -> confirmMoveFavorites());
        moveArea.addView(moveButton, marginTop(6));
        root.addView(moveArea);

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
        statusText.setText("请选择文件夹后扫描。收藏夹可单独打开。 ");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(7), 0, dp(4));
        root.addView(statusText);

        countText = new TextView(this);
        countText.setTextSize(14);
        countText.setTypeface(null, android.graphics.Typeface.BOLD);
        countText.setPadding(0, dp(4), 0, dp(6));
        root.addView(countText);

        listView = new ListView(this);
        adapter = new CardAdapter();
        listView.setAdapter(adapter);
        listView.setDividerHeight(dp(1));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < visibleCards.size()) openCard(visibleCards.get(position));
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshButtons();
    }

    private void chooseSourceFolder() {
        if (busy) return;
        sourceTreeUri = null;
        scannedCards = new ArrayList<>();
        if (!showingFavorites) {
            visibleCards = scannedCards;
            adapter.notifyDataSetChanged();
        }
        CardSession.clearCards();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SOURCE);
    }

    private void chooseTargetFolder() {
        if (busy) return;
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
            if (!showingFavorites) {
                visibleCards = scannedCards;
                adapter.notifyDataSetChanged();
            }
            statusText.setText("文件夹已选择，请点击扫描。");
        } else if (requestCode == REQUEST_TARGET) {
            targetTreeUri = selected;
            targetText.setText("收藏移动目标：\n" + selected);
            statusText.setText("目标文件夹已选择。进入收藏夹后可移动全部收藏。");
        }
        updateCounts();
        refreshButtons();
    }

    private void startScan() {
        if (busy || sourceTreeUri == null) return;
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
                    refreshButtons();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("扫描失败：" + safeMessage(e));
                    sourceTreeUri = null;
                    sourceText.setText("扫描失败，请重新选择 PNG 文件夹");
                    refreshButtons();
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
        if (statusText != null && scannedCards.isEmpty()) {
            statusText.setText("全部卡片列表为空，请重新选择文件夹并扫描。");
        }
        updateCounts();
        refreshButtons();
    }

    private void showFavorites() {
        showingFavorites = true;
        refreshFavorites();
        if (statusText != null) statusText.setText("收藏夹中的卡片可逐张查看，也可选择目标文件夹后批量移动。");
        refreshButtons();
    }

    private void refreshFavorites() {
        visibleCards = FavoriteStore.load(this);
        if (adapter != null) adapter.notifyDataSetChanged();
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
                        + " 张原始 PNG。每张都会先复制到目标文件夹并计算 SHA-256 校验，完全一致后才删除源文件。目标重名会自动添加 (1)、(2)、(3)。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移动", (dialog, which) -> startMoveFavorites(favorites))
                .show();
    }

    private void startMoveFavorites(List<CharacterCard> favorites) {
        if (busy || targetTreeUri == null) return;
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
                    finishBusy("移动已取消。已完成的移动保持不变；收藏夹已保留未完成项目。");
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
        lastMoveLog = "移动时间：" + DateFormat.getDateTimeInstance().format(new Date())
                + "\n成功 " + result.moved + "，跳过 " + result.skipped
                + "，失败 " + result.failed + "\n\n" + result.log;
        refreshFavorites();
        new AlertDialog.Builder(this)
                .setTitle("收藏移动结果")
                .setMessage(lastMoveLog)
                .setPositiveButton("完成", null)
                .show();
    }

    private void updateCounts() {
        if (countText == null) return;
        int favoriteCount = FavoriteStore.load(this).size();
        countText.setText(showingFavorites
                ? "收藏夹：" + visibleCards.size() + " 张"
                : "当前扫描：" + scannedCards.size() + " 张　｜　收藏：" + favoriteCount + " 张");
        if (allButton != null) allButton.setText("全部卡片（" + scannedCards.size() + "）");
        if (favoritesButton != null) favoritesButton.setText("收藏夹（" + favoriteCount + "）");
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
        chooseSourceButton.setEnabled(!busy);
        scanButton.setEnabled(!busy && sourceTreeUri != null);
        allButton.setEnabled(!busy);
        favoritesButton.setEnabled(!busy);
        chooseTargetButton.setEnabled(!busy && showingFavorites);
        moveButton.setEnabled(!busy && showingFavorites
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
                LinearLayout row = new LinearLayout(CardBrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(6), dp(8), dp(6), dp(8));

                ImageView image = new ImageView(CardBrowserActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(image, new LinearLayout.LayoutParams(dp(70), dp(82)));

                LinearLayout textArea = new LinearLayout(CardBrowserActivity.this);
                textArea.setOrientation(LinearLayout.VERTICAL);
                textArea.setPadding(dp(9), 0, dp(6), 0);
                TextView name = new TextView(CardBrowserActivity.this);
                name.setTextSize(16);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView file = new TextView(CardBrowserActivity.this);
                file.setTextSize(12);
                file.setMaxLines(2);
                TextView info = new TextView(CardBrowserActivity.this);
                info.setTextSize(12);
                textArea.addView(name);
                textArea.addView(file);
                textArea.addView(info);
                row.addView(textArea, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button favorite = button("收藏");
                row.addView(favorite, new LinearLayout.LayoutParams(dp(78), dp(48)));
                holder = new RowHolder(image, name, file, info, favorite);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            CharacterCard card = getItem(position);
            holder.name.setText(TextUtils.isEmpty(card.characterName)
                    ? stripExtension(card.fileName) : card.characterName);
            holder.file.setText(card.path);
            if (card.error != null) {
                holder.info.setText("未识别：" + card.error);
            } else {
                holder.info.setText("开场白：" + card.greetingCount() + " 个　｜　点击查看");
            }
            thumbnailLoader.load(holder.image, card.contentUri(), dp(120));
            boolean favorite = FavoriteStore.contains(CardBrowserActivity.this, card.uri);
            holder.favorite.setText(favorite ? "取消" : "收藏");
            holder.favorite.setOnClickListener(v -> toggleFavorite(card));
            return convertView;
        }
    }

    private static final class RowHolder {
        final ImageView image;
        final TextView name;
        final TextView file;
        final TextView info;
        final Button favorite;

        RowHolder(ImageView image, TextView name, TextView file,
                  TextView info, Button favorite) {
            this.image = image;
            this.name = name;
            this.file = file;
            this.info = info;
            this.favorite = favorite;
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
