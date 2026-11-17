// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.children
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ime.core.InputView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real held-button touches, accessibility scrolling, screenshots and HTTPS against a local mock only. */
@RequiresApi(30)
internal object LongCaptureChecks {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    fun run(test: Instrumentation) {
        test.runOnMainSync { }
        val context = test.targetContext
        val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val readyFile = File(context.filesDir, "long-capture-ready")
        val goFile = File(context.filesDir, "long-capture-go")
        val original = AiConfigStore.load(context)
        val quick = AiQuickCapture.isRequested(context)
        val tlsOriginal = HttpsURLConnection.getDefaultSSLSocketFactory()
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var code = Activity.RESULT_OK
        val output = Bundle()
        var input: InputView? = null
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { runCatching(block).onFailure { failure = it } }
            failure?.let { throw it }
        }
        fun waitFor(name: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                SystemClock.sleep(80)
            }
            error("Timed out: $name; state=${AiEvents.captureState}")
        }
        val dismiss = AiInputController::class.java.getDeclaredMethod("dismissCaptureDialog", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val imageField = AiEvents::class.java.getDeclaredField("screenshot").apply { isAccessible = true }
        var editorEpoch = 0
        fun open(long: Boolean = true, hideImeOnScroll: Boolean = false, exposeScrollNode: Boolean = false) {
            val epoch = ++editorEpoch
            val expectedField = if (long) 65432 + epoch else 12345
            main {
                input?.service?.aiInputController?.cancelLongScreenAnalysis()
                input?.service?.aiInputController?.let { dismiss.invoke(it, true) }
                AiEvents.finishSession(AiEvents.captureState.requestId)
                context.startActivity(Intent().setClassName(test.context.packageName, if (long) LongChatActivity::class.java.name else TestChatActivity::class.java.name).putExtra("epoch", epoch).putExtra("hideScrollNode", !exposeScrollNode).putExtra("badRootBounds", true).putExtra("badEditorBounds", true).putExtra("hideImeOnScroll", hideImeOnScroll).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            waitFor("editor") {
                input = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<InputView>()
                    .firstOrNull { it.isShown && it.windowVisibility == View.VISIBLE && it.service.isInputViewShown && it.service.currentInputEditorInfo?.fieldId == expectedField }
                input != null
            }
            SystemClock.sleep(1600)
        }
        var x = 0f
        var y = 0f
        var down = 0L
        fun button(): View = descendants(input!!).first { it.contentDescription?.startsWith("AI回复") == true }
        fun touch(action: Int, drag: Boolean = false) {
            if (action == MotionEvent.ACTION_DOWN) {
                main {
                    val view = button()
                    val pos = IntArray(2)
                    view.getLocationOnScreen(pos)
                    x = pos[0] + view.width / 2f
                    y = pos[1] + view.height / 2f
                }
                down = SystemClock.uptimeMillis()
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y - if (drag) 160f else 0f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        fun dragStart() {
            touch(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(200)
            touch(MotionEvent.ACTION_MOVE, true)
        }
        fun label(): String = descendants(button()).filterIsInstance<TextView>().first().text.toString()
        fun image(name: String): Bitmap {
            var bytes: ByteArray? = null
            main { bytes = imageField.get(AiEvents) as? ByteArray }
            File(context.filesDir, "$name.jpg").writeBytes(checkNotNull(bytes))
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size))
        }
        fun sourceEditor(): AccessibilityNodeInfo? {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(roots::add)
            for (window in automation.windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) window.root?.let(roots::add)
            }
            for (root in roots) {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node.isEditable && node.packageName?.toString() == test.context.packageName) {
                        queue.forEach(AccessibilityNodeInfo::recycle)
                        return node
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                    node.recycle()
                }
            }
            return null
        }
        fun fillSourceEditor() {
            checkNotNull(sourceEditor()).let { editor ->
                try {
                    check(
                        editor.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "前缀后缀")
                            },
                        ),
                    )
                    check(
                        editor.performAction(
                            AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                            },
                        ),
                    )
                } finally {
                    editor.recycle()
                }
            }
        }
        fun firstReplyInserted(): Boolean {
            val editor = sourceEditor() ?: return false
            return try {
                editor.text?.toString() == "前缀好的，明天下午三点见，我会提前发提纲。后缀" &&
                    AiTextReviewStore.current == null && AiEvents.captureState.status == CaptureStatus.IDLE
            } finally {
                editor.recycle()
            }
        }
        try {
            val certificate = test.context.assets.open("test-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            AiConfigStore.save(context, AiConfig("https://10.0.2.2:9443/v1/chat/completions", "mock-text", "mock-vision", "synthetic-test-key", "zh-CN", showReplyOptions = true))
            main { AiQuickCapture.setRequested(context, true) }
            goFile.delete()
            readyFile.writeText(BuildConfig.VERSION_NAME)
            waitFor("accessibility service") { goFile.exists() && AiAccessibilityCaptureService.isConnected }
            open(exposeScrollNode = true)
            dragStart()
            waitFor("held finger scrolls exposed chat") {
                AiEvents.captureState.status == CaptureStatus.CAPTURING &&
                    AiEvents.captureState.message.contains("已截2屏")
            }
            main { check(imageField.get(AiEvents) == null) { "Image sent before finger release" } }
            touch(MotionEvent.ACTION_UP, true)
            waitFor("held scroll reply") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            val heldScroll = image("long-capture-held-scroll")
            check(heldScroll.width <= 800 && heldScroll.height > 1800)
            heldScroll.recycle()
            rows += buildJsonObject {
                put("case", "scroll-and-capture-before-release-then-stop")
                put("passed", true)
            }

            open()
            dragStart()
            waitFor("release prompt") { label() == "松手" }
            main {
                check(AiEvents.captureState.status == CaptureStatus.CAPTURING)
                check(imageField.get(AiEvents) == null) { "Image sent before finger release" }
            }
            touch(MotionEvent.ACTION_UP, true)
            waitFor("long replies") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            val stitched = image("long-capture-stitch")
            check(stitched.width <= 800 && stitched.height > 1800)
            fun messageStripe(fromBottom: Boolean): Int {
                for (offset in 0 until minOf(600, stitched.height)) {
                    val color = stitched.getPixel(5, if (fromBottom) stitched.height - 1 - offset else offset)
                    val red = Color.red(color)
                    if (red in 180..240 && Color.blue(color) in 185..215 && kotlin.math.abs(red - Color.green(color)) > 10) return red
                }
                error("Missing synthetic chat color stripe")
            }
            val top = messageStripe(false)
            val bottom = messageStripe(true)
            check(top + 4 < bottom) { "Chronology reversed or latest messages lost: $top -> $bottom" }
            rows += buildJsonObject {
                put("case", "hold-drag-release")
                put("width", stitched.width)
                put("height", stitched.height)
                put("oldest_red", top)
                put("latest_red", bottom)
                put("upload_waits_for_release", true)
                put("scroll_node_hidden", true)
                put("simulated_downward_swipe", true)
                put("invalid_root_bounds", true)
                put("stale_editor_bounds", true)
            }
            stitched.recycle()

            open()
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            waitFor("single tap") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            waitFor("short-tap reply chooser") {
                WindowInspector.getGlobalWindowViews().any { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels && AiEvents.captureState.suggestions.any { it in labels }
                }
            }
            val single = image("long-capture-single")
            check(maxOf(single.width, single.height) == 2400)
            rows += buildJsonObject {
                put("case", "short-tap-single-screen")
                put("width", single.width)
                put("height", single.height)
            }
            single.recycle()

            open()
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(1500)
            main {
                check(AiEvents.captureState.status == CaptureStatus.IDLE || AiEvents.captureState.status == CaptureStatus.CANCELLED)
                check(imageField.get(AiEvents) == null)
            }
            rows += buildJsonObject {
                put("case", "cancel-before-drag-discards-frames")
                put("passed", true)
            }

            open()
            dragStart()
            waitFor("drag armed before cancelled pointer") { label() == "松手" }
            touch(MotionEvent.ACTION_CANCEL, true)
            waitFor("cancelled pointer completes long capture") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            rows += buildJsonObject {
                put("case", "cancel-after-drag-finishes-capture")
                put("passed", true)
            }

            open(hideImeOnScroll = true)
            fillSourceEditor()
            var expectedChooserTop = 0
            main { expectedChooserTop = checkNotNull(input!!.service.currentKeyboardTopOnScreen()) }
            dragStart()
            touch(MotionEvent.ACTION_UP, true)
            waitFor("chat hides keyboard on scroll") { input?.service?.isInputViewShown == false }
            waitFor("reply despite hidden keyboard") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            val hiddenImeImage = image("long-capture-ime-hidden")
            check(hiddenImeImage.height > 1800) { "Expected more than the first screen after IME dismissal" }
            hiddenImeImage.recycle()
            waitFor("same reply chooser after keyboard dismissal") {
                WindowInspector.getGlobalWindowViews().any { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels && AiEvents.captureState.suggestions.any { it in labels }
                }
            }
            waitFor("reply chooser settles at keyboard edge") {
                val chooser = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels
                } ?: return@waitFor false
                val position = IntArray(2)
                chooser.getLocationOnScreen(position)
                kotlin.math.abs(position[1] - expectedChooserTop) <= 2
            }
            main {
                val chooser = WindowInspector.getGlobalWindowViews().first { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels
                }
                val params = chooser.layoutParams as WindowManager.LayoutParams
                check(params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0)
                check(params.windowAnimations == R.style.Animation_HuiyiReply)
                val position = IntArray(2)
                chooser.getLocationOnScreen(position)
                check(kotlin.math.abs(position[1] - expectedChooserTop) <= 2) {
                    "Long reply chooser moved away from keyboard edge: ${position[1]} vs $expectedChooserTop"
                }
                check(descendants(chooser).filterIsInstance<TextView>().none { it.text.toString() == "返回" })
            }
            SystemClock.sleep(500)
            automation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "long-capture-visible-reply.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val originalJpeg = imageField.get(AiEvents) as ByteArray
            main {
                val chooser = WindowInspector.getGlobalWindowViews().first { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels
                }
                check(chooser.findViewById<View>(android.R.id.button1).performClick())
            }
            waitFor("regenerated replies in the same chooser") {
                AiEvents.captureState.status == CaptureStatus.READY && AiEvents.captureState.attempt == 1 &&
                    WindowInspector.getGlobalWindowViews().any { root ->
                        val labels = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toSet()
                        "AI回复" in labels && "再次生成" in labels && "关闭" in labels && AiEvents.captureState.suggestions.any { it in labels }
                    }
            }
            main { check(originalJpeg.contentEquals(imageField.get(AiEvents) as ByteArray)) }
            val chosenReply = AiEvents.captureState.suggestions.first()
            val replyPoint = IntArray(2)
            main {
                val chooser = WindowInspector.getGlobalWindowViews().first { root ->
                    val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    "AI回复" in labels && "再次生成" in labels && "关闭" in labels
                }
                val replyView = descendants(chooser).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == chosenReply }
                replyView.getLocationOnScreen(replyPoint)
                replyPoint[0] += replyView.width / 2
                replyPoint[1] += replyView.height / 2
            }
            val replyTouchDown = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(replyTouchDown, SystemClock.uptimeMillis(), action, replyPoint[0].toFloat(), replyPoint[1].toFloat(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true))
                event.recycle()
            }
            waitFor("long reply inserts at the original cursor without tapping the editor") {
                val editor = sourceEditor() ?: return@waitFor false
                try {
                    editor.text?.toString() == "前缀${chosenReply}后缀" && AiTextReviewStore.current == null
                } finally {
                    editor.recycle()
                }
            }
            rows += buildJsonObject {
                put("case", "keyboard-hides-during-scroll-opens-same-reply-chooser")
                put("passed", true)
            }
            rows += buildJsonObject {
                put("case", "long-reply-auto-inserts-at-original-cursor")
                put("passed", true)
            }

            open(false)
            dragStart()
            waitFor("no scroll node fallback armed") { label() == "松手" }
            touch(MotionEvent.ACTION_UP, true)
            waitFor("stationary page reply") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            rows += buildJsonObject {
                put("case", "stationary-page-fallback-single-image")
                put("passed", true)
            }

            open()
            dragStart()
            touch(MotionEvent.ACTION_UP, true)
            waitFor("quick release") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            val quickImage = image("long-capture-quick-release")
            check(quickImage.height > 1800)
            quickImage.recycle()
            rows += buildJsonObject {
                put("case", "quick-upward-flick-starts-auto-capture")
                put("passed", true)
            }

            open()
            dragStart()
            waitFor("auto capture armed") { label() == "松手" }
            touch(MotionEvent.ACTION_UP, true)
            waitFor("bounded capture reply") { AiEvents.captureState.status == CaptureStatus.READY || AiEvents.captureState.status == CaptureStatus.ERROR }
            check(AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
            rows += buildJsonObject {
                put("case", "bounded-auto-collection")
                put("passed", true)
            }

            open()
            dragStart()
            touch(MotionEvent.ACTION_UP, true)
            waitFor("stop button while capturing") {
                AiEvents.captureState.status == CaptureStatus.CAPTURING && button().contentDescription.toString().contains("屏")
            }
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            SystemClock.sleep(1500)
            main { check(AiEvents.captureState.status == CaptureStatus.IDLE || AiEvents.captureState.status == CaptureStatus.CANCELLED) }
            rows += buildJsonObject {
                put("case", "tap-stop-cancels-auto-capture")
                put("passed", true)
            }

            main { AiConfigStore.save(context, AiConfigStore.load(context).copy(showReplyOptions = false)) }
            open(false)
            fillSourceEditor()
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
            waitFor("short tap directly inserts first reply") { firstReplyInserted() }
            rows += buildJsonObject {
                put("case", "direct-reply-short-tap-first-option")
                put("passed", true)
            }

            open(hideImeOnScroll = true)
            fillSourceEditor()
            dragStart()
            touch(MotionEvent.ACTION_UP, true)
            waitFor("long capture directly inserts first reply") { firstReplyInserted() }
            rows += buildJsonObject {
                put("case", "direct-reply-long-capture-first-option")
                put("passed", true)
            }

            open()
            main {
                AiQuickCapture.setRequested(context, false)
                check(input!!.service.aiInputController.startLongScreenAnalysis(1500) { } == null)
                check(AiEvents.captureState.status == CaptureStatus.IDLE)
            }
            rows += buildJsonObject {
                put("case", "disabled-permission-rejected")
                put("passed", true)
            }
            output.putString("stream", "PASS: held-finger accessibility scrolling, release stop, injected-swipe fallback and previous long-capture checks")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            output.putString("stream", error.stackTraceToString())
            automation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "long-capture-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            readyFile.delete()
            goFile.delete()
            main {
                input?.service?.aiInputController?.cancelLongScreenAnalysis()
                input?.service?.aiInputController?.let { dismiss.invoke(it, true) }
                AiQuickCapture.setRequested(context, quick)
            }
            AiConfigStore.save(context, original)
            HttpsURLConnection.setDefaultSSLSocketFactory(tlsOriginal)
            File(context.filesDir, "long-capture-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("passed", code == Activity.RESULT_OK)
                    put("external_ai_calls", 0)
                    put("synthetic_chat_only", true)
                    put("physical_phone_verified", false)
                    put("checks", JsonArray(rows))
                }.toString(),
            )
            test.finish(code, output)
        }
    }
}
