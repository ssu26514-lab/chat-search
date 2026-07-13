package com.susu.duplicatecleaner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CardDetailActivity extends Activity {
    private CharacterCard card;
    private int greetingIndex;
    private TextView greetingTitle;
    private TextView greetingText;
    private Button favoriteButton;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();

        String key = getIntent().getStringExtra("card_key");
        card = CardSession.get(key);
        if (card == null) {
            Toast.makeText(this, "卡片内容已失效，请重新扫描或从收藏夹打开。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        buildUi();
        showGreeting(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        updateFavoriteButton();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(10), dp(10), dp(10));

        Button backButton = button("← 返回卡片列表");
        backButton.setContentDescription("返回卡片列表");
        backButton.setOnClickListener(v -> finish());
        page.addView(backButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(8), dp(4), dp(24));
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = new TextView(this);
        title.setText(card.characterName);
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView file = new TextView(this);
        file.setText(card.path);
        file.setTextSize(12);
        file.setTextIsSelectable(true);
        file.setPadding(0, dp(4), 0, dp(10));
        root.addView(file);

        ImageView image = new ImageView(this);
        image.setImageURI(card.contentUri());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setOnClickListener(v -> {
            Intent intent = new Intent(this, FullScreenImageActivity.class);
            intent.putExtra("image_uri", card.uri);
            startActivity(intent);
        });
        root.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));

        favoriteButton = button("");
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        root.addView(favoriteButton, marginTop(8));
        updateFavoriteButton();

        TextView personaHeader = sectionTitle("CHAR 人设");
        root.addView(personaHeader, marginTop(16));

        TextView persona = contentBox(card.persona);
        persona.setMaxLines(12);
        persona.setOnClickListener(v -> openFullText("CHAR 人设", card.persona));
        root.addView(persona, marginTop(6));

        Button fullPersona = button("全屏查看人设");
        fullPersona.setOnClickListener(v -> openFullText("CHAR 人设", card.persona));
        root.addView(fullPersona, marginTop(6));

        greetingTitle = sectionTitle("");
        root.addView(greetingTitle, marginTop(18));

        greetingText = contentBox("");
        greetingText.setMinHeight(dp(240));
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float distance = e2.getX() - e1.getX();
                if (Math.abs(distance) < dp(60) || Math.abs(velocityX) < 300) return false;
                if (distance < 0) nextGreeting();
                else previousGreeting();
                return true;
            }
        });
        greetingText.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        greetingText.setOnClickListener(v -> openCurrentGreetingFullScreen());
        root.addView(greetingText, marginTop(6));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("← 上一个");
        Button next = button("下一个 →");
        previous.setOnClickListener(v -> previousGreeting());
        next.setOnClickListener(v -> nextGreeting());
        navigation.addView(previous, weighted());
        navigation.addView(next, weightedWithMargin());
        root.addView(navigation, marginTop(7));

        Button fullGreeting = button("全屏查看当前开场白");
        fullGreeting.setOnClickListener(v -> openCurrentGreetingFullScreen());
        root.addView(fullGreeting, marginTop(6));

        TextView hint = new TextView(this);
        hint.setText("提示：顶部返回键始终可用；在开场白区域左右滑动可切换全部开场白，点击人设、开场白或图片可全屏查看。");
        hint.setTextSize(12);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        setContentView(page);
    }

    private void showGreeting(int index) {
        int count = card.greetingCount();
        if (count == 0) {
            greetingIndex = 0;
            greetingTitle.setText("开场白（0 个）");
            greetingText.setText("（这张卡没有读取到开场白）");
            return;
        }
        greetingIndex = (index + count) % count;
        greetingTitle.setText("开场白 " + (greetingIndex + 1) + " / " + count);
        greetingText.setText(card.greetings.get(greetingIndex));
    }

    private void nextGreeting() {
        if (card.greetingCount() > 0) showGreeting(greetingIndex + 1);
    }

    private void previousGreeting() {
        if (card.greetingCount() > 0) showGreeting(greetingIndex - 1);
    }

    private void openCurrentGreetingFullScreen() {
        if (card.greetingCount() == 0) {
            openFullText("开场白", "（这张卡没有读取到开场白）");
        } else {
            openFullText("开场白 " + (greetingIndex + 1) + " / " + card.greetingCount(),
                    card.greetings.get(greetingIndex));
        }
    }

    private void openFullText(String title, String text) {
        CardSession.setFullText(title, text);
        startActivity(new Intent(this, FullScreenTextActivity.class));
    }

    private void toggleFavorite() {
        if (FavoriteStore.contains(this, card.uri)) {
            FavoriteStore.remove(this, card.uri);
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
        } else {
            FavoriteStore.add(this, card);
            Toast.makeText(this, "已暂存到收藏夹", Toast.LENGTH_SHORT).show();
        }
        updateFavoriteButton();
    }

    private void updateFavoriteButton() {
        if (favoriteButton == null || card == null) return;
        favoriteButton.setText(FavoriteStore.contains(this, card.uri)
                ? "★ 已收藏（点击取消）" : "☆ 收藏到应用");
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(19);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView contentBox(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackgroundColor(0xfff4f4f4);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
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

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
