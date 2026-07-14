package com.susu.duplicatecleaner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class HomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("角色卡文件工具");
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("五个功能完全独立，不会同时运行。进入任意功能后都需要重新选择文件夹并重新扫描；浏览功能的收藏夹会单独暂存在应用中，直到移动或取消收藏。");
        note.setTextSize(15);
        note.setPadding(0, dp(10), 0, dp(24));
        root.addView(note);

        Button duplicateButton = button("重复文件清理");
        duplicateButton.setOnClickListener(v -> openFeature(ToolSession.Mode.DUPLICATE));
        root.addView(duplicateButton, marginTop(0));

        TextView duplicateDesc = new TextView(this);
        duplicateDesc.setText("寻找字节级完全一致的副本，每组保留 1 份后批量删除。不会复用上次文件夹或扫描结果。");
        duplicateDesc.setTextSize(13);
        duplicateDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(duplicateDesc);

        Button semanticButton = button("角色卡有效内容查重");
        semanticButton.setOnClickListener(v -> openFeature(
                ToolSession.Mode.SEMANTIC_DUPLICATE));
        root.addView(semanticButton, marginTop(0));

        TextView semanticDesc = new TextView(this);
        semanticDesc.setText("识别“文件不同但导入后内容一样”的角色卡。展示封面、大小、chara/ccv3 兼容性，以及人设、开场白、世界书、正则和扩展差异；有效内容完全一致时可明确选择保留一个。 ");
        semanticDesc.setTextSize(13);
        semanticDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(semanticDesc);

        Button plainPngButton = button("无角色卡内容 PNG 筛选");
        plainPngButton.setOnClickListener(v -> openFeature(
                ToolSession.Mode.PLAIN_PNG_CLEANUP));
        root.addView(plainPngButton, marginTop(0));

        TextView plainPngDesc = new TextView(this);
        plainPngDesc.setText("筛出角色卡文件夹里的普通 PNG 和疑似损坏文件。普通图片可移动或删除；有 chara/ccv3 但解析失败、或 PNG 无法完整读取的文件只能移动到待修复文件夹。 ");
        plainPngDesc.setTextSize(13);
        plainPngDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(plainPngDesc);

        Button renameButton = button("角色卡自动改名");
        renameButton.setOnClickListener(v -> openFeature(ToolSession.Mode.RENAME));
        root.addView(renameButton, marginTop(0));

        TextView renameDesc = new TextView(this);
        renameDesc.setText("读取 PNG / JSON 卡片内部角色名，只修改手机中原文件的文件名。重名自动追加 (1)、(2)、(3)，不修改卡片内容，不生成新文件。 ");
        renameDesc.setTextSize(13);
        renameDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(renameDesc);

        Button browserButton = button("角色卡浏览与收藏");
        browserButton.setOnClickListener(v -> openFeature(ToolSession.Mode.BROWSER));
        root.addView(browserButton, marginTop(0));

        TextView browserDesc = new TextView(this);
        browserDesc.setText("按文件名排序浏览 PNG，只看 CHAR 人设和全部开场白；开场白左右滑动切换，可全屏查看。满意的卡片先收藏，最后集中移动到手机文件夹。 ");
        browserDesc.setTextSize(13);
        browserDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(browserDesc);

        setContentView(scroll);
    }

    private void openFeature(ToolSession.Mode mode) {
        ToolSession.Mode active = ToolSession.activeMode();
        if (active != null && active != mode) {
            Toast.makeText(this, "另一个功能仍在运行，请先退出后再使用。",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Class<?> target;
        if (mode == ToolSession.Mode.DUPLICATE) {
            target = MainActivity.class;
        } else if (mode == ToolSession.Mode.SEMANTIC_DUPLICATE) {
            target = SemanticDuplicateActivity.class;
        } else if (mode == ToolSession.Mode.PLAIN_PNG_CLEANUP) {
            target = PlainPngCleanupActivity.class;
        } else if (mode == ToolSession.Mode.RENAME) {
            target = GuardedCardRenamerActivity.class;
        } else {
            target = GuardedCardBrowserActivity.class;
        }
        startActivity(new Intent(this, target));
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(17);
        return button;
    }

    private LinearLayout.LayoutParams marginTop(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
