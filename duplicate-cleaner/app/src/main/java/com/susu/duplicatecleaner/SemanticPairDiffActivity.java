package com.susu.duplicatecleaner;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SemanticPairDiffActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SemanticCardScanner.Group group;
    private SemanticCardParser.CardRecord leftRecord;
    private SemanticCardParser.CardRecord rightRecord;
    private boolean testMode;

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView verdictText;
    private TextView coverText;
    private TextView personaText;
    private TextView greetingsText;
    private TextView worldbookText;
    private TextView regexText;
    private TextView extensionsText;
    private TextView otherText;
    private TextView packagingText;
    private ThumbnailLoader thumbnailLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String groupId = getIntent().getStringExtra("group_id");
        int leftIndex = getIntent().getIntExtra("left_index", 0);
        int rightIndex = getIntent().getIntExtra("right_index", 1);
        testMode = getIntent().getBooleanExtra("test_mode", false);
        group = SemanticDuplicateSession.group(groupId);
        if (group == null || leftIndex < 0 || rightIndex < 0
                || leftIndex >= group.cards.size() || rightIndex >= group.cards.size()
                || leftIndex == rightIndex) {
            Toast.makeText(this, "比较内容已失效，请返回重新选择。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        leftRecord = group.cards.get(leftIndex);
        rightRecord = group.cards.get(rightIndex);
        thumbnailLoader = new ThumbnailLoader(getContentResolver());
        buildUi();
        loadDiff();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (thumbnailLoader != null) thumbnailLoader.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(root);

        Button back = button("← 返回文件选择");
        back.setContentDescription("返回语义重复组");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        TextView title = new TextView(this);
        title.setText("两张角色卡详细差异");
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(6));
        root.addView(title);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.addView(cardColumn("左侧 / 当前基准", leftRecord), weighted());
        cards.addView(cardColumn("右侧 / 对比文件", rightRecord), weightedMargin());
        root.addView(cards, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        root.addView(progressBar, marginTop(10));

        statusText = new TextView(this);
        statusText.setText("正在重新读取两张角色卡，并逐项分析差异……");
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(5), 0, dp(5));
        root.addView(statusText);

        verdictText = section(root, "最终结论");
        coverText = section(root, "封面逐像素比较");
        personaText = section(root, "人设差异");
        greetingsText = section(root, "开场白差异");
        worldbookText = section(root, "世界书差异");
        regexText = section(root, "正则脚本差异");
        extensionsText = section(root, "扩展功能差异");
        otherText = section(root, "其他有效字段差异");
        packagingText = section(root, "PNG 封装、兼容性与文件差异");

        setContentView(scroll);
    }

    private LinearLayout cardColumn(String label, SemanticCardParser.CardRecord record) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(7), dp(7), dp(7), dp(7));
        column.setBackgroundColor(0xfff3f3f3);

        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextSize(15);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        column.addView(heading);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));
        if (!testMode) thumbnailLoader.load(image, record.contentUri(), dp(420));

        TextView info = new TextView(this);
        info.setText(record.fileName + "\n" + formatBytes(record.size)
                + " · " + record.width + "×" + record.height
                + "\n" + record.compatibilityText()
                + "\n" + record.componentSummary()
                + "\n路径：" + record.path);
        info.setTextSize(12);
        info.setTextIsSelectable(true);
        info.setPadding(0, dp(5), 0, 0);
        column.addView(info);
        return column;
    }

    private TextView section(LinearLayout root, String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(heading, marginTop(13));

        TextView content = new TextView(this);
        content.setText("等待分析……");
        content.setTextSize(14);
        content.setTextIsSelectable(true);
        content.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.setBackgroundColor(0xfff4f4f4);
        root.addView(content, marginTop(5));
        return content;
    }

    private void loadDiff() {
        executor.execute(() -> {
            try {
                SemanticCardParser.ParsedPayload left = parseRecord(leftRecord);
                SemanticCardParser.ParsedPayload right = parseRecord(rightRecord);
                SemanticDiffEngine.DiffReport report = SemanticDiffEngine.compare(left, right);
                SemanticCardParser.CoverComparison cover = testMode
                        ? new SemanticCardParser.CoverComparison("测试模式未读取真实封面", false)
                        : SemanticCardParser.compareCoverPixels(getContentResolver(),
                        leftRecord.contentUri(), rightRecord.contentUri());
                runOnUiThread(() -> showReport(report, cover));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("差异分析失败：" + safeMessage(e));
                });
            }
        });
    }

    private SemanticCardParser.ParsedPayload parseRecord(
            SemanticCardParser.CardRecord record) throws Exception {
        JSONObject testPayload = SemanticDuplicateSession.testPayload(record.uri);
        if (testPayload != null) {
            return SemanticCardParser.fromJsonForTest(record, testPayload);
        }
        return SemanticCardParser.parse(getContentResolver(), record);
    }

    private void showReport(SemanticDiffEngine.DiffReport report,
                            SemanticCardParser.CoverComparison cover) {
        progressBar.setVisibility(View.GONE);
        statusText.setText("分析完成。绿色“相同”代表不会影响导入后的实际使用；差异项会直接指出新增、缺少或改变的位置。 ");
        verdictText.setText(report.verdict);
        coverText.setText(cover.status);
        personaText.setText(report.persona);
        greetingsText.setText(report.greetings);
        worldbookText.setText(report.worldbook);
        regexText.setText(report.regex);
        extensionsText.setText(report.extensions);
        otherText.setText(report.other);
        packagingText.setText(report.packaging);
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
        params.leftMargin = dp(7);
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
