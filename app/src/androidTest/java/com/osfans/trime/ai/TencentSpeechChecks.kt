// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.osfans.trime.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Real Tencent requests use only synthetic test audio and the authorized internal-test defaults. */
internal object TencentSpeechChecks {
    fun run(test: Instrumentation, defaultsOnly: Boolean = false) {
        val context = test.targetContext
        val prefs = context.getSharedPreferences("ai_assistant_config", Context.MODE_PRIVATE)
        val original = AiConfigStore.load(context)
        val defaults = TencentSpeechDefaults.config
        val checks = mutableListOf<String>()
        val audioRows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val ready = File(context.filesDir, "tencent-speech-ui-ready")
        val done = File(context.filesDir, "tencent-speech-ui-done")
        val mode = File(context.filesDir, "tencent-speech-ui-mode")
        var largeStatus = "not_run"
        var code = Activity.RESULT_OK
        val result = Bundle()
        try {
            check(defaults.isReady)
            prefs.edit().clear().commit()
            val fresh = AiConfigStore.load(context)
            check(fresh.cloudSpeech && fresh.speechProvider == AiSpeechProvider.TENCENT_FLASH && fresh.tencentSpeech == defaults)
            check(fresh.apiKey == AiBundledDefaults.apiKey)
            for (secret in listOf(defaults.secretId, defaults.secretKey)) {
                check(prefs.all.values.none { it.toString().contains(secret) }) { "Credential is not encrypted" }
            }
            checks += "PASS: new install defaults to Tencent cloud; independent text and speech credentials are encrypted"
            if (defaultsOnly) {
                check(fresh.tencentSpeech.model == "16k_zh")
                check(fresh.tencentSpeech.forLanguage("en-US").model == "16k_en")
                prefs.edit().putString("tencent_default_applied", "previous-bundle").putString("tencent_speech_model", "16k_zh_en").commit()
                check(AiConfigStore.load(context).let { it.tencentSpeech.model == "16k_zh" && it.apiKey == fresh.apiKey })
                checks += "PASS: an old bundled bilingual default upgrades to general Chinese and retains the text key"
                AiConfigStore.save(context, fresh.copy(tencentSpeech = defaults.copy(model = "16k_yue")))
                prefs.edit().putString("tencent_default_applied", "previous-bundle").commit()
                check(AiConfigStore.load(context).tencentSpeech.model == "16k_yue")
                checks += "PASS: another user-selected Tencent model survives the bundle update"
                AiConfigStore.save(context, fresh)
            }
            AiConfigStore.save(context, fresh.copy(cloudSpeech = false))
            check(!AiConfigStore.load(context).cloudSpeech)
            checks += "PASS: later user-selected system voice is preserved"
            if (defaultsOnly) {
                prefs.edit().putString("tencent_default_applied", "previous-bundle").commit()
                check(!AiConfigStore.load(context).cloudSpeech)
                checks += "PASS: updating speech defaults preserves an explicit system-voice selection"
            }
            prefs.edit().remove("tencent_default_applied").remove("tencent_cloud_default_v1").commit()
            check(AiConfigStore.load(context).let { it.cloudSpeech && it.speechProvider == AiSpeechProvider.TENCENT_FLASH })
            checks += "PASS: old system settings migrate to cloud once"
            AiConfigStore.save(context, fresh.copy(tencentSpeech = defaults.copy(secretKey = "")))
            check(AiConfigStore.load(context).tencentSpeech.secretKey.isEmpty())
            checks += "PASS: a cleared Tencent key stays cleared"
            val compatible = fresh.copy(speechProvider = AiSpeechProvider.OPENAI_COMPATIBLE, speechEndpoint = "https://example.invalid/transcribe", speechModel = "custom", speechApiKey = "synthetic-speech-key")
            AiConfigStore.save(context, compatible)
            prefs.edit().remove("tencent_default_applied").commit()
            check(AiConfigStore.load(context).let { it.speechProvider == compatible.speechProvider && it.speechApiKey == compatible.speechApiKey && it.apiKey == fresh.apiKey })
            checks += "PASS: a custom compatible speech service and separate speech key survive migration"

            // Exercise the configured default in both language settings.
            val general = if (defaultsOnly) fresh else fresh.copy(tencentSpeech = defaults.copy(model = "16k_zh"))
            runBlocking {
                for (language in listOf("zh-CN", "en-US")) {
                    val asset = if (language == "zh-CN") "speech-zh.wav" else "speech-en.wav"
                    val config = general.copy(speechLanguage = language)
                    val audio = test.context.assets.open(asset).use { it.readBytes() }
                    val start = SystemClock.elapsedRealtime()
                    val transcript = withTimeout(60_000) { AiSpeechClient.transcribe(config, audio) }
                    val transcribedAt = SystemClock.elapsedRealtime()
                    if (language == "zh-CN") {
                        check(transcript.contains("提纲") && transcript.contains("预算"))
                    } else {
                        check(transcript.contains("outline", true) && transcript.contains("budget", true))
                    }
                    val polished = withTimeout(60_000) { AiClient.polish(config, transcript) }
                    if (language == "en-US") check(polished.none { it in '\u4E00'..'\u9FFF' })
                    audioRows += buildJsonObject {
                        put("language", language)
                        put("model", config.tencentSpeech.forLanguage(language).model)
                        put("transcript", transcript)
                        put("polished", polished)
                        put("transcription_ms", transcribedAt - start)
                        put("transcription_and_polish_ms", SystemClock.elapsedRealtime() - start)
                    }
                }
                checks += "PASS: real Chinese and English WAV -> Android production Tencent client -> real DeepSeek text cleanup"
                if (!defaultsOnly) {
                    try {
                        val transcript = AiSpeechClient.transcribe(fresh.copy(tencentSpeech = defaults.copy(model = "16k_zh_en")), test.context.assets.open("speech-zh.wav").use { it.readBytes() })
                        check(transcript.isNotBlank())
                        largeStatus = "recognized_synthetic_audio"
                    } catch (error: IllegalStateException) {
                        check(error.message?.contains("4004") == true) { "Unexpected large-engine failure" }
                        largeStatus = "quota_exhausted_4004"
                    }
                }
            }
            if (defaultsOnly) return
            AiConfigStore.save(context, general)
            ready.delete()
            done.delete()
            mode.delete()
            test.runOnMainSync {
                context.startActivity(Intent().setClassName(test.context.packageName, TestChatActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ready.writeText("READY")
            test.sendStatus(0, Bundle().apply { putString("stream", "TENCENT_SPEECH_UI_READY") })
            val deadline = SystemClock.elapsedRealtime() + 900_000
            while (!done.exists() && SystemClock.elapsedRealtime() < deadline) {
                if (mode.exists()) {
                    val model = mode.readText().trim()
                    mode.delete()
                    AiConfigStore.save(context, general.copy(tencentSpeech = general.tencentSpeech.copy(model = model)))
                }
                SystemClock.sleep(100)
            }
            check(done.exists() && done.readText().trim() == "PASS") { "Host microphone checks did not finish" }
            checks += "PASS: real microphone-path UI checks completed with emulator-injected synthetic PCM"
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("stream", error.stackTraceToString())
        } finally {
            ready.delete()
            done.delete()
            AiConfigStore.save(context, original)
            File(context.filesDir, if (defaultsOnly) "tencent-speech-general-default-result.json" else "tencent-speech-device-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("default_model", defaults.model)
                    put("defaults_only", defaultsOnly)
                    put("api", android.os.Build.VERSION.SDK_INT)
                    put("large_model_status", largeStatus)
                    put("physical_microphone", false)
                    put("audio_checks", JsonArray(audioRows))
                    put("checks", JsonArray(checks.map(::JsonPrimitive)))
                    put("passed", code == Activity.RESULT_OK)
                }.toString(),
            )
            if (code == Activity.RESULT_OK) result.putString("stream", checks.joinToString("\n"))
            test.finish(code, result)
        }
    }
}
