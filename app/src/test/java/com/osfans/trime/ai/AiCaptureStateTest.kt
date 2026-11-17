// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiCaptureStateTest :
    FunSpec({
        test("regeneration reuses the same screenshot and rejects duplicate or stale attempts") {
            AiEvents.clearSuggestions()
            AiEvents.beginCapture("reuse") shouldBe true
            val jpeg = byteArrayOf(1, 2, 3)
            val first = AiEvents.beginAnalysis("reuse", jpeg)!!
            AiEvents.completeAnalysis(first, listOf("first")) shouldBe true
            val ready = AiEvents.captureState
            val second = AiEvents.beginRegeneration(ready)!!
            (second.jpeg === jpeg) shouldBe true
            second.previousReplies shouldBe listOf("first")
            second.state.attempt shouldBe 1
            AiEvents.suggestions shouldBe emptyList()
            AiEvents.beginRegeneration(ready) shouldBe null
            AiEvents.completeAnalysis(first, listOf("late first")) shouldBe false
            AiEvents.failAnalysis(first, "late failure") shouldBe false
            AiEvents.completeAnalysis(second, listOf("second")) shouldBe true
            AiEvents.canRegenerate(ready) shouldBe false
            AiEvents.finishSession("reuse")
            AiEvents.canRegenerate(AiEvents.captureState) shouldBe false
        }
        test("leaving during generation clears the session and rejects late results") {
            AiEvents.clearSuggestions()
            AiEvents.beginCapture("leaving") shouldBe true
            val old = AiEvents.beginAnalysis("leaving", byteArrayOf(1))!!
            AiEvents.finishSession("leaving")
            AiEvents.captureState.status shouldBe CaptureStatus.IDLE
            AiEvents.completeAnalysis(old, listOf("late")) shouldBe false
            AiEvents.beginCapture("fresh") shouldBe true
            AiEvents.finishSession("leaving")
            AiEvents.captureState.requestId shouldBe "fresh"
            AiEvents.failAnalysis(old, "late error") shouldBe false
            AiEvents.finishSession("fresh")
        }
        test("failed analysis can retry the image but a new capture discards it") {
            AiEvents.clearSuggestions()
            AiEvents.beginCapture("error-retry") shouldBe true
            val first = AiEvents.beginAnalysis("error-retry", byteArrayOf(1))!!
            AiEvents.failAnalysis(first, "network") shouldBe true
            AiEvents.canRegenerate(AiEvents.captureState) shouldBe true
            val retry = AiEvents.beginRegeneration(AiEvents.captureState)!!
            AiEvents.completeAnalysis(retry, emptyList()) shouldBe true
            AiEvents.canRegenerate(AiEvents.captureState) shouldBe true
            AiEvents.beginCapture("different-image") shouldBe true
            AiEvents.failCapture("different-image", "permission denied") shouldBe true
            AiEvents.canRegenerate(AiEvents.captureState) shouldBe false
            AiEvents.clearSuggestions()
        }
        test("a failed new capture never exposes old suggestions or accepts stale callbacks") {
            AiEvents.clearSuggestions()
            AiEvents.beginCapture("one") shouldBe true
            AiEvents.beginCapture("duplicate") shouldBe false
            AiEvents.publishSuggestions("one", listOf("previous")) shouldBe true
            AiEvents.beginCapture("two") shouldBe true
            AiEvents.suggestions shouldBe emptyList()
            AiEvents.publishSuggestions("one", listOf("stale")) shouldBe false
            AiEvents.failCapture("two", "network error") shouldBe true
            AiEvents.captureState.status shouldBe CaptureStatus.ERROR
            AiEvents.suggestions shouldBe emptyList()
            AiEvents.publishSuggestions("two", listOf("late")) shouldBe false
            AiEvents.clearSuggestions()
        }
        test("cancellation releases the in-progress guard and observers can unsubscribe") {
            var notifications = 0
            val observer: (CaptureState) -> Unit = { notifications++ }
            AiEvents.addCaptureObserver(observer)
            AiEvents.beginCapture("cancel") shouldBe true
            AiEvents.cancelCapture("cancel") shouldBe true
            AiEvents.isCaptureInProgress shouldBe false
            notifications shouldBe 2
            AiEvents.removeCaptureObserver(observer)
            AiEvents.clearSuggestions()
            notifications shouldBe 2
        }
    })
