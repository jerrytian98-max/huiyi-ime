// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Runs only in the separately installed test APK against a synthetic HTTPS provider. */
class AiIntegrationInstrumentation : Instrumentation() {
    private var longCapture = false
    private var replyIntentGesture = false
    private var replyIntentNetwork = false
    private var replyIntentUi = false
    private var voiceWaveform = false
    private var capturePosition = false
    private var spaceHold = false
    private var tencentDefaultsOnly = false
    private var tencentSpeech = false
    private var systemSpeech = false
    private var capture = false
    private var ice = false
    private var t9 = false
    private var bundled = false
    private var quick = false
    private var toolbarVisibility = false
    private var insert = false
    private var interactive = false
    private var live = false
    private var liveModel = AiConfigStore.DEFAULT_MODEL
    private var liveProvider = "Tokenlink"
    private var audio = false
    private var audioUiOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        longCapture = arguments?.getString("longCapture") == "true"
        replyIntentGesture = arguments?.getString("replyIntentGesture") == "true"
        replyIntentNetwork = arguments?.getString("replyIntentNetwork") == "true"
        replyIntentUi = arguments?.getString("replyIntentUi") == "true"
        voiceWaveform = arguments?.getString("voiceWaveform") == "true"
        capturePosition = arguments?.getString("capturePosition") == "true"
        spaceHold = arguments?.getString("spaceHold") == "true"
        tencentDefaultsOnly = arguments?.getString("tencentDefaultsOnly") == "true"
        tencentSpeech = arguments?.getString("tencentSpeech") == "true"
        systemSpeech = arguments?.getString("systemSpeech") == "true"
        capture = arguments?.getString("capture") == "true"
        ice = arguments?.getString("ice") == "true"
        t9 = arguments?.getString("t9") == "true"
        bundled = arguments?.getString("bundled") == "true"
        quick = arguments?.getString("quick") == "true"
        toolbarVisibility = arguments?.getString("toolbarVisibility") == "true"
        insert = arguments?.getString("insert") == "true"
        interactive = arguments?.getString("interactive") == "true"
        live = arguments?.getString("live") == "true"
        liveModel = arguments?.getString("liveModel") ?: AiConfigStore.DEFAULT_MODEL
        liveProvider = arguments?.getString("liveProvider") ?: "Tokenlink"
        audio = arguments?.getString("audio") == "true"
        audioUiOnly = arguments?.getString("audioUiOnly") == "true"
        start()
    }
    override fun onStart() {
        if (replyIntentUi) {
            ReplyIntentUiChecks.run(this)
            return
        }
        if (replyIntentNetwork) {
            ReplyIntentNetworkChecks.run(this)
            return
        }
        if (replyIntentGesture) {
            ReplyIntentGestureChecks.run(this)
            return
        }
        if (longCapture) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                LongCaptureChecks.run(this)
            } else {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Long capture requires API30") })
            }
            return
        }
        if (voiceWaveform) {
            HeldVoiceWaveformChecks.run(this)
            return
        }
        if (capturePosition) {
            CaptureDialogPositionChecks.run(this)
            return
        }
        if (spaceHold) {
            SpaceHoldVoiceChecks.run(this)
            return
        }
        if (tencentSpeech) {
            TencentSpeechChecks.run(this, tencentDefaultsOnly)
            return
        }
        if (systemSpeech) {
            AiSystemSpeechChecks.run(this, interactive)
            return
        }
        if (toolbarVisibility) {
            AiDualPaletteChecks.run(this)
            return
        }
        if (t9) {
            T9NativeChecks.run(this)
            return
        }
        if (insert) {
            AiCursorInsertionChecks.run(this)
            return
        }
        if (quick) {
            AiQuickCaptureChecks.run(this)
            return
        }
        if (bundled) {
            AiBundledDefaultChecks.run(this)
            return
        }
        if (ice) {
            IceNativeChecks.run(this)
            return
        }
        if (audio) {
            AiAudioChecks.run(this, audioUiOnly)
            return
        }
        if (live) {
            AiLiveChecks.run(this, liveModel, liveProvider)
            return
        }
        val result = Bundle()
        val original = AiConfigStore.load(targetContext)
        val reviewDriver = if (interactive) ReviewEditorTestDriver(targetContext) else null
        try {
            runOnMainSync {
                val detached = com.osfans.trime.ime.core.TouchEventReceiverWindow(android.view.View(targetContext))
                detached.showAt(0, 0, 100, 100)
                detached.dismiss()
            }
            result.putString("window", "PASS: detached candidate touch window does not create a popup with a null token")
            val certificate = context.assets.open("test-ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val config = AiConfig("https://10.0.2.2:9443/v1/chat/completions", "mock-text", "mock-vision", "synthetic-test-key", "zh-CN")
            AiConfigStore.save(targetContext, config)
            check(AiConfigStore.load(targetContext) == config) { "Keystore round-trip failed" }
            val stored = targetContext.getSharedPreferences("ai_assistant_config", 0).all
            check(stored["api_key"] == null && !stored["api_key_encrypted"].toString().contains(config.apiKey))
            runBlocking {
                val partials = mutableListOf<String>()
                check(AiClient.polish(config, "嗯明天下午三点那个开会", partials::add) == "明天下午三点开会。")
                check(partials.size >= 2) { "No stream preview" }
                val jpeg = ByteArrayOutputStream().also { output ->
                    val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    bitmap.recycle()
                }.toByteArray()
                check(AiClient.suggestReplies(config, jpeg).size == 3)
                val customReply = config.copy(visionModel = "mock-vision-style", replySystemPrompt = "只说重点，不要寒暄。")
                AiConfigStore.save(targetContext, customReply)
                check(AiConfigStore.load(targetContext) == customReply) { "Reply system prompt did not persist" }
                check(AiClient.suggestReplies(AiConfigStore.load(targetContext), jpeg).first() == "行，明天见。")
                AiConfigStore.save(targetContext, config)
                val audioConfig = config.copy(speechEndpoint = "https://10.0.2.2:9443/v1/audio/transcriptions", speechModel = "mock-speech")
                val wav = WavAudio.encode(ByteArray(32000) { if (it % 2 == 0) 0 else 16 })
                check(AiSpeechClient.transcribe(audioConfig, wav) == "明天下午三点开会。")
                for (model in listOf("http401", "malformed", "empty")) {
                    var rejected = false
                    try {
                        AiSpeechClient.transcribe(audioConfig.copy(speechModel = model), wav)
                    } catch (_: IllegalStateException) {
                        rejected = true
                    }
                    check(rejected) { "Speech failure was not rejected: $model" }
                }
                for (model in listOf("http401", "http429", "malformed", "truncated")) {
                    var rejected = false
                    try {
                        AiClient.polish(config.copy(model = model), "测试")
                    } catch (_: IllegalStateException) {
                        rejected = true
                    }
                    check(rejected) { "Failure was not rejected: $model" }
                }
            }
            result.putString("network", "PASS: TLS, streamed text, image request, custom reply system prompt, multipart WAV transcription, 401/429/malformed/truncated/empty responses")
            result.putString("keystore", "PASS: encrypted round-trip, no plaintext preference")
            AiReplySettingsChecks.run(this, config)
            result.putString("reply_settings", "PASS: edit, save, reopen and restore default reply system prompt")
            if (capture || interactive) {
                runOnMainSync {
                    targetContext.startActivity(
                        Intent().setClassName(context.packageName, TestChatActivity::class.java.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                SystemClock.sleep(1500)
            }
            if (interactive) {
                val done = java.io.File(targetContext.filesDir, "finish-ui-checks")
                done.delete()
                sendStatus(0, Bundle().apply { putString("stream", "INTERACTIVE_READY") })
                // A bounded window for host UI checks in this same TLS-test process.
                val deadline = SystemClock.elapsedRealtime() + 1_200_000
                while (!done.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
                done.delete()
            }
            if (capture) {
                runOnMainSync {
                    targetContext.startActivity(
                        Intent(targetContext, AiCaptureActivity::class.java)
                            .putExtra(AiCaptureActivity.EXTRA_REQUEST_ID, "instrumentation-capture")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                // The host accepts the actual Android screen capture consent dialog.
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var state = CaptureState()
                while (SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync { state = AiEvents.captureState }
                    if (state.status == CaptureStatus.READY || state.status == CaptureStatus.ERROR || state.status == CaptureStatus.CANCELLED) break
                    SystemClock.sleep(200)
                }
                check(state.status == CaptureStatus.READY && state.suggestions.size == 3) { "MediaProjection failed: $state" }
                result.putString("capture", "PASS: Android 15 consent -> foreground service -> one frame -> HTTPS -> three suggestions")
                runOnMainSync { AiEvents.clearSuggestions() }
            }
            result.putString("stream", "All integration checks passed\n" + result.keySet().joinToString("\n") { "$it: ${result.getString(it)}" })
            AiConfigStore.save(targetContext, original)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            AiConfigStore.save(targetContext, original)
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            reviewDriver?.close()
        }
    }
}
