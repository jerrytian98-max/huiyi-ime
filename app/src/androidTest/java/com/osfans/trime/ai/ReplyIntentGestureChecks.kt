// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.BuildConfig
import com.osfans.trime.ime.bar.ui.ScreenCaptureGesture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Exercises actual toolbar gesture routing without a microphone or network provider. */
internal object ReplyIntentGestureChecks {
    fun run(test: Instrumentation) {
        val rows = mutableListOf<String>()
        var failure: Throwable? = null
        test.runOnMainSync {
            try {
                val view = View(test.targetContext)
                val density = view.resources.displayMetrics.density
                view.layout(0, 0, (120 * density).toInt(), (56 * density).toInt())
                var taps = 0
                var captures = 0
                var captureFinishes = 0
                var voices = 0
                var voiceFinishes = 0
                var voiceCancels = 0
                var cancelling = false
                var status: ((String?) -> Unit)? = null
                view.setOnClickListener { taps++ }
                val gesture = ScreenCaptureGesture(
                    view,
                    start = { _, callback ->
                        captures++
                        status = callback
                        callback("准备中")
                        "capture"
                    },
                    finish = {
                        captureFinishes++
                        status?.invoke(null)
                    },
                    cancel = { status?.invoke(null) },
                    label = {},
                    voiceStart = { callback ->
                        voices++
                        status = callback
                        callback("准备录音")
                        7L
                    },
                    voiceFinish = {
                        check(it == 7L)
                        voiceFinishes++
                        status?.invoke(null)
                    },
                    voiceCancel = {
                        check(it == 7L)
                        voiceCancels++
                        status?.invoke(null)
                    },
                    voiceCancelling = { _, value -> cancelling = value },
                )
                fun touch(action: Int, x: Float = 96f, y: Float = 16f) {
                    val event = MotionEvent.obtain(0, 100, action, x * density, y * density, 0)
                    try {
                        gesture.onTouch(view, event)
                    } finally {
                        event.recycle()
                    }
                }
                fun down() = touch(MotionEvent.ACTION_DOWN)
                fun move(x: Float = 96f, y: Float) = touch(MotionEvent.ACTION_MOVE, x, y)
                down()
                touch(MotionEvent.ACTION_UP)
                check(taps == 1 && captures == 0 && voices == 0)
                rows += "PASS: short tap retains ordinary screenshot click"
                down()
                move(y = -16f)
                move(y = 65f)
                touch(MotionEvent.ACTION_UP)
                check(captures == 1 && captureFinishes == 1 && voices == 0)
                rows += "PASS: upward capture remains direction-locked and finishes on release"
                down()
                move(y = 48f)
                move(y = -25f)
                touch(MotionEvent.ACTION_UP)
                check(voices == 1 && voiceFinishes == 1 && captures == 1 && taps == 1)
                rows += "PASS: downward voice remains direction-locked and submits only on release"
                down()
                move(y = 48f)
                move(x = 0f, y = 60f)
                touch(MotionEvent.ACTION_UP, 0f, 60f)
                check(cancelling && voiceCancels == 1 && voiceFinishes == 1)
                rows += "PASS: left slide then release cancels without screenshot or ordinary click"
                down()
                move(y = 48f)
                move(x = 0f, y = 60f)
                move(y = 60f)
                touch(MotionEvent.ACTION_UP)
                check(!cancelling && voiceFinishes == 2)
                rows += "PASS: moving back from the cancel region resumes the same recording"
                down()
                move(y = 48f)
                touch(MotionEvent.ACTION_CANCEL)
                check(voiceCancels == 2 && voiceFinishes == 2)
                down()
                move(y = 48f)
                gesture.onDetach()
                check(voiceCancels == 3 && voiceFinishes == 2)
                rows += "PASS: pointer cancellation and keyboard detach discard voice intent"
            } catch (error: Throwable) {
                failure = error
            }
        }
        val result = buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("passed", failure == null)
            put("external_ai_calls", 0)
            put("checks", JsonArray(rows.map(::JsonPrimitive)))
        }
        File(test.targetContext.filesDir, "reply-intent-gesture-result.json").writeText(result.toString())
        test.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure?.stackTraceToString() ?: result.toString()) })
    }
}
