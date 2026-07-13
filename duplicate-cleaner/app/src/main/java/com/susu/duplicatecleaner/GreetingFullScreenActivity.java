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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class GreetingFullScreenActivity extends Activity {
    static final String EXTRA_CARD_KEY = "card_key";
    static final String EXTRA_GREETING_INDEX = "greeting_index";

    private CharacterCard card;
    private int greetingIndex;
    private TextView title;
    private TextView content;
    private ScrollView scrollView;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();

        String key = getIntent().getStringExtra(EXTRA_CARD_KEY);
        card = CardSession.get(key);
        if (card == null) {
            Toast.makeText(this, "开场白内容已失效，请返回后重新打开卡片。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        greetingIndex = getIntent().getIntExtra(EXTRA_GREETING_INDEX, 0);
        buildUi();
        showGreeting(greetingIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
    }

    @Override
    public void finish() {
        Intent result = new Intent();
        result.putExtra(EXTRA_GREETING_INDEX, greetingIndex);
        setResult(RESULT_OK, result);
        super.finish();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Button back = button("← 返回卡片详情");
        back.setContentDescription("返回卡片详情");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());

        title = new TextView(this);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(8));
        root.addView(title);

        root.addView(navigationBar("上方"), matchWrap());

        scrollView = new ScrollView(this);
        content = new TextView(this);
        content.setTextSize(17);
        content.setTextIsSelectable(true);
        content.setPadding(dp(12), dp(14), dp(12), dp(20));
        content.setBackgroundColor(0xfff4f4f4);
        scrollView.addView(content, matchWrap());
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(navigationBar("下方"), marginTop(8));

        TextView hint = new TextView(this);
        hint.setText("可以左右滑动切换；页面上方和下方都保留“上一个 / 下一个”。正文仍可上下滚动。 ");
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, 0);
        root.addView(hint);

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) <= Math.abs(dy)
                        || Math.abs(velocityX) < 300) return false;
                if (dx < 0) nextGreeting();
                else previousGreeting();
                return true;
            }
        });
        View.OnTouchListener swipeListener = (v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        };
        scrollView.setOnTouchListener(swipeListener);
        content.setOnTouchListener(swipeListener);

        setContentView(root);
    }

    private LinearLayout navigationBar(String position) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Button previous = button("← 上一个");
        previous.setContentDescription(position + "上一个开场白");
        previous.setOnClickListener(v -> previousGreeting());
        Button next = button("下一个 →");
        next.setContentDescription(position + "下一个开场白");
        next.setOnClickListener(v -> nextGreeting());
        bar.addView(previous, weighted());
        bar.addView(next, weightedMargin());
        return bar;
    }

    private void showGreeting(int index) {
        int count = card.greetingCount();
        if (count <= 0) {
            greetingIndex = 0;
            title.setText("开场白（0 个）");
            content.setText("（这张卡没有读取到开场白）");
            return;
        }
        greetingIndex = (index + count) % count;
        title.setText("开场白 " + (greetingIndex + 1) + " / " + count);
        content.setText(card.greetings.get(greetingIndex));
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    private void previousGreeting() {
        if (card.greetingCount() > 0) showGreeting(greetingIndex - 1);
    }

    private void nextGreeting() {
        if (card.greetingCount() > 0) showGreeting(greetingIndex + 1);
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
