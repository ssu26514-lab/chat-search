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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FavoriteMoveActivity extends Activity {
    private static final int REQUEST_TARGET = 6201;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final List<CharacterCard> cards = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();

    private Uri targetTreeUri;
    private TextView summaryText;
    private TextView targetText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button selectAllButton;
    private Button clearButton;
    private Button targetButton;
    private Button moveButton;
    private Button cancelButton;
    private MoveAdapter adapter;
    private ThumbnailLoader thumbnailLoader;
    private boolean busy;
    private boolean testMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        if (testMode) loadTestCards();
        else cards.addAll(FavoriteStore.load(this));
        for (CharacterCard card : cards) selectedUris.add(card.uri);
        buildUi();
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        thumbnailLoader.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button back = button("← 返回收藏夹");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        TextView title = new TextView(this);
        title.setText("收藏移动管理");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(4));
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("这里使用完整大列表查看待移动卡片。默认全选，也可以逐张取消；移动仍会先复制、计算 SHA-256 校验，确认一致后才删除源文件。");
        note.setTextSize(14);
        note.setPadding(0, 0, 0, dp(8));
        root.addView(note);

        summaryText = infoBox("");
        root.addView(summaryText, matchWrap());

        LinearLayout selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选");
        clearButton = button("全部取消");
        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        selectionBar.addView(selectAllButton, weighted());
        selectionBar.addView(clearButton, weightedMargin());
        root.addView(selectionBar, marginTop(7));

        targetText = infoBox("目标文件夹：尚未选择");
        root.addView(targetText, marginTop(7));

        targetButton = button("选择手机目标文件夹");
        targetButton.setOnClickListener(v -> chooseTarget());
        root.addView(targetButton, marginTop(6));

        moveButton = button("移动已选择的收藏卡片");
        moveButton.setOnClickListener(v -> confirmMove());
        root.addView(moveButton, marginTop(6));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(6));

        cancelButton = button("取消当前移动任务");
        cancelButton.setOnClickListener(v -> cancelRequested.set(true));
        root.addView(cancelButton, marginTop(5));

        statusText = new TextView(this);
        statusText.setText(cards.isEmpty() ? "收藏夹为空。" : "请完整查看列表后选择目标文件夹。");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusText);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        adapter = new MoveAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateSummary();
        refreshButtons();
    }

    private void loadTestCards() {
        for (int i = 1; i <= 200; i++) {
            CharacterCard card = new CharacterCard();
            card.key = "move-test-" + i;
            card.uri = "content://test/move/" + i + ".png";
            card.treeUri = "content://test/tree";
            card.parentDocumentId = "parent";
            card.path = "收藏/测试角色" + i + ".png";
            card.fileName = "测试角色" + i + ".png";
            card.characterName = "测试角色 " + i;
            cards.add(card);
        }
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
        if (requestCode != REQUEST_TARGET || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        targetTreeUri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(targetTreeUri, flags);
        } catch (Exception ignored) {
        }
        targetText.setText("目标文件夹：\n" + targetTreeUri);
        statusText.setText("目标文件夹已选择，可以开始移动。");
        refreshButtons();
    }

    private void selectAll() {
        selectedUris.clear();
        for (CharacterCard card : cards) selectedUris.add(card.uri);
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

    private void toggleSelection(CharacterCard card, boolean selected) {
        if (selected) selectedUris.add(card.uri);
        else selectedUris.remove(card.uri);
        updateSummary();
        refreshButtons();
    }

    private void confirmMove() {
        if (busy || testMode) return;
        List<CharacterCard> selected = selectedCards();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一张卡片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (targetTreeUri == null) {
            Toast.makeText(this, "请先选择目标文件夹", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认移动收藏")
                .setMessage("即将移动 " + selected.size()
                        + " 张原始 PNG。目标文件校验完全一致后才删除源文件；移动成功后，收藏夹和原扫描列表会同步移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移动", (dialog, which) -> startMove(selected))
                .show();
    }

    private void startMove(List<CharacterCard> selected) {
        cancelRequested.set(false);
        setBusy(true, "正在准备移动……");
        Uri target = targetTreeUri;
        executor.execute(() -> {
            CardMoveManager manager = new CardMoveManager(getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                CardMoveManager.MoveResult result = manager.moveFavorites(selected, target);
                FavoriteStore.removeMany(this, result.completedSourceUris);
                runOnUiThread(() -> showMoveResult(result));
            } catch (CardMoveManager.CancelledException e) {
                runOnUiThread(() -> finishBusy("移动已取消。已完成的项目保持移动，未完成项目仍保留。"));
            } catch (Exception e) {
                runOnUiThread(() -> finishBusy("移动失败：" + safeMessage(e)));
            }
        });
    }

    private void showMoveResult(CardMoveManager.MoveResult result) {
        finishBusy("移动完成：成功 " + result.moved + "，跳过 "
                + result.skipped + "，失败 " + result.failed);
        if (!result.completedSourceUris.isEmpty()) {
            Set<String> moved = new HashSet<>(result.completedSourceUris);
            cards.removeIf(card -> moved.contains(card.uri));
            selectedUris.removeAll(moved);
            adapter.notifyDataSetChanged();
            Intent data = new Intent();
            data.putStringArrayListExtra("moved_uris", new ArrayList<>(result.completedSourceUris));
            setResult(RESULT_OK, data);
        }
        updateSummary();
        refreshButtons();
        new AlertDialog.Builder(this)
                .setTitle("移动结果")
                .setMessage("成功 " + result.moved + "，安全跳过 " + result.skipped
                        + "，失败 " + result.failed + "\n\n" + result.log)
                .setPositiveButton("继续查看", null)
                .show();
    }

    private List<CharacterCard> selectedCards() {
        List<CharacterCard> selected = new ArrayList<>();
        for (CharacterCard card : cards) {
            if (selectedUris.contains(card.uri)) selected.add(card);
        }
        return selected;
    }

    private void updateSummary() {
        if (summaryText != null) {
            summaryText.setText("收藏共 " + cards.size() + " 张　｜　已选择 "
                    + selectedUris.size() + " 张\n列表区域占满剩余页面，可一直向下滚动查看全部卡片。 ");
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
        if (selectAllButton == null) return;
        selectAllButton.setEnabled(!busy && !cards.isEmpty());
        clearButton.setEnabled(!busy && !selectedUris.isEmpty());
        targetButton.setEnabled(!busy && !testMode);
        moveButton.setEnabled(!busy && !testMode && targetTreeUri != null && !selectedUris.isEmpty());
        cancelButton.setEnabled(busy);
    }

    private final class MoveAdapter extends BaseAdapter {
        @Override public int getCount() { return cards.size(); }
        @Override public CharacterCard getItem(int position) { return cards.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(FavoriteMoveActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                row.setBackgroundColor(0xfff5f5f5);

                CheckBox check = new CheckBox(FavoriteMoveActivity.this);
                row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(56)));

                ImageView image = new ImageView(FavoriteMoveActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(image, new LinearLayout.LayoutParams(dp(82), dp(96)));

                LinearLayout text = new LinearLayout(FavoriteMoveActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(10), 0, 0, 0);
                TextView name = new TextView(FavoriteMoveActivity.this);
                name.setTextSize(17);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView path = new TextView(FavoriteMoveActivity.this);
                path.setTextSize(13);
                path.setTextIsSelectable(true);
                path.setMaxLines(3);
                text.addView(name);
                text.addView(path, marginTop(4));
                row.addView(text, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                holder = new Holder(check, image, name, path);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            CharacterCard card = getItem(position);
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selectedUris.contains(card.uri));
            holder.check.setOnCheckedChangeListener((buttonView, checked) -> toggleSelection(card, checked));
            holder.name.setText(card.characterName == null || card.characterName.isEmpty()
                    ? card.fileName : card.characterName);
            holder.path.setText(card.path);
            if (!testMode) thumbnailLoader.load(holder.image, card.contentUri(), dp(140));
            else holder.image.setImageDrawable(null);
            convertView.setOnClickListener(v -> holder.check.setChecked(!holder.check.isChecked()));
            return convertView;
        }
    }

    private static final class Holder {
        final CheckBox check;
        final ImageView image;
        final TextView name;
        final TextView path;
        Holder(CheckBox check, ImageView image, TextView name, TextView path) {
            this.check = check;
            this.image = image;
            this.name = name;
            this.path = path;
        }
    }

    private TextView infoBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackgroundColor(0xfff0f0f0);
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }
}
