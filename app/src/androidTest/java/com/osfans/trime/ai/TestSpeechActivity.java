// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;

/** Synthetic recognition-window result. Does not record audio or call any provider. */
public class TestSpeechActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button complete = new Button(this);
        complete.setText("完成测试识别");
        complete.setOnClickListener(view -> {
            Intent data = new Intent();
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Collections.singletonList("系统窗口识别的测试文字")));
            setResult(RESULT_OK, data);
            finish();
        });
        root.addView(complete);
        Button cancel = new Button(this);
        cancel.setText("取消测试识别");
        cancel.setOnClickListener(view -> { setResult(RESULT_CANCELED); finish(); });
        root.addView(cancel);
        setContentView(root);
    }
}
