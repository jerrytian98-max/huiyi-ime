// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Shares generation between both screenshot paths and the current-session retry buttons. */
internal object AiCaptureReplies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun regenerate(context: Context, state: CaptureState): Boolean {
        val analysis = AiEvents.beginRegeneration(state) ?: return false
        val config = AiConfigStore.load(context.applicationContext)
        scope.launch { generate(config, analysis) }
        return true
    }

    suspend fun generate(config: AiConfig, analysis: CaptureAnalysis): Boolean {
        try {
            val suggestions = coroutineScope {
                val observer: (CaptureState) -> Unit = {
                    if (it != analysis.state) cancel()
                }
                AiEvents.addCaptureObserver(observer)
                try {
                    if (AiEvents.captureState != analysis.state) return@coroutineScope emptyList()
                    withTimeout(60_000) { AiClient.suggestReplies(config, analysis.jpeg, analysis.previousReplies, analysis.replyIntent) }
                } finally {
                    AiEvents.removeCaptureObserver(observer)
                }
            }
            return AiEvents.completeAnalysis(analysis, suggestions)
        } catch (_: TimeoutCancellationException) {
            AiEvents.failAnalysis(analysis, "AI回复生成超时，可以再次生成")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AiEvents.failAnalysis(analysis, error.message ?: "AI回复生成失败，可以再次生成")
        }
        return false
    }
}
