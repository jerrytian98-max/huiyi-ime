// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TencentSpeechTest :
    FunSpec({
        val credentials = TencentSpeechConfig("1250000000", "AKIDsynthetic123", "synthetic-secret-key", model = "16k_zh_en")
        test("new configuration defaults to general Chinese and selects general English by language") {
            TencentSpeechConfig().model shouldBe "16k_zh"
            TencentSpeechConfig().forLanguage("en-US").model shouldBe "16k_en"
        }
        test("flash request matches an independent HMAC-SHA1 signing fixture") {
            val audio = byteArrayOf(1, 2, 3)
            val request = TencentSpeech.request(credentials, audio, 1700000000)
            request.authorization shouldBe "IrZLYc7idGdT4dbSxnOWT3Bjss4="
            request.url shouldBe "https://asr.cloud.tencent.com/asr/flash/v1/1250000000?convert_num_mode=1&engine_type=16k_zh_en&filter_dirty=0&filter_modal=0&filter_punc=0&first_channel_only=1&secretid=AKIDsynthetic123&speaker_diarization=0&timestamp=1700000000&voice_format=wav&word_info=0"
            request.contentType shouldBe "application/octet-stream"
            request.body.contentEquals(audio) shouldBe true
            request.url.contains(credentials.secretKey) shouldBe false
        }
        test("unsafe identifiers cannot alter the signed host path or query") {
            for (invalid in listOf(credentials.copy(appId = "125/evil"), credentials.copy(secretId = "AKIDsynthetic123&other=value"), credentials.copy(model = "16k_zh&other=value"))) {
                invalid.isReady shouldBe false
                shouldThrowAny { TencentSpeech.request(invalid, byteArrayOf()) }
            }
        }
        test("flash transcripts accept a single textual channel and preserve its language") {
            TencentSpeech.transcript("""{"code":0,"flash_result":[{"text":"  明天下午3点开会。  ","channel_id":0}]}""") shouldBe "明天下午3点开会。"
            TencentSpeech.transcript("""{"code":0,"flash_result":[{"text":"The budget is 1500 dollars."}]}""") shouldBe "The budget is 1500 dollars."
        }
        test("quota errors are actionable and do not echo provider messages") {
            val failure = shouldThrowAny { TencentSpeech.transcript("""{"code":4004,"message":"synthetic-private-value"}""") }
            failure.message!!.contains("4004") shouldBe true
            failure.message!!.contains("资源包") shouldBe true
            failure.message!!.contains("synthetic-private-value") shouldBe false
        }
        test("malformed empty non-text and multi-channel responses are rejected") {
            for (response in listOf("{}", "[]", """{"code":"0","flash_result":[{"text":"ok"}]}""", """{"code":0,"flash_result":[]}""", """{"code":0,"flash_result":[{"text":3}]}""", """{"code":0,"flash_result":[{"text":" "}]}""", """{"code":0,"flash_result":[{"text":"a"},{"text":"b"}]}""")) {
                shouldThrowAny { TencentSpeech.transcript(response) }
            }
        }
        test("Tencent speech readiness is independent of the text API credential") {
            val config = AiConfig("https://example.invalid/chat", "", "", "", "zh-CN", cloudSpeech = true, speechProvider = AiSpeechProvider.TENCENT_FLASH, tencentSpeech = credentials)
            config.isSpeechReady shouldBe true
            config.isTextReady shouldBe false
            config.copy(tencentSpeech = credentials.copy(secretKey = "")).isSpeechReady shouldBe false
        }
        test("compatible speech supports a separate key and preserves legacy shared-key configuration") {
            val config = AiConfig("https://example.invalid/chat", "text", "vision", "text-key", "en-US", speechEndpoint = "https://speech.example.invalid/transcriptions", speechModel = "speech-model")
            config.effectiveSpeechApiKey shouldBe "text-key"
            config.copy(speechApiKey = "speech-key").effectiveSpeechApiKey shouldBe "speech-key"
            config.copy(apiKey = "", speechApiKey = "speech-key").isSpeechReady shouldBe true
        }
        test("the general Chinese engine follows English selection while the bilingual engine stays unchanged") {
            credentials.copy(model = "16k_zh").forLanguage("en-US").model shouldBe "16k_en"
            credentials.copy(model = "16k_zh").forLanguage("zh-CN").model shouldBe "16k_zh"
            credentials.forLanguage("en-US").model shouldBe "16k_zh_en"
        }
        test("credential data class does not expose its secrets when logged") {
            credentials.toString().contains(credentials.secretKey) shouldBe false
            credentials.toString().contains(credentials.secretId) shouldBe false
        }
    })
