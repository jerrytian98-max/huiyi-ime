// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiTextReviewStoreTest :
    FunSpec({
        test("stale edits and late results cannot overwrite a newer review or remove a submitted result") {
            val target = AiEditTarget("example.chat", 12, "before", "selected", "after")
            val old = AiTextReviewStore.begin(target, "original", "draft")
            AiTextReviewStore.edit(old, "corrected")
            AiTextReviewStore.current?.draft shouldBe "corrected"
            val latest = AiTextReviewStore.begin(target, "new original", "new draft")
            AiTextReviewStore.edit(old, "stale edit")
            AiTextReviewStore.submit(old, "stale result") shouldBe false
            AiTextReviewStore.cancelEditing(old)
            AiTextReviewStore.current?.draft shouldBe "new draft"
            AiTextReviewStore.submit(latest, "user approved") shouldBe true
            AiTextReviewStore.edit(latest, "late draft")
            AiTextReviewStore.cancelEditing(latest)
            AiTextReviewStore.current?.result shouldBe "user approved"
            AiTextReviewStore.clear(old)
            AiTextReviewStore.current?.result shouldBe "user approved"
            AiTextReviewStore.clear(latest)
            AiTextReviewStore.current shouldBe null
        }
    })
