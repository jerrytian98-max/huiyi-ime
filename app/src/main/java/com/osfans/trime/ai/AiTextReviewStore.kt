// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import java.util.UUID

internal data class AiEditTarget(
    val packageName: String,
    val fieldId: Int,
    val before: String?,
    val selected: String?,
    val after: String?,
)

internal data class AiTextReviewSession(
    val id: String,
    val target: AiEditTarget,
    val original: String,
    val draft: String,
    val result: String? = null,
    val wholeDraft: AiWholeDraft? = null,
    val returnedToEditor: Boolean = false,
)

/** Main-thread, memory-only handoff. Intent extras contain only an opaque request ID. */
internal object AiTextReviewStore {
    var current: AiTextReviewSession? = null
        private set
    private val observers = linkedSetOf<() -> Unit>()

    fun addObserver(observer: () -> Unit) {
        observers.add(observer)
    }
    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }

    /** The result page has left the foreground; the IME may now restore its source editor. */
    fun notifyReturnedToEditor() {
        val session = current ?: return
        if (session.result != null && !session.returnedToEditor) publish(session.copy(returnedToEditor = true))
    }

    fun begin(target: AiEditTarget, original: String, draft: String, wholeDraft: AiWholeDraft? = null): String {
        val id = UUID.randomUUID().toString()
        publish(AiTextReviewSession(id, target, original, draft, wholeDraft = wholeDraft))
        return id
    }
    fun edit(id: String, draft: String) {
        val session = current ?: return
        if (session.id == id && session.result == null) publish(session.copy(draft = draft))
    }
    fun submit(id: String, result: String): Boolean {
        val session = current ?: return false
        if (session.id != id || session.result != null || result.isBlank()) return false
        publish(session.copy(draft = result, result = result))
        return true
    }
    fun cancelEditing(id: String) {
        if (current?.id == id && current?.result == null) publish(null)
    }
    fun clear(id: String) {
        if (current?.id == id) publish(null)
    }
    private fun publish(value: AiTextReviewSession?) {
        current = value
        observers.toList().forEach { it() }
    }
}
