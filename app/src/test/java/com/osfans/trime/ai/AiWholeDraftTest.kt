// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiWholeDraftTest :
    FunSpec({
        test("full multiline draft is accepted from a cursor in the middle") {
            val text = "第一句。\n第二句。\n第三句。"
            val cursor = text.indexOf("第二句")
            val draft = AiWholeDraft.verify(text, 0, cursor, cursor, text.take(cursor), text.drop(cursor))
            draft shouldBe AiWholeDraft(text, cursor)
            draft?.matches(AiEditTarget("chat", 1, text.take(cursor), null, text.drop(cursor))) shouldBe true
        }
        test("partial editor extraction and truncated cursor reads are rejected") {
            AiWholeDraft.verify("后半段", 4, 0, 0, "", "后半段") shouldBe null
            AiWholeDraft.verify("前半段后半段", 0, 3, 3, "前半段", "后半") shouldBe null
            AiWholeDraft.verify("前半段后半段", 0, 3, 3, "前半段", null) shouldBe null
        }
        test("selection and oversized drafts are rejected") {
            AiWholeDraft.verify("全文", 0, 0, 2, "", "") shouldBe null
            AiWholeDraft.verify("字".repeat(AiClient.MAX_TEXT_LENGTH + 1), 0, 0, 0, "", "") shouldBe null
        }
        test("changed cursor context is not treated as the original draft") {
            val draft = AiWholeDraft("甲乙丙", 1)
            draft.matches(AiEditTarget("chat", 1, "甲乙", null, "丙")) shouldBe false
        }
    })
