// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.children
import com.osfans.trime.BuildConfig
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** UI uses synthetic levels; real touch checks exercise AudioRecord with a local-only test endpoint. */
internal object HeldVoiceWaveformChecks {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

    fun run(test: Instrumentation) {
        test.runOnMainSync { }
        val context = test.targetContext
        val originalConfig = AiConfigStore.load(context)
        val prefs = AppPrefs.defaultInstance().keyboard
        val originalTimeout = prefs.longPressTimeout.getValue()
        val originalLayout = prefs.pinyinLayout.getValue()
        val originalColor = ThemeManager.prefs.normalModeColor.getValue()
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var code = Activity.RESULT_OK
        val output = Bundle()
        var input: InputView? = null
        var originalSchema: String? = null
        var originalAscii = false
        val session = RimeDaemon.createSession(TrimeInputMethodService::class.java.name)
        fun main(block: () -> Unit) = test.runOnMainSync(block)
        fun findInput(): InputView? = WindowInspector.getGlobalWindowViews().asSequence()
            .flatMap(::descendants).filterIsInstance<InputView>()
            .firstOrNull { it.isShown && it.windowVisibility == View.VISIBLE && it.service.isInputViewShown && it.keyboardView.height > 0 }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                SystemClock.sleep(50)
            }
            error("Timed out: $label")
        }
        fun wave(): HeldVoiceWaveformView = descendants(checkNotNull(input)).filterIsInstance<HeldVoiceWaveformView>().first()
        fun snapshot(name: String) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun render(): Bitmap {
            var bitmap: Bitmap? = null
            main {
                val view = wave()
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(checkNotNull(bitmap)))
            }
            return checkNotNull(bitmap)
        }
        fun ink(bitmap: Bitmap): Int {
            val background = bitmap.getPixel(0, 0)
            var pixels = 0
            for (y in (bitmap.height * .29f).toInt() until (bitmap.height * .67f).toInt()) {
                for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != background) pixels++
            }
            return pixels
        }
        var downTime = 0L
        var touchX = 0f
        var touchY = 0f
        fun touch(action: Int, x: Float = touchX, y: Float = touchY) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(test.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        try {
            // Never send audio or credentials to an external service in these UI checks.
            AiConfigStore.save(context, originalConfig.copy(cloudSpeech = true, speechProvider = AiSpeechProvider.OPENAI_COMPATIBLE, speechEndpoint = "https://127.0.0.1:1/transcriptions", speechModel = "ui-only", speechApiKey = "synthetic-waveform-only"))
            runBlocking {
                session.runOnReady {
                    originalSchema = selectedSchemaId()
                    originalAscii = statusCached.isAsciiMode
                    clearComposition()
                    check(selectSchema("rime_ice"))
                    setRuntimeOption("ascii_mode", true)
                }
            }
            main {
                prefs.longPressTimeout.setValue(300)
                context.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            waitFor("visible editor") {
                input = findInput()
                input != null
            }
            SystemClock.sleep(700)
            for ((index, pair) in listOf("default" to "rime_ice", "dark" to "rime_ice", "default" to "rime_ice_t9", "dark" to "rime_ice_t9").withIndex()) {
                val (scheme, schema) = pair
                runBlocking {
                    session.runOnReady {
                        clearComposition()
                        check(selectSchema(schema))
                        setRuntimeOption("ascii_mode", false)
                    }
                }
                main { ColorManager.setColorScheme(scheme) }
                SystemClock.sleep(600)
                waitFor("space key") {
                    input = findInput()
                    input != null && descendants(input!!).filterIsInstance<KeyView>().any { it.contentDescription?.startsWith("空格") == true }
                }
                snapshot("voice-waveform-idle-$index.png")
                main { check(KeyboardWindow.currentKeyboard.keys.any { it.getLabel() == "ABC" } == (schema == "rime_ice_t9")) { "Unexpected keyboard layout for $schema" } }
                var keyboardHeight = 0
                main {
                    keyboardHeight = input!!.keyboardView.height
                    input!!.showHeldVoiceRecording(true)
                    input!!.updateHeldVoiceRecording(seconds = 3, level = 0f)
                }
                waitFor("waveform layout") { wave().height > 0 && wave().width == input!!.keyboardView.width }
                SystemClock.sleep(300)
                val quiet = render()
                main { input!!.updateHeldVoiceRecording(level = 0.9f) }
                SystemClock.sleep(450)
                val loud = render()
                SystemClock.sleep(180)
                val later = render()
                check(ink(loud) > ink(quiet) * 2) { "Waveform did not react to volume" }
                check(!loud.sameAs(later)) { "Waveform did not animate" }
                main {
                    check(input!!.keyboardView.height == keyboardHeight) { "Keyboard geometry shifted" }
                    check(!wave().isClickable && !wave().isFocusable)
                }
                snapshot("voice-waveform-active-$index.png")
                rows += buildJsonObject {
                    put("case", "$scheme-$schema")
                    put("quiet_ink", ink(quiet))
                    put("loud_ink", ink(loud))
                    put("animated", true)
                    put("height_preserved", true)
                }
                quiet.recycle()
                loud.recycle()
                later.recycle()
                main {
                    input!!.showHeldVoiceRecording(true, atLimit = true)
                    check(wave().contentDescription.contains("60秒"))
                    input!!.showHeldVoiceRecording(false)
                    check(wave().visibility == View.GONE)
                }

                // Check that the new visible layer does not consume the original Space touch.
                var space: KeyView? = null
                main {
                    space = descendants(input!!).filterIsInstance<KeyView>().first { it.contentDescription?.startsWith("空格") == true }
                    val xy = IntArray(2)
                    space!!.getLocationOnScreen(xy)
                    touchX = xy[0] + space!!.width / 2f
                    touchY = xy[1] + space!!.height / 2f
                }
                touch(MotionEvent.ACTION_DOWN)
                waitFor("real long-hold recording") { wave().visibility == View.VISIBLE }
                if (index % 2 == 0) {
                    touch(MotionEvent.ACTION_UP)
                    waitFor("release restores keyboard") { wave().visibility == View.GONE && space?.isPressed == false }
                } else {
                    touch(MotionEvent.ACTION_MOVE, touchX, touchY - 240)
                    waitFor("slide cancels recording") { wave().visibility == View.GONE }
                    touch(MotionEvent.ACTION_UP, touchX, touchY - 240)
                }
                main { input!!.service.aiInputController.cancelHeldVoice() }
                rows += buildJsonObject {
                    put("case", "$scheme-$schema-touch")
                    put("gesture", if (index % 2 == 0) "release" else "slide-cancel")
                    put("passed", true)
                }
            }
            // Cursor/editor changes must also remove the animation and cancel its recording.
            main { input!!.service.aiInputController.startHeldVoice { } }
            waitFor("record before selection change") { wave().visibility == View.VISIBLE }
            main { input!!.service.aiInputController.onSelectionChanged() }
            waitFor("selection cleanup") { wave().visibility == View.GONE }
            rows += buildJsonObject {
                put("case", "selection-change-cleanup")
                put("passed", true)
            }

            // Configuration failures also restore the panel without an external request.
            AiConfigStore.save(context, originalConfig.copy(cloudSpeech = false))
            main { input!!.service.aiInputController.startHeldVoice { } }
            waitFor("failure cleanup") { wave().visibility == View.GONE }
            rows += buildJsonObject {
                put("case", "configuration-error-cleanup")
                put("passed", true)
            }
            AiConfigStore.save(context, originalConfig.copy(cloudSpeech = true, speechProvider = AiSpeechProvider.OPENAI_COMPATIBLE, speechEndpoint = "https://127.0.0.1:1/transcriptions", speechModel = "ui-only", speechApiKey = "synthetic-waveform-only"))
            main { input!!.service.aiInputController.startHeldVoice { } }
            waitFor("record before hiding keyboard") { wave().visibility == View.VISIBLE }
            main { input!!.service.requestHideSelf(0) }
            waitFor("keyboard hidden cleanup") { wave().visibility == View.GONE }
            rows += buildJsonObject {
                put("case", "keyboard-hidden-cleanup")
                put("passed", true)
            }
            output.putString("stream", "PASS: four palette/layout combinations, PCM-level animation, stable geometry, real hold/release/slide, and error cleanup")
        } catch (error: Throwable) {
            snapshot("voice-waveform-failure.png")
            code = Activity.RESULT_CANCELED
            output.putString("stream", error.stackTraceToString())
        } finally {
            main {
                input?.service?.aiInputController?.cancelHeldVoice()
                input?.showHeldVoiceRecording(false)
                prefs.longPressTimeout.setValue(originalTimeout)
                prefs.pinyinLayout.setValue(originalLayout)
                ColorManager.setColorScheme(originalColor)
            }
            runCatching {
                runBlocking {
                    session.runOnReady {
                        clearComposition()
                        originalSchema?.let { selectSchema(it) }
                        setRuntimeOption("ascii_mode", originalAscii)
                    }
                }
            }
            AiConfigStore.save(context, originalConfig)
            File(context.filesDir, "voice-waveform-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("passed", code == Activity.RESULT_OK)
                    put("external_ai_calls", 0)
                    put("synthetic_animation_levels", true)
                    put("physical_microphone_verified", false)
                    put("checks", JsonArray(rows))
                }.toString(),
            )
            test.finish(code, output)
        }
    }
}
