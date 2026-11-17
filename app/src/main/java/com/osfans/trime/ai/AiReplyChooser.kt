// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter

/** One chooser layout for replies shown by the IME and by the transparent result host. */
internal object AiReplyChooser {
    fun create(context: Context): AlertDialog = AiUiStyle.dialog(context).setTitle("AI回复")
        .setItems(emptyArray<String>(), null)
        .setPositiveButton("再次生成", null)
        .setNeutralButton("取消AI回复", null)
        .setNegativeButton("关闭", null)
        .create()

    fun render(dialog: AlertDialog, state: CaptureState) {
        val rows = state.suggestions.ifEmpty { listOf(state.message.ifBlank { "请重新点击AI回复" }) }
        dialog.listView.adapter = ArrayAdapter(dialog.context, android.R.layout.simple_list_item_1, rows)
        dialog.listView.isEnabled = state.suggestions.isNotEmpty()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = if (state.isInProgress) "查看进度" else "再次生成"
            visibility = if (state.isInProgress || AiEvents.canRegenerate(state)) View.VISIBLE else View.GONE
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility =
            if (state.isInProgress && AiQuickCapture.isSupported && AiAccessibilityCaptureService.ownsRequest(state.requestId)) View.VISIBLE else View.GONE
    }
}
