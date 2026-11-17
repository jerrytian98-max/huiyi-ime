// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Synthetic scrollable chat; each message has a unique color to verify image chronology. */
@android.annotation.TargetApi(30)
public class LongChatActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 80, 0, 0);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            int bottom = Math.max(bars.bottom, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
            view.setPadding(0, bars.top, 0, bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("长图测试聊天 · Alex");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        column.addView(title);
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("synthetic-long-chat");
        scroll.setSmoothScrollingEnabled(false);
        if (getIntent().getBooleanExtra("hideScrollNode", false)) scroll.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 60; i++) {
            TextView message = new TextView(this);
            message.setText(String.format("消息 %02d · %s\n这是合成聊天记录，第 %02d 项安排，仅用于长截图验证。", i, i % 2 == 0 ? "Alex" : "我", i));
            message.setTextSize(17);
            message.setTextColor(Color.BLACK);
            message.setPadding(32, 15, 18, 15);
            message.setBackgroundColor(Color.rgb(180 + i, 220 - i, 200));
            messages.addView(message, new LinearLayout.LayoutParams(-1, 190));
        }
        scroll.addView(messages);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        EditText editor = new EditText(this);
        editor.setHint("回复合成消息");
        editor.setId(65432 + getIntent().getIntExtra("epoch", 0));
        editor.setSingleLine(true);
        if (getIntent().getBooleanExtra("badEditorBounds", false)) editor.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setBoundsInScreen(new android.graphics.Rect(0, 350, 1080, 420));
            }
        });
        column.addView(editor, new LinearLayout.LayoutParams(-1, 150));
        if (getIntent().getBooleanExtra("hideImeOnScroll", false)) {
            final boolean[] hidden = {false};
            scroll.setOnTouchListener((view, event) -> {
                if (!hidden[0] && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    hidden[0] = true;
                    scroll.post(() -> getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(), 0));
                }
                return false;
            });
        }
        setContentView(column);
        if (getIntent().getBooleanExtra("badRootBounds", false)) getWindow().getDecorView().setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setBoundsInScreen(new android.graphics.Rect(0, 0, 1080, 60));
            }
        });
        editor.requestFocus();
        editor.postDelayed(() -> {
            getSystemService(InputMethodManager.class).showSoftInput(editor, 0);
            scroll.postDelayed(() -> scroll.scrollTo(0, messages.getHeight()), 700);
        }, 400);
        editor.postDelayed(() -> {
            if (editor.getRootWindowInsets() != null && !editor.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime())) {
                getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 1400);
    }
}
