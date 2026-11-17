/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Shared strict parsing for JSON and stream replies; malformed data never becomes user text. */
internal object AiResponseParser {
    fun transcript(body: String): String {
        val root = parseObject(body)
        check(root["error"] == null) { "语音服务未完成转写" }
        val text = string(root["text"] as? JsonPrimitive).orEmpty().trim()
        check(text.isNotEmpty() && text.length <= AiClient.MAX_TEXT_LENGTH) { "语音转写为空、格式错误或过长，请分段录音" }
        return text
    }

    data class Chunk(val text: String, val finished: Boolean)

    fun completion(body: String): String {
        val choice = firstChoice(body)
        checkFinishReason(choice)
        val message = choice["message"] as? JsonObject ?: error("AI 服务返回格式不正确")
        val text = string(message["content"] as? JsonPrimitive).orEmpty().trim()
        check(text.isNotEmpty()) { "AI 服务没有返回文字" }
        return text
    }

    fun streamChunk(body: String): Chunk {
        val root = parseObject(body)
        check(root["error"] == null) { "AI 服务未能完成整理，请重试" }
        val choices = root["choices"] as? JsonArray ?: error("AI 服务返回格式不正确")
        if (choices.isEmpty()) return Chunk("", false) // Optional usage packet.
        val choice = choices.first() as? JsonObject ?: error("AI 服务返回格式不正确")
        checkFinishReason(choice)
        val delta = choice["delta"] as? JsonObject ?: error("AI 服务返回格式不正确")
        val content = delta["content"]
        check(content == null || content == JsonNull || (content is JsonPrimitive && content.isString)) {
            "AI 服务返回格式不正确"
        }
        return Chunk(string(content as? JsonPrimitive).orEmpty(), choice["finish_reason"] != null && choice["finish_reason"] != JsonNull)
    }

    fun suggestions(body: String): List<String> {
        val cleaned = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val values = parseObject(cleaned)["suggestions"] as? JsonArray ?: error("AI 回复格式不正确，请重试")
        check(values.size <= 3) { "AI 回复格式不正确，请重试" }
        return values.map {
            val value = string(it as? JsonPrimitive)?.trim()
            check(!value.isNullOrEmpty() && value.length <= 600) { "AI 回复格式不正确，请重试" }
            value
        }.distinct()
    }

    private fun firstChoice(body: String): JsonObject {
        val choices = parseObject(body)["choices"] as? JsonArray
        return choices?.firstOrNull() as? JsonObject ?: error("AI 服务返回格式不正确")
    }

    private fun parseObject(body: String): JsonObject = try {
        Json.parseToJsonElement(body) as? JsonObject ?: error("AI 服务返回格式不正确")
    } catch (_: IllegalArgumentException) {
        error("AI 服务返回了无法解析的内容")
    }

    private fun string(value: JsonPrimitive?): String? = value?.takeIf { it.isString }?.contentOrNull

    private fun checkFinishReason(choice: JsonObject) {
        when (string(choice["finish_reason"] as? JsonPrimitive)) {
            null, "stop" -> Unit
            "length" -> error("AI 输出长度达到限制，内容不完整；请缩短文字后重试")
            "content_filter" -> error("AI 服务未能处理这段内容")
            else -> error("AI 服务未返回完整文字，请重试")
        }
    }
}
