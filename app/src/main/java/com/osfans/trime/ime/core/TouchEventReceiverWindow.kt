/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private var isWindowShowing = false

    private val cachedLocation = intArrayOf(0, 0)
    private var pendingBounds: IntArray? = null

    init {
        contentView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post {
                    pendingBounds?.let { bounds ->
                        if (bounds[2] > 0 && bounds[3] > 0) {
                            showAt(bounds[0], bounds[1], bounds[2], bounds[3])
                        } else {
                            show()
                        }
                    }
                }
            }
            override fun onViewDetachedFromWindow(view: View) {
                isWindowShowing = false
                window.dismiss()
            }
        })
    }

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        pendingBounds = intArrayOf(x, y, w, h)
        // Rime messages can arrive before attach or after the input view is replaced.
        if (!contentView.isAttachedToWindow || contentView.windowToken == null || w <= 0 || h <= 0) return
        try {
            if (window.isShowing) {
                window.update(x, y, w, h)
            } else {
                window.width = w
                window.height = h
                window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
            }
            isWindowShowing = true
        } catch (_: WindowManager.BadTokenException) {
            // The system may invalidate the token while an editor/window is closing.
            runCatching { window.dismiss() }
            isWindowShowing = false
        }
    }

    fun show() {
        val (x, y) = cachedLocation.also { contentView.getLocationInWindow(it) }
        val width = contentView.width
        val height = contentView.height
        showAt(x, y, width, height)
    }

    fun dismiss() {
        pendingBounds = null
        if (isWindowShowing) {
            isWindowShowing = false
            window.dismiss()
        }
    }
}
