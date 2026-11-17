// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenAI-compatible multipart transcription; the controller explicitly opts into audio upload. */
object AiSpeechClient {
    suspend fun transcribe(config: AiConfig, wav: ByteArray): String = withContext(Dispatchers.IO) {
        require(config.isSpeechReady) { "请先在 AI 设置中配置所选语音服务的凭证" }
        require(wav.size in 44..(WavAudio.MAX_PCM_BYTES + 44) && wav.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "录音格式或大小不正确" }
        val request = if (config.speechProvider == AiSpeechProvider.TENCENT_FLASH) {
            TencentSpeech.request(config.tencentSpeech.forLanguage(config.speechLanguage), wav)
        } else {
            multipart(config, wav)
        }
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", request.contentType)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", request.authorization)
            setFixedLengthStreamingMode(request.body.size)
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                connection.outputStream.use { it.write(request.body) }
                val status = connection.responseCode
                check(status in 200..299) { AiClient.httpError(status) }
                val response = connection.inputStream.use { stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(bytes.size() + count <= 128 * 1024) { "语音服务响应过大" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }
                val transcript = if (config.speechProvider == AiSpeechProvider.TENCENT_FLASH) {
                    TencentSpeech.transcript(response)
                } else {
                    AiResponseParser.transcript(response)
                }
                if (continuation.isActive) continuation.resume(transcript)
            } catch (error: Exception) {
                val safeError = when (error) {
                    is SocketTimeoutException -> IllegalStateException("云端转写超时，请重试", error)
                    is IOException -> IllegalStateException("云端转写网络连接失败，请检查网络", error)
                    is IllegalArgumentException -> IllegalStateException("语音服务返回格式不正确", error)
                    else -> error
                }
                if (continuation.isActive) continuation.resumeWithException(safeError)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun multipart(config: AiConfig, wav: ByteArray): SpeechHttpRequest {
        AiBundledDefaults.requireAllowed(config.speechEndpoint, config.effectiveSpeechApiKey)
        val boundary = "RimeAI" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun text(value: String) = write(value.toByteArray(Charsets.UTF_8))
            fun field(name: String, value: String) {
                text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", config.speechModel.trim())
            field("language", if (config.speechLanguage == "en-US") "en" else "zh")
            field("response_format", "json")
            text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            write(wav)
            text("\r\n--$boundary--\r\n")
        }.toByteArray()
        return SpeechHttpRequest(config.speechEndpoint.trim(), "Bearer ${config.effectiveSpeechApiKey.trim()}", "multipart/form-data; boundary=$boundary", body)
    }
}
