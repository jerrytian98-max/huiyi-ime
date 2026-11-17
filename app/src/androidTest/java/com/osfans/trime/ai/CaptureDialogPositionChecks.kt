// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.core.view.children
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Measures real IME/dialog windows using synthetic replies, without capture or AI requests. */
internal object CaptureDialogPositionChecks {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    private fun inputView(): InputView? = WindowInspector.getGlobalWindowViews()
        .asSequence().flatMap(::descendants).filterIsInstance<InputView>()
        .firstOrNull { it.isShown && it.windowVisibility == View.VISIBLE && it.service.isInputViewShown && it.keyboardView.height > 0 }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun run(test: Instrumentation) {
        test.runOnMainSync { }
        val context = test.targetContext
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val draws = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val drawObservers = mutableListOf<Pair<View, ViewTreeObserver.OnDrawListener>>()
        var code = Activity.RESULT_OK
        var originalColor: String? = null
        var controller: AiInputController? = null
        var lastGeometry = "waiting for the keyboard"
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { runCatching(block).onFailure { failure = it } }
            failure?.let { throw it }
        }
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                SystemClock.sleep(100)
            }
            error("Capture reply geometry did not settle: $lastGeometry")
        }
        val show = AiInputController::class.java.getDeclaredMethod("showCaptureState", CaptureState::class.java).apply { isAccessible = true }
        val dismiss = AiInputController::class.java.getDeclaredMethod("dismissCaptureDialog", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val output = Bundle()
        try {
            main {
                val enter = AnimationUtils.loadAnimation(context, R.anim.huiyi_reply_enter)
                enter.initialize(900, 650, 1080, 2400)
                enter.startTime = 0
                val first = Transformation()
                val last = Transformation()
                enter.getTransformation(0, first)
                enter.getTransformation(250, last)
                val values = FloatArray(9)
                first.matrix.getValues(values)
                check(values[android.graphics.Matrix.MTRANS_Y] > 0 && first.alpha == 0f)
                last.matrix.getValues(values)
                check(values[android.graphics.Matrix.MTRANS_Y] == 0f && last.alpha == 1f)
                check(enter.duration == 200L)
            }
            main {
                context.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            waitFor { inputView() != null }
            test.waitForIdleSync()
            SystemClock.sleep(500)
            main {
                originalColor = ThemeManager.prefs.normalModeColor.getValue()
                controller = inputView()!!.service.aiInputController
            }
            for ((index, longReplies) in listOf(false, true, false).withIndex()) {
                val scheme = if (index == 2) "dark" else "default"
                main {
                    ColorManager.setColorScheme(scheme)
                    if (index != 1) dismiss.invoke(controller, true)
                    val analysis = if (index == 1) {
                        checkNotNull(AiEvents.beginRegeneration(AiEvents.captureState))
                    } else {
                        val id = "position-$index"
                        AiEvents.finishSession(AiEvents.captureState.requestId)
                        check(AiEvents.beginCapture(id))
                        checkNotNull(AiEvents.beginAnalysis(id, byteArrayOf(1)))
                    }
                    val replies = List(3) { "回复${it + 1}，明天下午三点见，我会提前发提纲。".repeat(if (longReplies) 12 else 1) }
                    check(AiEvents.completeAnalysis(analysis, replies))
                    show.invoke(controller, AiEvents.captureState)
                    val root = WindowInspector.getGlobalWindowViews().first { window ->
                        (window.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG &&
                            descendants(window).filterIsInstance<TextView>().any { it.text.toString() == "AI回复" }
                    }
                    check((root.layoutParams as WindowManager.LayoutParams).windowAnimations == R.style.Animation_HuiyiReply)
                    if (index == 1) check(drawObservers.single().first === root) { "Regeneration recreated the window" }
                    if (drawObservers.none { it.first === root }) {
                        val observer = ViewTreeObserver.OnDrawListener {
                            val keyboard = inputView()?.keyboardView ?: return@OnDrawListener
                            val kb = bounds(keyboard)
                            val panel = bounds(root)
                            draws += buildJsonObject {
                                put("case", "$scheme-first-window-$index")
                                put("keyboard_top", kb.top)
                                put("dialog_top", panel.top)
                                put("within_keyboard", panel.bottom <= kb.bottom)
                            }
                        }
                        root.viewTreeObserver.addOnDrawListener(observer)
                        drawObservers += root to observer
                    }
                }
                var measured: kotlinx.serialization.json.JsonObject? = null
                waitFor {
                    val keyboard = inputView()?.keyboardView ?: return@waitFor false
                    val dialog = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
                        (root.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG &&
                            descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "AI回复" }
                    } ?: return@waitFor false
                    val keyboardBounds = bounds(keyboard)
                    val dialogBounds = bounds(dialog)
                    lastGeometry = "keyboard=$keyboardBounds, dialog=$dialogBounds"
                    val close = dialog.findViewById<View>(android.R.id.button2) ?: return@waitFor false
                    if (dialogBounds.top != keyboardBounds.top || dialogBounds.bottom > keyboardBounds.bottom || close.height == 0) return@waitFor false
                    check(bounds(close).bottom <= keyboardBounds.bottom)
                    measured = buildJsonObject {
                        put("case", "$scheme-${if (longReplies) "long" else "short"}")
                        put("keyboard_top", keyboardBounds.top)
                        put("dialog_top", dialogBounds.top)
                        put("keyboard_bottom", keyboardBounds.bottom)
                        put("dialog_bottom", dialogBounds.bottom)
                        put("close_button_visible", true)
                    }
                    true
                }
                rows += checkNotNull(measured)
                SystemClock.sleep(300)
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir, "capture-position-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            check(draws.isNotEmpty()) { "No drawn frames observed" }
            draws.forEach {
                check(it["keyboard_top"] == it["dialog_top"]) { "Misplaced drawn frame: $it" }
                check(it["within_keyboard"].toString() == "true") { "Overflow in drawn frame: $it" }
            }
            output.putString("stream", "PASS: every drawn frame aligned before rendering, bottom-up 200ms animation, regeneration keeps one window, and long replies fit in both palettes")
        } catch (error: Throwable) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "capture-position-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            code = Activity.RESULT_CANCELED
            output.putString("stream", error.stackTraceToString())
        } finally {
            main {
                controller?.let { dismiss.invoke(it, true) }
                drawObservers.forEach { (view, observer) ->
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(observer)
                }
                originalColor?.let(ColorManager::setColorScheme)
                AiEvents.finishSession(AiEvents.captureState.requestId)
            }
            File(context.filesDir, "capture-position-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("passed", code == Activity.RESULT_OK)
                    put("external_ai_calls", 0)
                    put("checks", JsonArray(rows))
                    put("drawn_frames", JsonArray(draws))
                    put("enter_animation_ms", 200)
                    put("first_draw_position_checked", true)
                }.toString(),
            )
            test.finish(code, output)
        }
    }
}
