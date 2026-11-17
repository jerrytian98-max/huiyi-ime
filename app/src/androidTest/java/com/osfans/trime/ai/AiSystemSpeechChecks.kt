// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Reproduces client-error recovery through Android's actual RecognitionService binding. */
internal object AiSystemSpeechChecks {
    fun run(instrumentation: Instrumentation, interactive: Boolean) {
        val context = instrumentation.targetContext
        val checks = mutableListOf<String>()
        val original = AiConfigStore.load(context)
        var code = Activity.RESULT_OK
        val output = Bundle()
        try {
            fun recognize(mode: String, expectedText: String? = null, expectedError: String? = null, expectedPartial: String = "", finish: Boolean = false) {
                val done = CountDownLatch(1)
                val calls = AtomicInteger()
                var text = ""
                var message = ""
                var partial = ""
                lateinit var session: AiSystemSpeech
                instrumentation.runOnMainSync {
                    session = AiSystemSpeech(
                        context,
                        mode,
                        onStatus = { _, ready ->
                            if (finish && ready) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    session.finish()
                                    session.finish()
                                }
                            }
                        },
                        onText = {
                            text = it
                            calls.incrementAndGet()
                            done.countDown()
                        },
                        onFailure = { error, interim ->
                            message = error
                            partial = interim
                            calls.incrementAndGet()
                            done.countDown()
                        },
                    )
                    session.start()
                }
                try {
                    check(done.await(25, TimeUnit.SECONDS)) { "No terminal callback for $mode" }
                    if (expectedText != null) check(text == expectedText) { "Unexpected transcript for $mode" }
                    if (expectedError != null) check(message.contains(expectedError)) { "Unexpected failure for $mode: $message" }
                    check(partial == expectedPartial)
                    SystemClock.sleep(600)
                    check(calls.get() == 1) { "Duplicate terminal callback" }
                } finally {
                    instrumentation.runOnMainSync { session.close() }
                }
            }
            recognize("test-client-once", expectedText = "嗯明天下午三点那个开会")
            checks += "PASS: ERROR_CLIENT (5) before readiness reconnects once and returns one transcript"
            recognize("test-client-always", expectedError = "连接失败（5）")
            checks += "PASS: persistent client error terminates with recovery instructions instead of an infinite retry"
            recognize("test-partial-error", expectedError = "连接失败（5）", expectedPartial = "保留临时文字")
            checks += "PASS: partial text survives a client error without automatic rerecording"
            recognize("test-permission", expectedError = "麦克风")
            checks += "PASS: permission failure is actionable and does not trigger engine switching"
            recognize("test-finish", expectedText = "点击完成后的识别文字", finish = true)
            checks += "PASS: Finish waits for the final asynchronous result and ignores duplicate finish requests"
            recognize("test-silent", expectedError = "响应超时")
            checks += "PASS: a service that never becomes ready times out after bounded reconnect"
            val calls = AtomicInteger()
            lateinit var cancelled: AiSystemSpeech
            instrumentation.runOnMainSync {
                cancelled = AiSystemSpeech(context, "test-client-once-cancel", { _, _ -> }, { calls.incrementAndGet() }, { _, _ -> calls.incrementAndGet() })
                cancelled.start()
            }
            SystemClock.sleep(180)
            instrumentation.runOnMainSync { cancelled.close() }
            SystemClock.sleep(2200)
            check(calls.get() == 0)
            checks += "PASS: cancellation during reconnect suppresses late results"
            if (interactive) {
                AiConfigStore.save(context, original.copy(endpoint = "https://example.invalid/chat", model = "", visionModel = "", apiKey = "", cloudSpeech = false, speechLanguage = "test-client-once-ui"))
                val modeFile = File(context.filesDir, "system-speech-ui-mode")
                val done = File(context.filesDir, "system-speech-ui-done")
                modeFile.delete()
                done.delete()
                instrumentation.runOnMainSync {
                    context.startActivity(Intent().setClassName(instrumentation.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                File(context.filesDir, "system-speech-ui-ready").writeText("READY")
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYSTEM_SPEECH_UI_READY") })
                val deadline = SystemClock.elapsedRealtime() + 900_000
                while (!done.exists() && SystemClock.elapsedRealtime() < deadline) {
                    if (modeFile.exists()) {
                        val mode = modeFile.readText().trim()
                        modeFile.delete()
                        AiConfigStore.save(context, AiConfigStore.load(context).copy(speechLanguage = mode))
                    }
                    SystemClock.sleep(100)
                }
                check(done.exists()) { "UI validation did not finish" }
                done.delete()
                File(context.filesDir, "system-speech-ui-ready").delete()
            }
            File(context.filesDir, "system-speech-result.json").writeText(
                buildJsonObject {
                    put("version", "0.1.18-ai")
                    put("api", android.os.Build.VERSION.SDK_INT)
                    put("real_audio", false)
                    put("network_requests", 0)
                    put("checks", JsonArray(checks.map(::JsonPrimitive)))
                }.toString(),
            )
            output.putString("stream", checks.joinToString("\n"))
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            output.putString("stream", error.stackTraceToString())
        } finally {
            AiConfigStore.save(context, original)
            instrumentation.finish(code, output)
        }
    }
}
