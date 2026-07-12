package com.susu.duplicatecleaner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("角色卡文件工具");
        title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("两个功能完全独立，不会同时运行。进入任意功能后都需要重新选择文件夹并重新扫描。");
        note.setTextSize(15);
        note.setPadding(0, dp(10), 0, dp(24));
        root.addView(note);

        Button duplicateButton = button("重复文件清理");
        duplicateButton.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(duplicateButton, marginTop(0));

        TextView duplicateDesc = new TextView(this);
        duplicateDesc.setText("寻找字节级完全一致的副本，每组保留 1 份后批量删除。");
        duplicateDesc.setTextSize(13);
        duplicateDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(duplicateDesc);

        Button renameButton = button("角色卡自动改名");
        renameButton.setOnClickListener(v -> startActivity(new Intent(this, CardRenamerActivity.class)));
        root.addView(renameButton, marginTop(0));

        TextView renameDesc = new TextView(this);
        renameDesc.setText("读取 PNG / JSON 角色卡中的角色名，直接修改手机里的实际文件名。重名自动追加 (1)、(2)、(3)。");
        renameDesc.setTextSize(13);
        renameDesc.setPadding(dp(6), dp(8), dp(6), dp(18));
        root.addView(renameDesc);

        setContentView(root);
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
