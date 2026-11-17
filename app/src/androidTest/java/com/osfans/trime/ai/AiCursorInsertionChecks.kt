// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.File

/** Production reply/review pages -> real IME -> a separate test application's InputConnection. */
object AiCursorInsertionChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val originalConfig = AiConfigStore.load(context)
        AiConfigStore.save(context, originalConfig.copy(showReplyOptions = true))
        val stage = File(context.filesDir, "cursor-insertion-stage")
        val signal = File(context.filesDir, "cursor-insertion-continue")
        val driver = ReviewEditorTestDriver(context)
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        val checks = mutableListOf<String>()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun next(name: String) {
            signal.delete()
            stage.writeText(name)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", name + "\n") })
            val end = SystemClock.elapsedRealtime() + 180_000
            while (!signal.exists() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            check(signal.exists()) { "Host did not finish $name" }
        }
        fun fixture() {
            main {
                context.startActivity(
                    Intent().setClassName(instrumentation.context.packageName, TestChatActivity::class.java.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
        fun replies(id: String, target: AiEditTarget) {
            main {
                AiEvents.clearSuggestions()
                AiCaptureInsertion.remember(id, target)
                check(AiEvents.beginCapture(id))
                check(AiEvents.publishSuggestions(id, listOf("好的，我会提前发提纲。", "收到，我确认后回复你。")))
                context.startActivity(Intent(context, AiReplyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        val editor = AiEditTarget(instrumentation.context.packageName, 12345, "前缀", null, "后缀")
        try {
            fixture()
            next("INSERT_PREPARE_MIDDLE")
            replies("middle", editor)
            next("INSERT_REPLY_MIDDLE")
            main { check(AiTextReviewStore.current == null && AiEvents.captureState.status == CaptureStatus.IDLE) }
            checks += "PASS: reply page returns to source app and inserts between prefix and suffix via the IME; clipboard unchanged"

            fixture()
            next("INSERT_PREPARE_MOVE_CANCEL")
            replies("move-cancel", editor)
            next("INSERT_MOVE_CANCEL")
            main { check(AiTextReviewStore.current == null) }
            checks += "PASS: moving cursor requires explicit confirmation; cancel preserves original text"

            fixture()
            next("INSERT_PREPARE_MOVE_ACCEPT")
            replies("move-accept", editor)
            next("INSERT_MOVE_ACCEPT")
            main { check(AiTextReviewStore.current == null) }
            checks += "PASS: user-approved destination inserts at the new cursor without replacing surrounding text"

            fixture()
            next("INSERT_PREPARE_HIDDEN")
            replies("hidden", editor)
            next("INSERT_REPLY_HIDDEN")
            main { check(AiTextReviewStore.current == null && AiEvents.captureState.status == CaptureStatus.IDLE) }
            checks += "PASS: reply page restores a dismissed keyboard and inserts at the original cursor without another tap"

            fixture()
            next("INSERT_PREPARE_DIRECT_PAGE")
            AiConfigStore.save(context, originalConfig.copy(showReplyOptions = false))
            replies("direct-page", editor)
            next("INSERT_DIRECT_PAGE")
            main { check(AiTextReviewStore.current == null && AiEvents.captureState.status == CaptureStatus.IDLE) }
            checks += "PASS: direct mode skips the result chooser and inserts the first reply through the page fallback"
            AiConfigStore.save(context, originalConfig.copy(showReplyOptions = true))

            fixture()
            next("INSERT_PREPARE_REVIEW")
            main {
                val target = AiEditTarget(instrumentation.context.packageName, 12345, "甲", "原文", "乙")
                val id = AiTextReviewStore.begin(target, "原文", "AI 整理稿")
                context.startActivity(
                    Intent(context, AiTextReviewActivity::class.java)
                        .putExtra(AiTextReviewActivity.EXTRA_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            next("INSERT_REVIEW_CONFIRM")
            main { check(AiTextReviewStore.current == null) }
            checks += "PASS: edited AI draft returns to source and replaces only the selected text"
            File(context.filesDir, "cursor-insertion-result.json").writeText(
                kotlinx.serialization.json.buildJsonObject {
                    put("version", kotlinx.serialization.json.JsonPrimitive(com.osfans.trime.BuildConfig.VERSION_NAME))
                    put("ai_network_requests", kotlinx.serialization.json.JsonPrimitive(0))
                    put("checks", kotlinx.serialization.json.JsonArray(checks.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }.toString(),
            )
            result.putString("stream", checks.joinToString("\n"))
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            AiConfigStore.save(context, originalConfig)
            driver.close()
            main {
                AiTextReviewStore.current?.let { AiTextReviewStore.clear(it.id) }
                AiEvents.clearSuggestions()
            }
            stage.delete()
            signal.delete()
        }
        instrumentation.finish(resultCode, result)
    }
}
