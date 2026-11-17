// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real acoustic decoding from synthetic WAV, followed by actual IME microphone UI checks. */
object AiAudioChecks {
    fun run(test: Instrumentation, uiOnly: Boolean = false) {
        val result = Bundle()
        val original = AiConfigStore.load(test.targetContext)
        val originalTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val ready = File(test.targetContext.filesDir, "audio-ui-ready")
        val done = File(test.targetContext.filesDir, "finish-audio-checks")
        val evidence = File(test.targetContext.filesDir, "live-audio-result.json")
        ready.delete()
        done.delete()
        if (!uiOnly) evidence.delete()
        try {
            val certificate = test.context.assets.open("test-ca.pem").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val config = AiConfig("https://10.0.2.2:9444/v1/chat/completions", "gpt-4o-mini", "gpt-4o-mini", "synthetic-test-key", "zh-CN", cloudSpeech = true, speechEndpoint = "https://10.0.2.2:9444/v1/audio/transcriptions", speechModel = "gpt-4o-mini-transcribe")
            val checks = runBlocking {
                if (uiOnly) return@runBlocking Json.parseToJsonElement(evidence.readText()) as JsonArray
                val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
                for ((language, asset) in listOf("zh-CN" to "speech-zh.wav", "en-US" to "speech-en.wav")) {
                    val audio = test.context.assets.open(asset).use { it.readBytes() }
                    val start = SystemClock.elapsedRealtime()
                    val transcript = AiSpeechClient.transcribe(config.copy(speechLanguage = language), audio)
                    val transcribedAt = SystemClock.elapsedRealtime()
                    val polished = AiClient.polish(config, transcript)
                    rows += buildJsonObject {
                        put("language", language)
                        put("audio_fixture", asset)
                        put("audio_bytes", audio.size)
                        put("transcript", transcript)
                        put("polished", polished)
                        put("transcription_ms", transcribedAt - start)
                        put("transcription_and_polish_ms", SystemClock.elapsedRealtime() - start)
                    }
                    evidence.writeText(JsonArray(rows).toString())
                    if (language == "zh-CN") {
                        check(transcript.contains("提纲") && transcript.contains("预算")) { "Chinese acoustic recognition lost meaning: $transcript" }
                    } else {
                        check(transcript.contains("outline", true) && transcript.contains("budget", true) && polished.none { it in '\u4E00'..'\u9FFF' }) { "English acoustic recognition or language failed: $transcript / $polished" }
                    }
                }
                JsonArray(rows)
            }
            AiConfigStore.save(test.targetContext, config)
            test.runOnMainSync {
                test.targetContext.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            SystemClock.sleep(1500)
            test.waitForIdleSync()
            ready.writeText("READY")
            test.sendStatus(0, Bundle().apply { putString("stream", "AUDIO_UI_READY") })
            val deadline = SystemClock.elapsedRealtime() + 240_000
            while (!done.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
            check(done.exists() && done.readText().trim() == "PASS") { "Host microphone UI checks did not complete" }
            evidence.writeText(
                buildJsonObject {
                    put("provider", "OpenAI")
                    put("recognition_model", "gpt-4o-mini-transcribe")
                    put("polish_model", "gpt-4o-mini")
                    put("source", "Windows TTS synthetic Chinese/English WAV; microphone UI uses emulator injected PCM, not physical microphone")
                    put("checks", checks)
                    put("microphone_ui", "PASS: AudioRecord -> real acoustic recognition -> AI preview -> insert; cancel and release")
                }.toString(),
            )
            result.putString("stream", "PASS: real Chinese/English audio recognition and microphone UI\n${evidence.readText()}")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            test.finish(Activity.RESULT_CANCELED, result)
        } finally {
            ready.delete()
            done.delete()
            AiConfigStore.save(test.targetContext, original)
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
        }
    }
}
