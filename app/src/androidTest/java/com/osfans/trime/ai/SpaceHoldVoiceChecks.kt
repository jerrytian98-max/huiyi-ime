// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.osfans.trime.BuildConfig
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Host drives real space-key touch events and injects only synthetic PCM into the emulator. */
internal object SpaceHoldVoiceChecks {
    fun run(test: Instrumentation) {
        // Instrumentation may start before Application.onCreate has finished on a cold boot.
        test.runOnMainSync { }
        val context = test.targetContext
        val original = AiConfigStore.load(context)
        val keyboard = AppPrefs.defaultInstance().keyboard
        val inlineMode = AppPrefs.defaultInstance().general.inlinePreeditMode
        val originalInlineMode = inlineMode.getValue()
        val originalLayout = keyboard.pinyinLayout.getValue()
        val originalTimeout = keyboard.longPressTimeout.getValue()
        val ready = File(context.filesDir, "space-hold-ready")
        val done = File(context.filesDir, "space-hold-done")
        val command = File(context.filesDir, "space-hold-command")
        val acknowledged = File(context.filesDir, "space-hold-command-done")
        var originalSchema = "rime_ice"
        var originalAscii = false
        var originalColor = "default"
        var established = false
        var code = Activity.RESULT_OK
        val output = Bundle()
        try {
            check(TencentSpeechDefaults.config.isReady)
            // A text-ready configuration makes an accidental call to the text cleanup path fail.
            val config = original.copy(
                cloudSpeech = true,
                speechProvider = AiSpeechProvider.TENCENT_FLASH,
                speechLanguage = "zh-CN",
                tencentSpeech = TencentSpeechDefaults.config.copy(model = "16k_zh"),
                endpoint = "https://example.invalid/chat",
                model = "must-not-call-text-model",
                apiKey = "synthetic-direct-dictation-key",
            )
            AiConfigStore.save(context, config)
            // Keys cache the first session. Share the IME-owned session so cleanup cannot
            // invalidate the session while the keyboard is still drawing.
            val session = RimeDaemon.createSession(TrimeInputMethodService::class.java.name)
            established = true
            runBlocking {
                session.runOnReady {
                    originalSchema = selectedSchemaId()
                    originalAscii = statusCached.isAsciiMode
                    clearComposition()
                    check(selectSchema("rime_ice"))
                    setRuntimeOption("ascii_mode", true)
                }
            }
            test.runOnMainSync {
                ThemeManager.init(context.resources.configuration)
                originalColor = ThemeManager.prefs.normalModeColor.getValue()
                ColorManager.setColorScheme("default")
                keyboard.pinyinLayout.setValue(AppPrefs.Keyboard.PinyinLayout.QWERTY)
                keyboard.longPressTimeout.setValue(300)
                context.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            done.delete()
            command.delete()
            acknowledged.delete()
            ready.writeText("READY")
            test.sendStatus(0, Bundle().apply { putString("stream", "SPACE_HOLD_READY") })
            val deadline = SystemClock.elapsedRealtime() + 1_200_000
            while (!done.exists() && SystemClock.elapsedRealtime() < deadline) {
                if (command.exists()) {
                    val value = command.readText().trim()
                    command.delete()
                    if (value == "dark-t9") {
                        test.runOnMainSync { ColorManager.setColorScheme("dark") }
                        runBlocking {
                            session.runOnReady {
                                clearComposition()
                                check(selectSchema("rime_ice_t9"))
                                setRuntimeOption("ascii_mode", false)
                            }
                        }
                    }
                    if (value == "inline-on") {
                        test.runOnMainSync { inlineMode.setValue(InlinePreeditMode.COMMIT_TEXT_PREVIEW) }
                    }
                    acknowledged.writeText(value)
                }
                SystemClock.sleep(100)
            }
            check(done.exists() && done.readText().trim() == "PASS") { "Space-key UI checks did not complete" }
            output.putString("stream", "PASS: host space-key touch, release, cancellation and direct transcript insertion checks")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            output.putString("stream", error.stackTraceToString())
        } finally {
            ready.delete()
            done.delete()
            AiConfigStore.save(context, original)
            if (established) {
                runCatching {
                    runBlocking {
                        RimeDaemon.createSession(TrimeInputMethodService::class.java.name).runOnReady {
                            clearComposition()
                            selectSchema(originalSchema)
                            setRuntimeOption("ascii_mode", originalAscii)
                        }
                    }
                }
            }
            test.runOnMainSync {
                keyboard.pinyinLayout.setValue(originalLayout)
                keyboard.longPressTimeout.setValue(originalTimeout)
                inlineMode.setValue(originalInlineMode)
                ColorManager.setColorScheme(originalColor)
            }
            File(context.filesDir, "space-hold-device-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("api", android.os.Build.VERSION.SDK_INT)
                    put("passed", code == Activity.RESULT_OK)
                    put("speech_provider", "Tencent Flash ASR 16k_zh")
                    put("text_model_required", false)
                    put("physical_microphone", false)
                }.toString(),
            )
            test.finish(code, output)
        }
    }
}
