// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import com.osfans.trime.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** App-specific Tencent credentials, separate from the text and vision provider. */
data class TencentSpeechConfig(
    val appId: String = "",
    val secretId: String = "",
    val secretKey: String = "",
    val model: String = "16k_zh",
) {
    val isReady: Boolean get() = appId.matches(Regex("[1-9][0-9]{4,14}")) &&
        secretId.matches(Regex("AKID[A-Za-z0-9]{8,128}")) && secretKey.isNotBlank() &&
        model.matches(Regex("[A-Za-z0-9_.-]+"))

    fun forLanguage(language: String): TencentSpeechConfig = if (model == "16k_zh" && language == "en-US") copy(model = "16k_en") else this

    override fun toString() = "TencentSpeechConfig(credentials=redacted, model=$model)"
}

enum class AiSpeechProvider { OPENAI_COMPATIBLE, TENCENT_FLASH }

internal class SpeechHttpRequest(val url: String, val authorization: String, val contentType: String, val body: ByteArray)

internal object TencentSpeechDefaults {
    val id get() = BuildConfig.SPEECH_DEFAULT_BUNDLE_ID
    val config: TencentSpeechConfig by lazy {
        runCatching {
            val json = Json.parseToJsonElement(
                AiBundledDefaults.decrypt(BuildConfig.SPEECH_DEFAULT_CIPHERTEXT, BuildConfig.SPEECH_DEFAULT_WRAP_KEY, "rime-ai:tencent-asr:v1"),
            ) as JsonObject
            TencentSpeechConfig(json["appId"]!!.jsonText(), json["secretId"]!!.jsonText(), json["secretKey"]!!.jsonText(), json["model"]?.jsonText() ?: "16k_zh")
        }.getOrDefault(TencentSpeechConfig())
    }
    private fun kotlinx.serialization.json.JsonElement.jsonText() = (this as JsonPrimitive).content
}

/** Official Flash ASR API. Signing and errors contain no transcript or credential logging. */
internal object TencentSpeech {
    fun request(config: TencentSpeechConfig, wav: ByteArray, timestamp: Long = System.currentTimeMillis() / 1000): SpeechHttpRequest {
        require(config.isReady) { "请配置腾讯云 AppID、SecretId 和 SecretKey" }
        val parameters = sortedMapOf(
            "convert_num_mode" to "1", "engine_type" to config.model, "filter_dirty" to "0",
            "filter_modal" to "0", "filter_punc" to "0", "first_channel_only" to "1",
            "secretid" to config.secretId, "speaker_diarization" to "0", "timestamp" to timestamp.toString(),
            "voice_format" to "wav", "word_info" to "0",
        )
        // All accepted parameter values are ASCII identifiers, digits or engine names.
        val query = parameters.entries.joinToString("&") { "${it.key}=${it.value}" }
        val target = "asr.cloud.tencent.com/asr/flash/v1/${config.appId}?$query"
        val hmac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(config.secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1")) }
        val signature = Base64.getEncoder().encodeToString(hmac.doFinal("POST$target".toByteArray(Charsets.UTF_8)))
        return SpeechHttpRequest("https://$target", signature, "application/octet-stream", wav)
    }

    fun transcript(body: String): String {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("腾讯云语音返回格式不正确")
        val code = root["code"] as? JsonPrimitive
        check(code != null && !code.isString && code.intOrNull != null) { "腾讯云语音返回格式不正确" }
        check(code.intOrNull == 0) { errorMessage(code.intOrNull!!) }
        val rows = root["flash_result"] as? JsonArray ?: error("腾讯云语音返回格式不正确")
        val text = ((rows.singleOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
        check(text.isNotEmpty() && text.length <= AiClient.MAX_TEXT_LENGTH) { "腾讯云未识别到清晰语音，或文字过长，请分段重试" }
        return text
    }

    fun errorMessage(code: Int): String = when (code) {
        4002 -> "腾讯云语音鉴权失败（4002），请核对 AppID、SecretId、SecretKey 和手机时间"
        4003 -> "腾讯云尚未开通语音识别服务（4003），请在腾讯云控制台开通"
        4004 -> "腾讯云语音额度不足（4004），请购买当前模型适用的识别资源包或开通对应后付费"
        4005 -> "腾讯云语音因账户欠费暂停（4005），请在腾讯云控制台处理"
        4006 -> "腾讯云语音并发超限（4006），请稍后重试"
        4001 -> "腾讯云语音参数不正确（4001），请检查 AppID 和识别模型"
        4007 -> "腾讯云无法解码本次录音（4007），请重新录音"
        5001, 5002, 5003 -> "腾讯云语音暂时未完成识别（$code），请稍后重试"
        else -> "腾讯云语音识别失败（$code），请重试或在服务控制台检查"
    }
}
