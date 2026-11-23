// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Production ASR client -> per-capture intent -> vision client; only a synthetic local server. */
internal object ReplyIntentNetworkChecks {
    fun run(test: Instrumentation) {
        test.runOnMainSync { }
        val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val rows = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            val certificate = test.context.assets.open("test-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory)
            val config = AiConfig("https://10.0.2.2:9443/v1/chat/completions", "mock-text", "mock-vision-intent", "synthetic-test-key", "zh-CN", cloudSpeech = true, speechEndpoint = "https://10.0.2.2:9443/v1/audio/transcriptions", speechModel = "mock-reply-intent")
            runBlocking {
                val transcript = AiSpeechClient.transcribe(config, test.context.assets.open("speech-zh.wav").use { it.readBytes() })
                check(transcript == "委婉拒绝，这周没空，下周可以。")
                rows += "PASS: production cloud transcription client obtains the synthetic reply intent"
                val jpeg = test.context.assets.open("synthetic-chat.jpg").use { it.readBytes() }
                var analysis: CaptureAnalysis? = null
                test.runOnMainSync {
                    AiEvents.finishSession(AiEvents.captureState.requestId)
                    check(AiEvents.beginCapture("intent-network", transcript))
                    analysis = AiEvents.beginAnalysis("intent-network", jpeg)
                }
                check(AiCaptureReplies.generate(config, checkNotNull(analysis)))
                test.runOnMainSync {
                    check(AiEvents.suggestions.first() == "这周没空，下周可以吗？")
                    analysis = AiEvents.beginRegeneration(AiEvents.captureState)
                }
                rows += "PASS: screenshot generation carries intent and retains three reply options"
                check(AiCaptureReplies.generate(config, checkNotNull(analysis)))
                rows += "PASS: regenerating the same screenshot retains the voice intent"
                test.runOnMainSync {
                    AiEvents.finishSession("intent-network")
                    check(AiEvents.beginCapture("intent-plain"))
                    analysis = AiEvents.beginAnalysis("intent-plain", jpeg)
                    check(analysis!!.replyIntent.isEmpty())
                }
                check(AiCaptureReplies.generate(config.copy(visionModel = "mock-vision"), checkNotNull(analysis)))
                test.runOnMainSync {
                    check(AiEvents.suggestions.first().startsWith("好的"))
                    AiEvents.finishSession("intent-plain")
                }
                rows += "PASS: the next ordinary capture has no voice intent"
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
            test.runOnMainSync { AiEvents.finishSession(AiEvents.captureState.requestId) }
        }
        val result = buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("passed", failure == null)
            put("external_ai_calls", 0)
            put("checks", JsonArray(rows.map(::JsonPrimitive)))
        }
        File(test.targetContext.filesDir, "reply-intent-network-result.json").writeText(result.toString())
        test.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure?.stackTraceToString() ?: result.toString()) })
    }
}
