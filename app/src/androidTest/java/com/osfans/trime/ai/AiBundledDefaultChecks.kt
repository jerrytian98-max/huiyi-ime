// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Authorized live check: real default credential, Android system TLS, no relay. */
internal object AiBundledDefaultChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "deepseek-bundled-result.json")
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var original: AiConfig? = null
        var credentialChecksPassed = false
        fun write(error: String? = null) {
            file.writeText(
                buildJsonObject {
                    put("version", "0.1.5-ai")
                    put("provider", "DeepSeek")
                    put("model", AiConfigStore.DEFAULT_MODEL)
                    put("transport", "Android AiClient -> official DeepSeek HTTPS; system TLS; no relay")
                    put("credential_checks_passed", credentialChecksPassed)
                    put("plaintext_credential_logged", false)
                    put("checks", JsonArray(rows))
                    if (error != null) put("error", error)
                }.toString(),
            )
        }
        try {
            instrumentation.runOnMainSync { }
            val prefs = context.getSharedPreferences("ai_assistant_config", Context.MODE_PRIVATE)
            original = AiConfigStore.load(context)
            check(AiBundledDefaults.apiKey.isNotEmpty()) { "No bundled default in this build" }
            // Fresh import, then exercise persistence and user overrides with
            // synthetic configuration. Never print keys or preference values.
            prefs.edit().clear().commit()
            val config = AiConfigStore.load(context)
            check(config.endpoint == AiConfigStore.DEFAULT_ENDPOINT && config.model == "deepseek-flash" && config.visionModel == "deepseek-flash")
            check(AiBundledDefaults.isBundledKey(config.apiKey))
            check(!prefs.getString("api_key_encrypted", "").isNullOrEmpty() && !prefs.contains("api_key"))
            check(prefs.all.values.none { it.toString().contains(config.apiKey) })
            check(AiConfigStore.load(context).apiKey == config.apiKey) { "Keystore round-trip failed" }
            AiConfigStore.save(context, config.copy(apiKey = ""))
            check(AiConfigStore.load(context).apiKey.isEmpty()) { "Cleared key was silently restored" }
            val custom = config.copy(endpoint = "https://example.invalid/v1/chat/completions", model = "custom", visionModel = "custom", apiKey = "synthetic-custom-key")
            AiConfigStore.save(context, custom)
            check(AiConfigStore.load(context) == custom) { "Custom settings were overwritten" }
            AiConfigStore.save(context, config)
            var blocked = false
            try {
                AiConfigStore.save(context, config.copy(endpoint = "https://example.invalid/v1/chat/completions"))
            } catch (_: IllegalArgumentException) {
                blocked = true
            }
            check(blocked && AiConfigStore.load(context).endpoint == AiConfigStore.DEFAULT_ENDPOINT) { "Default key was not origin-bound" }
            credentialChecksPassed = true
            write()
            runBlocking {
                for ((name, input) in listOf(
                    "zh-polish" to "嗯那个明天下午三点开会然后请Alex提前发一下提纲预算是1500元还没确定",
                    "en-polish" to "um so tomorrow at 3 pm we meet and Alex should send the outline first the budget is 1500 dollars but it is not confirmed yet",
                )) {
                    val started = SystemClock.elapsedRealtime()
                    var firstMs: Long? = null
                    val output = withTimeout(60_000) {
                        AiClient.polish(config, input) { if (firstMs == null) firstMs = SystemClock.elapsedRealtime() - started }
                    }
                    check(firstMs != null && output.contains("Alex") && output.replace(",", "").contains("1500")) { "Incomplete streamed edit" }
                    if (name == "en-polish") check(output.none { it in '\u4E00'..'\u9FFF' }) { "English was translated" }
                    rows += buildJsonObject {
                        put("case", name)
                        put("input", input)
                        put("output", output)
                        put("first_content_ms", firstMs)
                        put("total_ms", SystemClock.elapsedRealtime() - started)
                    }
                    write()
                }
                val started = SystemClock.elapsedRealtime()
                val image = instrumentation.context.assets.open("synthetic-chat.jpg").use { it.readBytes() }
                val replies = withTimeout(60_000) { AiClient.suggestReplies(config, image) }
                check(replies.size == 3 && replies.any { it.contains("提纲") }) { "Screenshot replies missed last incoming message" }
                rows += buildJsonObject {
                    put("case", "screenshot-replies")
                    put("suggestions", JsonArray(replies.map(::JsonPrimitive)))
                    put("total_ms", SystemClock.elapsedRealtime() - started)
                }
            }
            write()
            instrumentation.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: encrypted bundled default, Keystore import, clearing/custom settings, origin binding, direct DeepSeek text and vision\n${file.readText()}") })
        } catch (error: Throwable) {
            write("${error.javaClass.simpleName}: ${error.message}")
            instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}") })
        } finally {
            original?.let { AiConfigStore.save(context, it) }
        }
    }
}
