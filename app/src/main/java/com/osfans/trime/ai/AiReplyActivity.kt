// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import com.osfans.trime.R

/** Shows the same reply chooser when the chat app has dismissed the keyboard. */
class AiReplyActivity : Activity() {
    private lateinit var chooser: AlertDialog
    private var shownState: CaptureState? = null
    private var sessionRequestId = ""
    private var handledDirectReply = false
    private var hasResumed = false
    private var removeAlignmentListener: (() -> Unit)? = null
    private val observer: (CaptureState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AiUiStyle.prepare(this, replyOverlay = true)
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        chooser = AiReplyChooser.create(this)
        chooser.setOnDismissListener { finish() }
        if (maybeInsertFirstReply(AiEvents.captureState)) return
        chooser.show()
        AiUiStyle.styleDialog(this, chooser)
        alignChooser()
        chooser.listView.setOnItemClickListener { _, _, index, _ ->
            val state = shownState ?: return@setOnItemClickListener
            if (state != AiEvents.captureState || index !in state.suggestions.indices) return@setOnItemClickListener
            val reply = state.suggestions[index]
            if (AiCaptureInsertion.canInsert(state) && AiCaptureInsertion.submit(state, reply)) {
                returnToInputApp()
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("回复建议", reply))
                Toast.makeText(this, "输入位置已失效，回复已复制", Toast.LENGTH_SHORT).show()
                returnToInputApp()
            }
        }
        chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val state = shownState ?: return@setOnClickListener
            if (!state.isInProgress && AiCaptureReplies.regenerate(this, state)) render(AiEvents.captureState)
        }
        chooser.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (AiQuickCapture.isSupported) AiAccessibilityCaptureService.cancelCurrent()
            chooser.dismiss()
        }
        render(AiEvents.captureState)
    }

    private fun alignChooser() {
        val dialogWindow = chooser.window ?: return
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.setDimAmount(0f)
        dialogWindow.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        dialogWindow.setWindowAnimations(R.style.Animation_HuiyiReply)
        val screenHeight = resources.displayMetrics.heightPixels
        val requestedTop = intent.getIntExtra(EXTRA_KEYBOARD_TOP, -1)
        val top = requestedTop.takeIf { it in 1 until screenHeight } ?: (screenHeight * 0.55f).toInt()
        val maxHeight = (screenHeight - top).coerceAtLeast(AiUiStyle.dp(this, 220))
        dialogWindow.attributes = dialogWindow.attributes.apply { y = top }
        val decor = dialogWindow.decorView
        val location = IntArray(2)
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!chooser.isShowing || decor.height == 0) return@OnPreDrawListener false
            decor.getLocationOnScreen(location)
            val offset = top - location[1]
            if (offset != 0 || decor.height > maxHeight) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    y += offset
                    if (decor.height > maxHeight) height = maxHeight
                }
                return@OnPreDrawListener false
            }
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(listener)
        removeAlignmentListener = {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    private fun render(state: CaptureState) {
        if (handledDirectReply || maybeInsertFirstReply(state)) return
        if (state.requestId.isNotEmpty()) sessionRequestId = state.requestId
        shownState = state
        AiReplyChooser.render(chooser, state)
        if (state.isInProgress) chooser.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        if (handledDirectReply) return
        AiEvents.addCaptureObserver(observer)
        render(AiEvents.captureState)
    }

    override fun onResume() {
        super.onResume()
        hasResumed = true
        if (handledDirectReply) returnToInputApp()
    }

    override fun onPause() {
        hasResumed = false
        super.onPause()
    }

    override fun onStop() {
        AiEvents.removeCaptureObserver(observer)
        if (!isChangingConfigurations) AiEvents.finishSession(sessionRequestId)
        super.onStop()
        if (!isChangingConfigurations) AiTextReviewStore.notifyReturnedToEditor()
    }

    override fun onDestroy() {
        removeAlignmentListener?.invoke()
        if (chooser.isShowing) chooser.dismiss()
        super.onDestroy()
    }

    private fun maybeInsertFirstReply(state: CaptureState): Boolean {
        if (AiConfigStore.showReplyOptions(this)) return false
        if (AiTextReviewStore.current?.result != null) {
            handledDirectReply = true
            if (hasResumed) returnToInputApp()
            return true
        }
        if (state.status != CaptureStatus.READY || state.suggestions.isEmpty()) return false
        handledDirectReply = true
        sessionRequestId = state.requestId
        val reply = state.suggestions.first()
        if (!AiCaptureInsertion.submit(state, reply)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("回复建议", reply))
            Toast.makeText(this, "输入位置已失效，回复已复制", Toast.LENGTH_SHORT).show()
        }
        AiCaptureService.dismissReadyNotification(this)
        if (hasResumed) returnToInputApp()
        return true
    }

    companion object {
        const val EXTRA_KEYBOARD_TOP = "ai_reply_keyboard_top"
    }
}
