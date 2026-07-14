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
    private static final int REQUEST_GREETING_FULLSCREEN = 8101;

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
            Toast.makeText(this, "卡片内容已失效，请重新扫描或从收藏夹打开。",
                    Toast.LENGTH_LONG).show();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GREETING_FULLSCREEN
                && resultCode == RESULT_OK && data != null) {
            showGreeting(data.getIntExtra(
                    GreetingFullScreenActivity.EXTRA_GREETING_INDEX, greetingIndex));
        }
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(10), dp(10), dp(10));

        Button backButton = button("← 返回卡片列表");
        backButton.setContentDescription("返回卡片列表");
        backButton.setOnClickListener(v -> finish());
        page.addView(backButton, matchWrap());

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
        file.setText(card.formatLabel() + " 角色卡\n" + card.path);
        file.setTextSize(12);
        file.setTextIsSelectable(true);
        file.setPadding(0, dp(4), 0, dp(10));
        root.addView(file);

        if (card.hasCoverImage()) buildPngHeader(root);
        else buildJsonHeader(root);
        updateFavoriteButton();

        LinearLayout personaHeader = new LinearLayout(this);
        personaHeader.setOrientation(LinearLayout.HORIZONTAL);
        personaHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView personaTitle = sectionTitle("CHAR 人设");
        personaHeader.addView(personaTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button fullPersona = button("全屏查看人设");
        fullPersona.setOnClickListener(v -> openFullText("CHAR 人设", card.persona));
        personaHeader.addView(fullPersona,
                new LinearLayout.LayoutParams(dp(142), dp(52)));
        root.addView(personaHeader, marginTop(16));

        TextView persona = contentBox(card.persona);
        persona.setMaxLines(12);
        persona.setOnClickListener(v -> openFullText("CHAR 人设", card.persona));
        root.addView(persona, marginTop(6));

        LinearLayout greetingHeader = new LinearLayout(this);
        greetingHeader.setOrientation(LinearLayout.HORIZONTAL);
        greetingHeader.setGravity(Gravity.CENTER_VERTICAL);
        greetingTitle = sectionTitle("");
        greetingHeader.addView(greetingTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button fullGreeting = button("全屏查看开场白");
        fullGreeting.setOnClickListener(v -> openCurrentGreetingFullScreen());
        greetingHeader.addView(fullGreeting,
                new LinearLayout.LayoutParams(dp(154), dp(52)));
        root.addView(greetingHeader, marginTop(18));

        root.addView(greetingNavigation("上方"), marginTop(6));

        greetingText = contentBox("");
        greetingText.setMinHeight(dp(240));
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float distance = e2.getX() - e1.getX();
                        float vertical = e2.getY() - e1.getY();
                        if (Math.abs(distance) < dp(60)
                                || Math.abs(distance) <= Math.abs(vertical)
                                || Math.abs(velocityX) < 300) return false;
                        if (distance < 0) nextGreeting();
                        else previousGreeting();
                        return true;
                    }
                });
        greetingText.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });
        greetingText.setOnClickListener(v -> openCurrentGreetingFullScreen());
        root.addView(greetingText, marginTop(6));

        root.addView(greetingNavigation("下方"), marginTop(7));

        TextView hint = new TextView(this);
        hint.setText("提示：PNG 和 JSON 角色卡都支持开场白左右滑动；上方和下方均有“上一个 / 下一个”。 ");
        hint.setTextSize(12);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        setContentView(page);
    }

    private void buildPngHeader(LinearLayout root) {
        LinearLayout imageArea = new LinearLayout(this);
        imageArea.setOrientation(LinearLayout.HORIZONTAL);
        imageArea.setGravity(Gravity.CENTER_VERTICAL);

        ImageView image = new ImageView(this);
        image.setImageURI(card.contentUri());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setOnClickListener(v -> openImageFullScreen());
        imageArea.addView(image, new LinearLayout.LayoutParams(0, dp(285), 1f));

        LinearLayout imageActions = new LinearLayout(this);
        imageActions.setOrientation(LinearLayout.VERTICAL);
        imageActions.setPadding(dp(8), 0, 0, 0);

        favoriteButton = button("");
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        imageActions.addView(favoriteButton,
                new LinearLayout.LayoutParams(dp(112), dp(64)));

        Button imageFullScreen = button("图片全屏");
        imageFullScreen.setContentDescription("全屏查看图片");
        imageFullScreen.setOnClickListener(v -> openImageFullScreen());
        LinearLayout.LayoutParams imageFullParams =
                new LinearLayout.LayoutParams(dp(112), dp(64));
        imageFullParams.topMargin = dp(8);
        imageActions.addView(imageFullScreen, imageFullParams);

        imageArea.addView(imageActions);
        root.addView(imageArea, matchWrap());
    }

    private void buildJsonHeader(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(0xfff1f1f1);

        TextView info = new TextView(this);
        info.setText("JSON 角色卡没有内嵌封面。\n人设、开场白、收藏和移动功能仍可正常使用。 ");
        info.setTextSize(14);
        row.addView(info, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        favoriteButton = button("");
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        row.addView(favoriteButton,
                new LinearLayout.LayoutParams(dp(112), dp(64)));
        root.addView(row, matchWrap());
    }

    private LinearLayout greetingNavigation(String position) {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("← 上一个");
        previous.setContentDescription(position + "上一个开场白");
        Button next = button("下一个 →");
        next.setContentDescription(position + "下一个开场白");
        previous.setOnClickListener(v -> previousGreeting());
        next.setOnClickListener(v -> nextGreeting());
        navigation.addView(previous, weighted());
        navigation.addView(next, weightedWithMargin());
        return navigation;
    }

    private void openImageFullScreen() {
        if (!card.hasCoverImage()) return;
        Intent intent = new Intent(this, FullScreenImageActivity.class);
        intent.putExtra("image_uri", card.uri);
        startActivity(intent);
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
            return;
        }
        Intent intent = new Intent(this, GreetingFullScreenActivity.class);
        intent.putExtra(GreetingFullScreenActivity.EXTRA_CARD_KEY, card.key);
        intent.putExtra(GreetingFullScreenActivity.EXTRA_GREETING_INDEX, greetingIndex);
        startActivityForResult(intent, REQUEST_GREETING_FULLSCREEN);
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
                ? "★ 已收藏\n点击取消" : "☆ 收藏");
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
