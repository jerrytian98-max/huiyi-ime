// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.core.view.children
import com.osfans.trime.BuildConfig
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real toolbar touch + AudioRecord (host injects synthetic PCM) + screenshot + guarded insertion. */
internal object ReplyIntentUiChecks {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    fun run(test: Instrumentation) {
        test.runOnMainSync { }
        val context = test.targetContext
        val original = AiConfigStore.load(context)
        val originalQuick = AiQuickCapture.isRequested(context)
        val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val ready = File(context.filesDir, "reply-intent-audio-ready")
        val audioDone = File(context.filesDir, "reply-intent-audio-done")
        val rows = mutableListOf<String>()
        var lastToast = ""
        automation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == context.packageName && event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) lastToast = event.text.joinToString()
        }
        var failure: Throwable? = null
        var input: InputView? = null
        var pressed = false
        var x = 0f
        var y = 0f
        var downTime = 0L
        fun main(block: () -> Unit) = test.runOnMainSync(block)
        fun waitFor(label: String, timeout: Long = 45_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                SystemClock.sleep(80)
            }
            error("Timed out: $label")
        }
        fun mainCondition(condition: () -> Boolean): Boolean {
            var result = false
            main { result = condition() }
            return result
        }
        fun wave(): HeldVoiceWaveformView = descendants(checkNotNull(input)).filterIsInstance<HeldVoiceWaveformView>().first()
        fun findEditor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.packageName?.toString() == test.context.packageName) return node
            for (i in 0 until node.childCount) findEditor(node.getChild(i))?.let { return it }
            return null
        }
        fun editor(): AccessibilityNodeInfo? = findEditor(automation.rootInActiveWindow)
            ?: automation.windows.asSequence().mapNotNull { findEditor(it.root) }.firstOrNull()
        fun touch(action: Int, dx: Float = 0f) {
            if (action == MotionEvent.ACTION_DOWN) {
                main {
                    val button = descendants(checkNotNull(input)).first { it.contentDescription?.startsWith("AI回复") == true }
                    val xy = IntArray(2)
                    button.getLocationOnScreen(xy)
                    x = xy[0] + button.width / 2f
                    y = xy[1] + button.height / 2f
                }
                downTime = SystemClock.uptimeMillis()
                pressed = true
            }
            val offset = if (action == MotionEvent.ACTION_DOWN) 0f else 36f * context.resources.displayMetrics.density
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x + dx, y + offset, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pressed = false
        }
        fun snapshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            ready.delete()
            audioDone.delete()
            val certificate = test.context.assets.open("test-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory)
            AiConfigStore.save(context, AiConfig("https://10.0.2.2:9443/v1/chat/completions", "mock-text", "mock-vision-intent", "synthetic-test-key", "zh-CN", cloudSpeech = true, speechEndpoint = "https://10.0.2.2:9443/v1/audio/transcriptions", speechModel = "mock-reply-intent", showReplyOptions = false))
            main { AiQuickCapture.setRequested(context, true) }
            val service = context.packageName + "/" + AiAccessibilityCaptureService::class.java.name
            for (command in listOf("settings put secure enabled_accessibility_services $service", "settings put secure accessibility_enabled 1")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            waitFor("accessibility capture service") { mainCondition { AiAccessibilityCaptureService.isConnected } }
            main {
                context.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            waitFor("visible keyboard") {
                mainCondition {
                    input = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<InputView>().firstOrNull { it.isShown && it.service.isInputViewShown && it.keyboardView.height > 0 }
                    input != null
                }
            }
            runBlocking {
                withTimeout(180_000) {
                    RimeDaemon.createSession(TrimeInputMethodService::class.java.name).runOnReady {
                        while (!selectSchema("rime_ice")) delay(250)
                        clearComposition()
                    }
                }
            }
            waitFor("accessible source editor") { editor() != null }
            val node = checkNotNull(editor())
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "前缀后缀") }))
            check(
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                    },
                ),
            )
            SystemClock.sleep(800)
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_MOVE)
            waitFor("reply intent recording") { mainCondition { wave().visibility == View.VISIBLE && wave().contentDescription.startsWith("说说你想怎么回") } }
            snapshot("reply-intent-recording.png")
            ready.writeText("recording")
            waitFor("host synthetic microphone audio", 45_000) { audioDone.exists() }
            main { check(!AiEvents.isCaptureInProgress) { "Screenshot started before release" } }
            check(mainCondition { wave().visibility == View.VISIBLE }) { "Recording stopped before release: $lastToast" }
            rows += "PASS: actual downward toolbar touch starts AudioRecord and waits for release before screenshot"
            touch(MotionEvent.ACTION_UP)
            waitFor("reply inserted at original cursor", 75_000) {
                check(lastToast.isBlank()) { "Reply flow reported: $lastToast" }
                editor()?.text?.toString() == "前缀这周没空，下周可以吗？后缀" && mainCondition { AiEvents.captureState.status == CaptureStatus.IDLE }
            }
            snapshot("reply-intent-inserted.png")
            rows += "PASS: injected PCM -> cloud ASR -> real screenshot -> intent-aware vision -> first reply inserted between original prefix and suffix"
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_MOVE)
            waitFor("second recording") { mainCondition { wave().visibility == View.VISIBLE } }
            touch(MotionEvent.ACTION_MOVE, -100f * context.resources.displayMetrics.density)
            waitFor("cancel region") { mainCondition { wave().contentDescription.contains("松手取消") } }
            snapshot("reply-intent-cancel.png")
            touch(MotionEvent.ACTION_UP, -100f * context.resources.displayMetrics.density)
            waitFor("recording cancelled") { mainCondition { wave().visibility == View.GONE } }
            main { check(AiEvents.captureState.status == CaptureStatus.IDLE) }
            check(editor()?.text?.toString() == "前缀这周没空，下周可以吗？后缀")
            rows += "PASS: left-slide cancellation stops real recording without changing the editor or taking a screenshot"
        } catch (error: Throwable) {
            failure = error
            runCatching { snapshot("reply-intent-ui-failure.png") }
        } finally {
            if (pressed) runCatching { touch(MotionEvent.ACTION_CANCEL) }
            main {
                input?.service?.aiInputController?.cancelHeldVoice()
                AiEvents.finishSession(AiEvents.captureState.requestId)
                AiQuickCapture.setRequested(context, originalQuick)
            }
            AiConfigStore.save(context, original)
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
            ready.delete()
            audioDone.delete()
        }
        val result = buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("passed", failure == null)
            put("external_ai_calls", 0)
            put("physical_microphone_verified", false)
            put("diagnostic", failure?.message.orEmpty())
            put("checks", JsonArray(rows.map(::JsonPrimitive)))
        }
        File(context.filesDir, "reply-intent-ui-result.json").writeText(result.toString())
        test.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure?.stackTraceToString() ?: result.toString()) })
    }
}
