package com.susu.duplicatecleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SemanticGroupActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private String groupId;
    private SemanticCardScanner.Group group;
    private boolean testMode;
    private boolean busy;

    private TextView headerText;
    private TextView selectedText;
    private TextView statusText;
    private CheckBox confirmationCheck;
    private Button deleteButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private ListView listView;
    private CardAdapter adapter;
    private ThumbnailLoader thumbnailLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        groupId = getIntent().getStringExtra("group_id");
        testMode = getIntent().getBooleanExtra("test_mode", false);
        group = SemanticDuplicateSession.group(groupId);
        if (group == null || group.cards.size() < 2) {
            Toast.makeText(this, "该比较组已失效，请返回重新扫描。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGroup();
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        executor.shutdownNow();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button back = button("← 返回分析结果");
        back.setContentDescription("返回语义查重结果");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        TextView title = new TextView(this);
        title.setText(group.title);
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(4));
        root.addView(title);

        headerText = infoBox("");
        root.addView(headerText, matchWrap());

        selectedText = new TextView(this);
        selectedText.setTextSize(14);
        selectedText.setTypeface(null, android.graphics.Typeface.BOLD);
        selectedText.setPadding(0, dp(8), 0, dp(6));
        root.addView(selectedText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, marginTop(4));

        statusText = new TextView(this);
        statusText.setText("点击任意文件作为保留项或比较基准，再打开详细差异。");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(5), 0, dp(5));
        root.addView(statusText);

        listView = new ListView(this);
        listView.setDividerHeight(dp(7));
        adapter = new CardAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        confirmationCheck = new CheckBox(this);
        confirmationCheck.setText("我已看清封面、兼容性和差异，确认永久删除除所选保留项以外的语义重复副本");
        confirmationCheck.setOnCheckedChangeListener((buttonView, checked) -> refreshButtons());
        root.addView(confirmationCheck, marginTop(7));

        deleteButton = button("重新验证后删除其余语义重复副本");
        deleteButton.setOnClickListener(v -> confirmDelete());
        root.addView(deleteButton, marginTop(5));

        cancelButton = button("取消当前删除任务");
        cancelButton.setOnClickListener(v -> cancelRequested.set(true));
        root.addView(cancelButton, marginTop(5));

        setContentView(root);
        refreshGroup();
    }

    private void refreshGroup() {
        group = SemanticDuplicateSession.group(groupId);
        if (group == null || group.cards.size() < 2) {
            finish();
            return;
        }
        boolean exact = group.type == SemanticCardScanner.GroupType.EXACT_CONTENT;
        headerText.setText(exact
                ? "✓ 这一组的有效内容指纹完全一致。人设、全部开场白、世界书、正则、扩展和其他功能字段相同。\n"
                + "文件仍可能在封面编码、图片像素、chara/ccv3、构建标记、水印或大小上不同。"
                : "⚠ 这一组角色名相同或相近，但有效内容不一致。只能查看差异，不提供一键删除，以免把真正更新版删掉。 ");
        SemanticCardParser.CardRecord keeper = SemanticDuplicateSession.keeper(groupId);
        selectedText.setText((exact ? "当前保留：" : "当前比较基准：")
                + (keeper == null ? "未选择" : keeper.fileName)
                + (keeper == null ? "" : "\n" + SemanticDuplicateSession.recommendationReason(keeper)));
        adapter.notifyDataSetChanged();
        refreshButtons();
    }

    private void selectKeeper(int index) {
        SemanticDuplicateSession.selectKeeper(groupId, index);
        confirmationCheck.setChecked(false);
        refreshGroup();
    }

    private void openDiff(int rightIndex) {
        int leftIndex = SemanticDuplicateSession.keeperIndex(groupId);
        if (rightIndex == leftIndex) {
            int alternative = leftIndex == 0 ? 1 : 0;
            if (alternative >= group.cards.size()) return;
            rightIndex = alternative;
        }
        Intent intent = new Intent(this, SemanticPairDiffActivity.class);
        intent.putExtra("group_id", groupId);
        intent.putExtra("left_index", leftIndex);
        intent.putExtra("right_index", rightIndex);
        intent.putExtra("test_mode", testMode);
        startActivity(intent);
    }

    private void confirmDelete() {
        if (busy || testMode || group.type != SemanticCardScanner.GroupType.EXACT_CONTENT
                || !group.safeDelete || !confirmationCheck.isChecked()) return;
        SemanticCardParser.CardRecord keeper = SemanticDuplicateSession.keeper(groupId);
        if (keeper == null) return;
        new AlertDialog.Builder(this)
                .setTitle("确认永久删除语义重复副本")
                .setMessage("将保留：\n" + keeper.path + "\n\n并尝试删除其余 "
                        + (group.cards.size() - 1)
                        + " 个文件。删除前会重新解析保留项和每个待删文件；只有有效内容指纹仍完全一致才会删除。此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> startDelete(keeper))
                .show();
    }

    private void startDelete(SemanticCardParser.CardRecord keeper) {
        cancelRequested.set(false);
        setBusy(true, "正在重新验证保留文件……");
        SemanticCardScanner.Group currentGroup = group;
        executor.execute(() -> {
            SemanticDeleteManager manager = new SemanticDeleteManager(
                    getContentResolver(), cancelRequested,
                    message -> runOnUiThread(() -> statusText.setText(message)));
            try {
                SemanticDeleteManager.DeleteResult result =
                        manager.deleteNonKeeper(currentGroup, keeper);
                SemanticDuplicateSession.removeDeleted(groupId, result.deletedUris);
                runOnUiThread(() -> showDeleteResult(result));
            } catch (SemanticDeleteManager.CancelledException e) {
                runOnUiThread(() -> {
                    finishBusy("删除已取消。已完成的删除无法撤销，未完成文件仍保留。 ");
                    refreshGroup();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishBusy("删除停止：" + safeMessage(e));
                    refreshGroup();
                });
            }
        });
    }

    private void showDeleteResult(SemanticDeleteManager.DeleteResult result) {
        finishBusy("处理完成：删除 " + result.deleted + "，安全跳过 "
                + result.skipped + "，失败 " + result.failed);
        new AlertDialog.Builder(this)
                .setTitle("语义重复清理结果")
                .setMessage("删除 " + result.deleted + "，安全跳过 "
                        + result.skipped + "，失败 " + result.failed
                        + "\n\n" + result.log)
                .setPositiveButton("完成", (dialog, which) -> refreshGroup())
                .show();
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
        if (deleteButton == null || group == null) return;
        boolean canDelete = !busy && !testMode
                && group.type == SemanticCardScanner.GroupType.EXACT_CONTENT
                && group.safeDelete && confirmationCheck.isChecked();
        confirmationCheck.setVisibility(group.type == SemanticCardScanner.GroupType.EXACT_CONTENT
                ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(group.type == SemanticCardScanner.GroupType.EXACT_CONTENT
                ? View.VISIBLE : View.GONE);
        confirmationCheck.setEnabled(!busy && !testMode && group.safeDelete);
        deleteButton.setEnabled(canDelete);
        cancelButton.setEnabled(busy);
    }

    private final class CardAdapter extends BaseAdapter {
        @Override public int getCount() { return group.cards.size(); }
        @Override public SemanticCardParser.CardRecord getItem(int position) {
            return group.cards.get(position);
        }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(SemanticGroupActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                row.setBackgroundColor(0xfff4f4f4);

                ImageView image = new ImageView(SemanticGroupActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(image, new LinearLayout.LayoutParams(dp(88), dp(112)));

                LinearLayout center = new LinearLayout(SemanticGroupActivity.this);
                center.setOrientation(LinearLayout.VERTICAL);
                center.setPadding(dp(9), 0, dp(7), 0);
                RadioButton radio = new RadioButton(SemanticGroupActivity.this);
                radio.setTextSize(15);
                TextView file = new TextView(SemanticGroupActivity.this);
                file.setTextSize(14);
                file.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView details = new TextView(SemanticGroupActivity.this);
                details.setTextSize(12);
                details.setTextIsSelectable(true);
                Button compare = button("查看详细差异");
                compare.setContentDescription("查看两张角色卡详细差异");
                center.addView(radio);
                center.addView(file);
                center.addView(details, marginTop(3));
                center.addView(compare, marginTop(5));
                row.addView(center, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                holder = new Holder(image, radio, file, details, compare);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (Holder) convertView.getTag();
            }

            SemanticCardParser.CardRecord card = getItem(position);
            boolean selected = position == SemanticDuplicateSession.keeperIndex(groupId);
            holder.radio.setOnCheckedChangeListener(null);
            holder.radio.setChecked(selected);
            holder.radio.setText(group.type == SemanticCardScanner.GroupType.EXACT_CONTENT
                    ? (selected ? "✓ 保留这个文件" : "保留这个文件")
                    : (selected ? "✓ 作为比较基准" : "作为比较基准"));
            holder.radio.setOnClickListener(v -> selectKeeper(position));
            holder.file.setText(card.fileName + "\n" + card.path);
            holder.details.setText("大小：" + formatBytes(card.size)
                    + "　封面：" + card.width + "×" + card.height
                    + "\n兼容性：" + card.compatibilityText()
                    + "\n" + card.componentSummary()
                    + (selected ? "\n推荐说明：" + SemanticDuplicateSession.recommendationReason(card) : ""));
            holder.compare.setOnClickListener(v -> openDiff(position));
            convertView.setOnClickListener(v -> selectKeeper(position));
            if (!testMode) thumbnailLoader.load(holder.image, card.contentUri(), dp(160));
            else holder.image.setImageDrawable(null);
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView image;
        final RadioButton radio;
        final TextView file;
        final TextView details;
        final Button compare;

        Holder(ImageView image, RadioButton radio, TextView file,
               TextView details, Button compare) {
            this.image = image;
            this.radio = radio;
            this.file = file;
            this.details = details;
            this.compare = compare;
        }
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
