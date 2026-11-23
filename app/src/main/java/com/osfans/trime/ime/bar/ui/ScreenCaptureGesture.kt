// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.bar.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import splitties.dimensions.dp

/** Keeps the original toolbar touch target while the finger moves over the chat window. */
internal class ScreenCaptureGesture(
    private val view: View,
    private val start: (Int, (String?) -> Unit) -> String?,
    private val finish: (String) -> Unit,
    private val cancel: (String) -> Unit,
    private val label: (String?) -> Unit,
    private val voiceStart: ((String?) -> Unit) -> Long? = { null },
    private val voiceFinish: (Long) -> Unit = {},
    private val voiceCancel: (Long) -> Unit = {},
    private val voiceCancelling: (Long, Boolean) -> Unit = { _, _ -> },
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var token: String? = null
    private var runningToken: String? = null
    private var busy = false
    private var stoppingTap = false
    private var voiceMode = false
    private var voiceToken: Long? = null
    private var runningVoiceToken: Long? = null
    private var voiceCancelled = false
    private val hint = Runnable { if (view.isPressed && !dragged) label("上下滑") }

    init {
        view.contentDescription = "AI回复，点按截屏，上滑截长图，下滑说回复要求，松手截图回复"
        view.setOnLongClickListener {
            Toast.makeText(view.context, "短按截屏；上滑截长图；下滑说说你想怎么回，松手截图回复，左滑取消", Toast.LENGTH_LONG).show()
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility") // A short release delegates to performClick.
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            reset()
            dragged = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val active = runningToken
                val activeVoice = runningVoiceToken
                if (busy && token == null && voiceToken == null && (active != null || activeVoice != null)) {
                    stoppingTap = true
                    active?.let(cancel)
                    activeVoice?.let(voiceCancel)
                    view.isPressed = true
                    return true
                }
                stoppingTap = false
                reset()
                downX = event.rawX
                downY = event.rawY
                dragged = false
                voiceMode = false
                voiceCancelled = false
                view.isPressed = true
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.postDelayed(hint, 350)
            }

            MotionEvent.ACTION_MOVE -> {
                if (stoppingTap) return true
                if (!dragged && downY - event.rawY >= view.context.dp(24)) {
                    dragged = true
                    view.removeCallbacks(hint)
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    token = start(location[1]) { text ->
                        busy = text != null
                        if (text == null) runningToken = null
                        label(text)
                    }
                    runningToken = token
                    if (token == null) label(null)
                } else if (!dragged && event.rawY - downY >= view.context.dp(24)) {
                    dragged = true
                    voiceMode = true
                    view.removeCallbacks(hint)
                    voiceToken = voiceStart { text ->
                        busy = text != null
                        if (text == null) runningVoiceToken = null
                        label(if (text != null && voiceCancelled) "松手取消" else text)
                    }
                    runningVoiceToken = voiceToken
                    if (voiceToken == null) label(null)
                }
                if (voiceMode) {
                    val cancelling = event.rawX - downX <= -view.context.dp(72)
                    if (voiceCancelled != cancelling) {
                        voiceCancelled = cancelling
                        voiceToken?.let { voiceCancelling(it, cancelling) }
                        label(if (cancelling) "松手取消" else "说回复要求")
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(hint)
                view.isPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (stoppingTap) {
                    stoppingTap = false
                    return true
                }
                if (voiceMode) {
                    val currentVoice = voiceToken
                    voiceToken = null
                    if (voiceCancelled) currentVoice?.let(voiceCancel) else currentVoice?.let(voiceFinish)
                    return true
                }
                val current = token
                token = null
                if (dragged) {
                    current?.let(finish)
                } else {
                    label(null)
                    if (event.x in 0f..view.width.toFloat() && event.y in 0f..view.height.toFloat()) view.performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (voiceMode) {
                    // Losing the pointer/window must never submit an unfinished voice intent.
                    reset()
                    return true
                }
                // Some IME windows cancel the original pointer when it crosses into the app.
                // A drag already armed by the user is a release, not a request to discard it.
                val current = token
                if (dragged && current != null) {
                    view.removeCallbacks(hint)
                    view.isPressed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    token = null
                    finish(current)
                } else {
                    reset()
                }
            }
        }
        return true
    }

    fun reset() {
        view.removeCallbacks(hint)
        token?.let(cancel)
        voiceToken?.let(voiceCancel)
        voiceToken = null
        runningVoiceToken = null
        voiceCancelled = false
        // A released capture belongs to the accessibility service and must survive
        // the toolbar being detached when a chat app hides the keyboard on scroll.
        token = null
        runningToken = null
        busy = false
        label(null)
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun onDetach() {
        // A chat app can close the IME while a scroll action is running. The
        // physical UP will no longer reach this button, so finish this hold.
        token?.takeIf { dragged && !voiceMode }?.let {
            token = null
            finish(it)
        }
        reset()
    }
}
