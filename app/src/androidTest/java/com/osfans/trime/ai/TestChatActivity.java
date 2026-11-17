// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Separate-process synthetic app. Java avoids relying on the target APK's Kotlin runtime. */
public class TestChatActivity extends Activity {
    private static TestChatActivity latest;
    private EditText editor;
    private final BroadcastReceiver driver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (latest != TestChatActivity.this) return;
            if (intent.hasExtra("inputType")) {
                editor.setInputType(intent.getIntExtra("inputType", 1));
                getSystemService(InputMethodManager.class).restartInput(editor);
            }
            String value = intent.getStringExtra("value");
            if (value != null) editor.setText(value);
            if (intent.hasExtra("start")) {
                int start = Math.max(0, Math.min(editor.length(), intent.getIntExtra("start", editor.length())));
                int end = Math.max(0, Math.min(editor.length(), intent.getIntExtra("end", start)));
                editor.setSelection(start, end);
            }
            if (intent.getBooleanExtra("focus", false)) {
                editor.requestFocus();
                getSystemService(InputMethodManager.class).showSoftInput(editor, 0);
            }
            if (intent.getBooleanExtra("hideKeyboard", false)) {
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(editor.getWindowToken(), 0);
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            String clip = intent.getStringExtra("clipboard");
            if (clip != null) clipboard.setPrimaryClip(ClipData.newPlainText("test", clip));
            if (intent.getBooleanExtra("readClipboard", false)) {
                ClipData data = clipboard.getPrimaryClip();
                setResultData(data == null ? "" : String.valueOf(data.getItemAt(0).getText()));
            } else {
                setResultData(editor.getText().toString());
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(32, 80, 32, 32);
        TextView title = new TextView(this);
        title.setText("测试聊天 · Alex");
        title.setTextSize(24);
        column.addView(title);
        TextView conversation = new TextView(this);
        conversation.setText("Alex：明天下午三点一起讨论项目，可以吗？\n\n我：我看看安排。\n\nAlex：方便的话请提前发我一下提纲。");
        conversation.setTextSize(20);
        column.addView(conversation);
        editor = new EditText(this);
        editor.setHint("回复消息");
        editor.setId(12345);
        editor.setMinLines(3);
        column.addView(editor);
        Button select = new Button(this);
        select.setText("选中全部文字");
        select.setOnClickListener(view -> { editor.requestFocus(); editor.selectAll(); });
        column.addView(select);
        Button fill = new Button(this);
        fill.setText("填入测试文字");
        fill.setOnClickListener(view -> { editor.setText("嗯明天下午三点那个开会"); editor.selectAll(); editor.requestFocus(); });
        column.addView(fill);
        setContentView(column);
        latest = this;
        IntentFilter filter = new IntentFilter("com.rimeai.keyboard.debug.test.SET_EDITOR");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(driver, filter, "android.permission.READ_INPUT_STATE", null, Context.RECEIVER_EXPORTED);
        } else {
            registerLegacyReceiver(filter);
        }
        editor.requestFocus();
        editor.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(editor, 0), 400);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        // Used only below API 33; the signature permission still restricts this test driver.
        registerReceiver(driver, filter, "android.permission.READ_INPUT_STATE", null);
    }

    @Override public void onDestroy() {
        unregisterReceiver(driver);
        if (latest == this) latest = null;
        super.onDestroy();
    }
}
