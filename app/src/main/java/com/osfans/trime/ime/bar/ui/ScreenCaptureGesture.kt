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
) : View.OnTouchListener {
    private var downY = 0f
    private var dragged = false
    private var token: String? = null
    private var runningToken: String? = null
    private var busy = false
    private var stoppingTap = false
    private val hint = Runnable { if (view.isPressed && !dragged) label("上拖") }

    init {
        view.contentDescription = "AI回复，点按截当前屏幕；上拖后按住滚动截图，松手结束；不支持的页面松手后继续"
        view.setOnLongClickListener {
            Toast.makeText(view.context, "上拖后按住滚动截长图，松手生成回复；不支持的页面松手后自动截图", Toast.LENGTH_LONG).show()
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
                if (busy && active != null && token == null) {
                    stoppingTap = true
                    cancel(active)
                    view.isPressed = true
                    return true
                }
                stoppingTap = false
                reset()
                downY = event.rawY
                dragged = false
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
        token?.takeIf { dragged }?.let {
            token = null
            finish(it)
        }
        reset()
    }
}
