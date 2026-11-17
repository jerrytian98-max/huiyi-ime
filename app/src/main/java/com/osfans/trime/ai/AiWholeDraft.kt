// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

/** A full editor snapshot verified against text on both sides of the cursor. */
internal data class AiWholeDraft(
    val text: String,
    val cursor: Int,
) {
    fun matches(target: AiEditTarget): Boolean = target.selected.isNullOrEmpty() && target.before == text.substring(0, cursor).takeLast(64) &&
        target.after == text.substring(cursor).take(64)

    companion object {
        fun verify(text: String?, startOffset: Int, selectionStart: Int, selectionEnd: Int, before: String?, after: String?): AiWholeDraft? {
            if (text == null || text.length > AiClient.MAX_TEXT_LENGTH || startOffset != 0 ||
                selectionStart != selectionEnd || selectionStart !in 0..text.length || before == null || after == null
            ) {
                return null
            }
            if (before != text.substring(0, selectionStart) || after != text.substring(selectionEnd)) return null
            return AiWholeDraft(text, selectionStart)
        }
    }
}
