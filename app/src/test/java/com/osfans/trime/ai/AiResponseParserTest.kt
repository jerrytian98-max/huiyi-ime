// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiResponseParserTest :
    FunSpec({
        test("transcription accepts only bounded nonblank text without provider errors") {
            AiResponseParser.transcript("""{"text":"  Hello.  "}""") shouldBe "Hello."
            listOf("""{"text":12}""", """{"text":" "}""", """{"error":{},"text":"partial"}""", "[]", "broken", """{"text":"${"a".repeat(6001)}"}""").forEach {
                shouldThrow<IllegalStateException> { AiResponseParser.transcript(it) }
            }
        }
        test("reads complete text and refuses truncated or non-text results") {
            AiResponseParser.completion("""{"choices":[{"message":{"content":"已整理。"},"finish_reason":"stop"}]}""") shouldBe "已整理。"
            shouldThrow<IllegalStateException> {
                AiResponseParser.completion("""{"choices":[{"message":{"content":"半句"},"finish_reason":"length"}]}""")
            }
            shouldThrow<IllegalStateException> {
                AiResponseParser.completion("""{"choices":[{"message":{"content":123},"finish_reason":"stop"}]}""")
            }
        }
        test("stream permits role and usage packets but requires valid completion") {
            AiResponseParser.streamChunk("""{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""") shouldBe AiResponseParser.Chunk("", false)
            AiResponseParser.streamChunk("""{"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}""") shouldBe AiResponseParser.Chunk("你好", false)
            AiResponseParser.streamChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""") shouldBe AiResponseParser.Chunk("", true)
            AiResponseParser.streamChunk("""{"choices":[]}""") shouldBe AiResponseParser.Chunk("", false)
            shouldThrow<IllegalStateException> { AiResponseParser.streamChunk("""{"error":{"message":"bad"}}""") }
        }
        test("suggestions reject mixed types, invented wrappers, blanks, and excess output") {
            AiResponseParser.suggestions("```json\n{\"suggestions\":[\"好的\",\"明白\",\"好的\"]}\n```") shouldBe listOf("好的", "明白")
            AiResponseParser.suggestions("""{"suggestions":[]}""") shouldBe emptyList()
            listOf("""{"suggestions":[1]}""", """{"suggestions":[" "]}""", """{"suggestions":["a","b","c","d"]}""", "not json").forEach {
                shouldThrow<IllegalStateException> { AiResponseParser.suggestions(it) }
            }
        }
    })
