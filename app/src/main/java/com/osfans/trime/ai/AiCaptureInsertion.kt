// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

/** Keeps the original editor in memory so a notification result can return through the IME. */
internal object AiCaptureInsertion {
    private var requestId = ""
    private var target: AiEditTarget? = null

    fun remember(id: String, editor: AiEditTarget) {
        requestId = id
        target = editor
    }

    fun canInsert(state: CaptureState): Boolean = target != null && state.requestId == requestId && state.status == CaptureStatus.READY

    fun submit(state: CaptureState, text: String): Boolean {
        val editor = target ?: return false
        if (!canInsert(state) || state != AiEvents.captureState || text.isBlank() || text !in state.suggestions) return false
        // Invalidate before notifying observers: two taps must never insert twice.
        target = null
        requestId = ""
        val id = AiTextReviewStore.begin(editor, "", text)
        val accepted = AiTextReviewStore.submit(id, text)
        if (accepted) AiEvents.clearSuggestions()
        return accepted
    }
}
