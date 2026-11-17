// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiReplyPromptTest :
    FunSpec({
        test("blank uses direct default style") {
            AiReplyPrompt.effective(" \n ") shouldBe AiReplyPrompt.DEFAULT
            AiReplyPrompt.DEFAULT.contains("直接、自然、简短") shouldBe true
        }

        test("custom style is used without changing its wording") {
            AiReplyPrompt.effective(" 只说重点，不要寒暄。 ") shouldBe "只说重点，不要寒暄。"
        }

        test("oversized prompt is rejected") {
            shouldThrow<IllegalArgumentException> {
                AiReplyPrompt.validate("字".repeat(AiReplyPrompt.MAX_LENGTH + 1))
            }
        }
    })
