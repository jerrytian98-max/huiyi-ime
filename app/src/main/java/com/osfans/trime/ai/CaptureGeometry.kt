// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

/** Physical screenshot coordinates; accessibility root-node bounds are deliberately not an input. */
internal object CaptureGeometry {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun intersect(other: Bounds) = Bounds(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        val valid get() = width > 0 && height > 0
    }

    fun plan(width: Int, height: Int, keyboardTop: Int, statusTop: Int, window: Bounds?, editor: Bounds?, composerReserve: Int): Bounds {
        check(width > 0 && height > 0 && keyboardTop > 0) { "键盘位置尚未就绪，请重新上拖AI回复" }
        val screen = Bounds(0, statusTop.coerceIn(0, height / 5), width, minOf(height, keyboardTop))
        val visible = if (window?.valid == true) screen.intersect(window) else screen
        check(visible.valid) { "聊天窗口不在键盘上方，请回到聊天页面后重试" }
        // Web/custom editors sometimes expose the entire window, or a stale box near the top.
        val trustworthyEditor = editor?.valid == true && editor.width >= visible.width / 5 &&
            editor.height <= visible.height / 3 && editor.top >= visible.top + visible.height / 2 &&
            editor.bottom <= keyboardTop + composerReserve && editor.intersect(visible).valid
        val bottom = if (trustworthyEditor) editor!!.top else visible.bottom - composerReserve.coerceAtMost(visible.height / 5)
        return visible.copy(bottom = bottom).also {
            check(it.width > 0 && it.height > 100) { "当前可见聊天区域过小，请调整键盘高度后重试" }
        }
    }
}
