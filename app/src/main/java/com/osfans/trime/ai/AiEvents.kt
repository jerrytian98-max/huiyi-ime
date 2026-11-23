// SPDX-FileCopyrightText: 2026 Rime AI contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ai

enum class CaptureStatus { IDLE, AWAITING_PERMISSION, CAPTURING, ANALYZING, READY, ERROR, CANCELLED }

data class CaptureState(
    val requestId: String = "",
    val status: CaptureStatus = CaptureStatus.IDLE,
    val suggestions: List<String> = emptyList(),
    val message: String = "",
    val attempt: Int = 0,
) {
    val isInProgress: Boolean
        get() = status == CaptureStatus.AWAITING_PERMISSION ||
            status == CaptureStatus.CAPTURING || status == CaptureStatus.ANALYZING
}

internal data class CaptureAnalysis(val state: CaptureState, val jpeg: ByteArray, val previousReplies: List<String> = emptyList(), val replyIntent: String = "")

/** Main-thread, process-local handoff. No screenshot or conversation is persisted. */
object AiEvents {
    var onMicrophonePermissionGranted: (() -> Unit)? = null
    var onSuggestions: ((List<String>) -> Unit)? = null
    private val captureObservers = linkedSetOf<(CaptureState) -> Unit>()
    private var screenshot: ByteArray? = null
    private var replyIntent = ""

    var captureState: CaptureState = CaptureState()
        private set
    val suggestions: List<String> get() = captureState.suggestions
    val isCaptureInProgress: Boolean get() = captureState.isInProgress

    fun addCaptureObserver(observer: (CaptureState) -> Unit) {
        captureObservers.add(observer)
    }
    fun removeCaptureObserver(observer: (CaptureState) -> Unit) {
        captureObservers.remove(observer)
    }

    internal fun hasReplyIntent(requestId: String): Boolean = captureState.requestId == requestId &&
        captureState.isInProgress && replyIntent.isNotBlank()

    fun beginCapture(requestId: String, intent: String = ""): Boolean {
        if (requestId.isBlank() || isCaptureInProgress) return false
        AiReplyPrompt.validateIntent(intent)
        screenshot = null
        replyIntent = intent.trim()
        publish(CaptureState(requestId, CaptureStatus.AWAITING_PERMISSION, message = "等待系统截屏授权"))
        return true
    }

    fun updateCapture(requestId: String, status: CaptureStatus, message: String): Boolean {
        if (!isActive(requestId)) return false
        publish(captureState.copy(status = status, suggestions = emptyList(), message = message))
        return true
    }

    internal fun beginAnalysis(requestId: String, jpeg: ByteArray): CaptureAnalysis? {
        if (!isActive(requestId)) return null
        require(jpeg.isNotEmpty() && jpeg.size <= 10 * 1024 * 1024) { "截屏为空或过大，请重新点击AI回复" }
        screenshot = jpeg
        updateCapture(requestId, CaptureStatus.ANALYZING, "正在理解聊天内容并生成回复")
        return CaptureAnalysis(captureState, jpeg, replyIntent = replyIntent)
    }

    fun canRegenerate(state: CaptureState): Boolean = state == captureState && screenshot != null &&
        (state.status == CaptureStatus.READY || state.status == CaptureStatus.ERROR)

    internal fun beginRegeneration(state: CaptureState): CaptureAnalysis? {
        if (!canRegenerate(state)) return null
        val jpeg = screenshot ?: return null
        val next = state.copy(status = CaptureStatus.ANALYZING, suggestions = emptyList(), message = "正在根据本轮截图再次生成回复", attempt = state.attempt + 1)
        publish(next)
        return CaptureAnalysis(next, jpeg, state.suggestions, replyIntent)
    }

    internal fun completeAnalysis(analysis: CaptureAnalysis, value: List<String>): Boolean = captureState == analysis.state && publishSuggestions(analysis.state.requestId, value)

    internal fun failAnalysis(analysis: CaptureAnalysis, message: String): Boolean = captureState == analysis.state && failCapture(analysis.state.requestId, message)

    fun publishSuggestions(requestId: String, value: List<String>): Boolean {
        if (!isActive(requestId)) return false
        val copy = value.filter(String::isNotBlank).take(3).toList()
        publish(
            CaptureState(
                requestId,
                CaptureStatus.READY,
                copy,
                if (copy.isEmpty()) "未识别出可回复的对话，请确认聊天内容清晰可见。" else "回复建议已就绪",
                captureState.attempt,
            ),
        )
        onSuggestions?.invoke(copy)
        return true
    }

    fun failCapture(requestId: String, message: String): Boolean = updateCapture(requestId, CaptureStatus.ERROR, message)

    fun cancelCapture(requestId: String): Boolean = updateCapture(requestId, CaptureStatus.CANCELLED, "已取消本次AI回复分析")

    fun clearSuggestions() {
        if (!isCaptureInProgress) publish(CaptureState())
    }

    fun finishSession(requestId: String) {
        if (requestId.isNotEmpty() && captureState.requestId == requestId) publish(CaptureState())
    }

    private fun isActive(requestId: String): Boolean = requestId == captureState.requestId && captureState.isInProgress

    private fun publish(state: CaptureState) {
        if (state.status == CaptureStatus.IDLE || state.status == CaptureStatus.CANCELLED) {
            screenshot = null
            replyIntent = ""
        }
        captureState = state
        captureObservers.toList().forEach { it(state) }
    }
}
