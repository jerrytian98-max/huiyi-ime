// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Collections;

/** Test-only callback source. No real audio is recorded or recognized. */
public class TestRecognitionService extends RecognitionService {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static String lastMode = "";
    private static int attempts;

    @Override protected void onStartListening(Intent intent, Callback listener) {
        String mode = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (mode == null) mode = "";
        if (!mode.equals(lastMode)) { lastMode = mode; attempts = 0; }
        attempts++;
        if (mode.startsWith("test-client-always") || (mode.startsWith("test-client-once") && attempts == 1)) {
            try { listener.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { }
            return;
        }
        if (mode.startsWith("test-silent")) return;
        if (mode.startsWith("test-permission")) {
            try { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); } catch (RemoteException ignored) { }
            return;
        }
        if (!intent.hasExtra("calling_package") || !intent.hasExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)) {
            try { listener.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { }
            return;
        }
        if (mode.startsWith("test-partial-error")) {
            try {
                listener.readyForSpeech(new Bundle());
                listener.partialResults(words("保留临时文字"));
                listener.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (RemoteException ignored) { }
            return;
        }
        if (mode.startsWith("test-finish")) {
            try { listener.readyForSpeech(new Bundle()); } catch (RemoteException ignored) { }
            return;
        }
        try { listener.readyForSpeech(new Bundle()); } catch (RemoteException ignored) { return; }
        handler.postDelayed(() -> {
            try { listener.partialResults(words("嗯明天下午三点")); } catch (RemoteException ignored) { }
        }, 300);
        handler.postDelayed(() -> {
            try { listener.results(words("嗯明天下午三点那个开会")); } catch (RemoteException ignored) { }
        }, 1200);
    }

    @Override protected void onStopListening(Callback listener) {
        handler.postDelayed(() -> {
            try { listener.results(words("点击完成后的识别文字")); } catch (RemoteException ignored) { }
        }, 600);
    }
    @Override protected void onCancel(Callback listener) { handler.removeCallbacksAndMessages(null); }
    private static Bundle words(String text) {
        Bundle result = new Bundle();
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
            new ArrayList<>(Collections.singletonList(text)));
        return result;
    }
}
