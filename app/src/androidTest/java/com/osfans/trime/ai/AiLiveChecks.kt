// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Explicitly invoked paid-provider test; the real credential stays in host relay memory. */
object AiLiveChecks {
    fun run(instrumentation: Instrumentation, model: String = AiConfigStore.DEFAULT_MODEL, provider: String = "Tokenlink", endpoint: String = "https://10.0.2.2:9444/v1/chat/completions") {
        val context = instrumentation.context
        val targetContext = instrumentation.targetContext
        val result = Bundle()
        val evidenceFile = java.io.File(targetContext.filesDir, "live-provider-result.json")
        evidenceFile.delete()
        val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val directRoute = if (provider == "Tokenlink" && model == "hy4") {
                runBlocking {
                    try {
                        withTimeout(15_000) {
                            AiClient.polish(AiConfig(AiConfigStore.DEFAULT_ENDPOINT, model, model, "synthetic-invalid-route-probe", "zh-CN"), "合成连通性测试")
                        }
                        "Unexpected success with invalid credential; not evidence of authenticated access"
                    } catch (error: Exception) {
                        // A 401/403 demonstrates Android DNS/TLS/HTTP reachability,
                        // without transferring the real key to the emulator.
                        error.message ?: error.javaClass.simpleName
                    }
                }
            } else {
                "Not requested"
            }
            val certificate = context.assets.open("test-ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val config = AiConfig(endpoint, model, model, "synthetic-test-key", "zh-CN")
            val checks = runBlocking {
                val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
                for ((name, input) in listOf(
                    "zh-polish" to "嗯那个明天下午三点开会然后请Alex提前发一下提纲预算是1500元还没确定",
                    "en-polish" to "um so tomorrow at 3 pm we meet and Alex should send the outline first the budget is 1500 dollars but it is not confirmed yet",
                )) {
                    val started = SystemClock.elapsedRealtime()
                    var firstContentMs: Long? = null
                    val output = AiClient.polish(config, input) {
                        if (firstContentMs == null) firstContentMs = SystemClock.elapsedRealtime() - started
                    }
                    // Wording and preserved uncertainty are reviewed from the saved output;
                    // a synonym such as "待确定" must not fail a lexical assertion.
                    rows += buildJsonObject {
                        put("case", name)
                        put("input", input)
                        put("output", output)
                        put("first_content_ms", firstContentMs)
                        put("total_ms", SystemClock.elapsedRealtime() - started)
                    }
                    evidenceFile.writeText(JsonArray(rows).toString())
                    check(output.replace(",", "").contains("1500") && output.contains("Alex")) { "Lost name or number in $name: $output" }
                    check(firstContentMs != null) { "Missing streamed preview in $name" }
                    if (name == "en-polish") {
                        check(output.none { it in '\u4E00'..'\u9FFF' }) { "English input was translated: $output" }
                    }
                }
                val started = SystemClock.elapsedRealtime()
                val jpeg = context.assets.open("synthetic-chat.jpg").use { it.readBytes() }
                val replies = AiClient.suggestReplies(config, jpeg)
                check(replies.size == 3) { "Expected three replies to readable chat" }
                // One explicit reference establishes grounding; pronouns in the other
                // suggestions can be valid and are reviewed in the complete evidence.
                check(replies.any { it.contains("提纲") }) { "Replies missed the final incoming request for an outline: $replies" }
                rows += buildJsonObject {
                    put("case", "screenshot-replies")
                    put("suggestions", JsonArray(replies.map(::JsonPrimitive)))
                    put("total_ms", SystemClock.elapsedRealtime() - started)
                }
                JsonArray(rows)
            }
            val evidence = buildJsonObject {
                put("provider", provider)
                put("model", model)
                put("version", "0.1.3-ai")
                put("direct_route_probe", directRoute)
                put("transport", "Android AiClient -> local test-only TLS relay -> real HTTPS provider")
                put("checks", checks)
            }
            evidenceFile.writeText(Json.encodeToString(evidence))
            result.putString("stream", "PASS: real-provider Chinese/English streaming and screenshot replies\n$evidence")
            instrumentation.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            instrumentation.finish(Activity.RESULT_CANCELED, result)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
        }
    }
}
