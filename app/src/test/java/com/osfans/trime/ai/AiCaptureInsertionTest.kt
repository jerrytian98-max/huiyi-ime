// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiCaptureInsertionTest :
    FunSpec({
        test("regenerated replies preserve the original insertion target and reject old choices") {
            AiEvents.clearSuggestions()
            val editor = AiEditTarget("example.chat", 12, "prefix", null, "suffix")
            AiCaptureInsertion.remember("regenerated", editor)
            AiEvents.beginCapture("regenerated") shouldBe true
            val first = AiEvents.beginAnalysis("regenerated", byteArrayOf(1))!!
            AiEvents.completeAnalysis(first, listOf("first")) shouldBe true
            val old = AiEvents.captureState
            val next = AiEvents.beginRegeneration(old)!!
            AiCaptureInsertion.submit(old, "first") shouldBe false
            AiEvents.completeAnalysis(next, listOf("second")) shouldBe true
            AiCaptureInsertion.submit(old, "first") shouldBe false
            AiCaptureInsertion.submit(AiEvents.captureState, "second") shouldBe true
            AiTextReviewStore.current?.target shouldBe editor
            AiTextReviewStore.current?.let { AiTextReviewStore.clear(it.id) }
            AiEvents.canRegenerate(AiEvents.captureState) shouldBe false
        }
        test("a selected screenshot reply reaches the original editor exactly once") {
            AiEvents.clearSuggestions()
            val editor = AiEditTarget("example.chat", 12, "prefix", null, "suffix")
            AiCaptureInsertion.remember("insert-once", editor)
            AiEvents.beginCapture("insert-once") shouldBe true
            AiEvents.publishSuggestions("insert-once", listOf("approved reply")) shouldBe true
            val state = AiEvents.captureState
            AiCaptureInsertion.submit(state, "invented reply") shouldBe false
            AiCaptureInsertion.submit(state, "approved reply") shouldBe true
            val pending = AiTextReviewStore.current!!
            pending.target shouldBe editor
            pending.result shouldBe "approved reply"
            AiCaptureInsertion.submit(state, "approved reply") shouldBe false
            AiTextReviewStore.current?.id shouldBe pending.id
            AiEvents.captureState.status shouldBe CaptureStatus.IDLE
            AiTextReviewStore.clear(pending.id)
        }
        test("stale screenshot pages cannot insert into a newer capture target") {
            AiEvents.clearSuggestions()
            val editor = AiEditTarget("example.chat", 12, "prefix", null, "suffix")
            AiCaptureInsertion.remember("old", editor)
            AiEvents.beginCapture("old") shouldBe true
            AiEvents.publishSuggestions("old", listOf("old reply")) shouldBe true
            val old = AiEvents.captureState
            AiCaptureInsertion.remember("new", editor.copy(fieldId = 13))
            AiEvents.beginCapture("new") shouldBe true
            AiEvents.publishSuggestions("new", listOf("new reply")) shouldBe true
            AiCaptureInsertion.submit(old, "old reply") shouldBe false
            AiCaptureInsertion.submit(AiEvents.captureState, "new reply") shouldBe true
            AiTextReviewStore.current?.target?.fieldId shouldBe 13
            AiTextReviewStore.current?.let { AiTextReviewStore.clear(it.id) }
        }
    })
