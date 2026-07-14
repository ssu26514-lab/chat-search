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
        note.setText("六个功能完全独立，不会同时运行。PNG 与 JSON 角色卡都能参与浏览、改名、严格查重和有效内容查重；每次进入功能都要重新选择文件夹并扫描。 ");
        note.setTextSize(15);
        note.setPadding(0, dp(10), 0, dp(24));
        root.addView(note);

        addFeature(root, "重复文件清理",
                "扫描文件夹内所有文件，包括 PNG 与 JSON；只删除字节级完全一致的副本。",
                ToolSession.Mode.DUPLICATE);

        addFeature(root, "角色卡有效内容查重",
                "同时比较 PNG 与 JSON 角色卡。忽略封装和水印差异，展示人设、开场白、世界书、正则和扩展的真实差异。",
                ToolSession.Mode.SEMANTIC_DUPLICATE);

        addFeature(root, "无角色卡内容 PNG / JSON 筛选",
                "普通 PNG 可移动或删除；非角色卡 JSON 和损坏 PNG / JSON 只能移动，避免误删预设、美化或世界书。",
                ToolSession.Mode.PLAIN_PNG_CLEANUP);

        addFeature(root, "酒馆文件分类整理",
                "区分角色卡、预设、主题美化、世界书、正则、插件、图片素材和混合包，并说明为什么这样分类。分类后只允许安全移动。",
                ToolSession.Mode.FILE_CLASSIFIER);

        addFeature(root, "角色卡自动改名",
                "读取 PNG / JSON 卡片内部角色名，只修改手机中原文件名；重名自动追加编号，不改卡片内容。",
                ToolSession.Mode.RENAME);

        addFeature(root, "角色卡浏览与收藏",
                "按文件名排序浏览 PNG 与 JSON 角色卡，只看 CHAR 人设和全部开场白；支持收藏和安全移动。",
                ToolSession.Mode.BROWSER);

        setContentView(scroll);
    }

    private void addFeature(LinearLayout root, String title, String description,
                            ToolSession.Mode mode) {
        Button button = button(title);
        button.setOnClickListener(v -> openFeature(mode));
        root.addView(button, marginTop(0));

        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextSize(13);
        desc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(desc);
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
        } else if (mode == ToolSession.Mode.FILE_CLASSIFIER) {
            target = TavernFileClassifierActivity.class;
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
