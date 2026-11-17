// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber

/** One user-started recognition session. All methods and callbacks run on the main thread. */
internal class AiSystemSpeech(
    private val context: Context,
    private val language: String,
    private val onStatus: (String, Boolean) -> Unit,
    private val onText: (String) -> Unit,
    private val onFailure: (String, String) -> Unit,
) {
    private data class Engine(val component: ComponentName?, val onDevice: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private val engines = availableEngines()
    private var engineIndex = 0
    private var recognizer: SpeechRecognizer? = null
    private var closed = false
    private var ready = false
    private var finishing = false
    private var heardSpeech = false
    private var reconnected = false
    private var partial = ""
    private val timeout = Runnable { fail(SpeechRecognizer.ERROR_CLIENT, "语音服务响应超时") }
    private val retry = Runnable { connect() }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (engines.isEmpty()) {
            finishFailure("未找到可供应用调用的系统语音服务。可尝试系统语音窗口，或在系统设置中启用语音识别服务。")
            return
        }
        connect()
    }

    private fun availableEngines(): List<Engine> {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.enabled && it.exported && it.applicationInfo.enabled && it.permission == "android.permission.BIND_SPEECH_RECOGNITION_SERVICE" }
        val selected = ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service").orEmpty(),
        )
        val engines = mutableListOf<Engine>()
        services.firstOrNull { ComponentName(it.packageName, it.name) == selected }?.let { engines += Engine(selected) }
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            engines += Engine(null, onDevice = true)
        }
        // A user-selected engine is allowed above. Other automatic fallbacks must be system apps.
        services.filter { it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .sortedBy { it.packageName + it.name }
            .map { Engine(ComponentName(it.packageName, it.name)) }
            .forEach { if (it !in engines) engines += it }
        return engines
    }

    private fun connect() {
        if (closed) return
        ready = false
        finishing = false
        heardSpeech = false
        onStatus("正在连接系统语音服务，请稍候…", false)
        val engine = engines[engineIndex]
        try {
            val speech = if (engine.onDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context, engine.component)
            }
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                private fun current() = !closed && recognizer === speech
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!current()) return
                    ready = true
                    handler.removeCallbacks(timeout)
                    onStatus("请说话，识别文字会显示在这里", !finishing)
                }
                override fun onBeginningOfSpeech() {
                    if (!current()) return
                    ready = true
                    heardSpeech = true
                    handler.removeCallbacks(timeout)
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onEndOfSpeech() {
                    if (!current()) return
                    heardSpeech = true
                    finishing = true
                    onStatus(partial.ifBlank { "正在完成识别…" }, false)
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 12_000)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!current()) return
                    val text = partialResults.bestText()
                    if (text.isNotBlank()) {
                        partial = text
                        ready = true
                        heardSpeech = true
                        handler.removeCallbacks(timeout)
                        onStatus(text, !finishing)
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = results.bestText()
                    if (text.isBlank()) {
                        finishFailure("没有识别到语音内容，请重试")
                    } else {
                        close(cancel = false)
                        onText(text)
                    }
                }
                override fun onError(error: Int) {
                    if (current()) fail(error)
                }
            })
            handler.postDelayed(timeout, 8_000)
            speech.startListening(
                recognitionIntent(context, language).apply {
                    if (engine.onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                },
            )
        } catch (_: SecurityException) {
            finishFailure(errorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        } catch (_: Exception) {
            fail(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun fail(error: Int, message: String = errorMessage(error)) {
        if (closed) return
        Timber.w("System speech failed: code=%d engine=%s ready=%s", error, engines.getOrNull(engineIndex), ready)
        handler.removeCallbacks(timeout)
        val canRetry = !heardSpeech && partial.isBlank() && error in setOf(2, 4, 5, 8, 11, 12, 13)
        val reconnect = canRetry && error == SpeechRecognizer.ERROR_CLIENT && !reconnected
        if (reconnect || (canRetry && engineIndex + 1 < engines.size)) {
            release(cancel = true)
            if (reconnect) reconnected = true else engineIndex++
            onStatus("正在重新连接兼容的系统语音服务…", false)
            handler.postDelayed(retry, 400)
        } else {
            finishFailure(message)
        }
    }

    private fun finishFailure(message: String) {
        if (closed) return
        close(cancel = false)
        onFailure(message, partial)
    }

    fun finish() {
        if (closed || !ready || finishing) return
        finishing = true
        onStatus(partial.ifBlank { "正在完成识别…" }, false)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 12_000)
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            fail(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun close(cancel: Boolean = true) {
        closed = true
        handler.removeCallbacksAndMessages(null)
        release(cancel)
    }

    private fun release(cancel: Boolean) {
        val old = recognizer
        recognizer = null
        if (cancel) runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    private fun Bundle?.bestText() = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    companion object {
        fun recognitionIntent(context: Context, language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Some vendor recognizers still require the legacy caller package extra.
            putExtra("calling_package", context.packageName)
        }

        fun errorMessage(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统语音服务无法使用麦克风。请检查会意及语音服务的麦克风权限和系统麦克风开关。"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到清晰的语音，请重试"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用，请检查网络或下载离线语言包"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务正忙，请关闭其他录音或语音应用后重试"
            SpeechRecognizer.ERROR_CLIENT -> "系统语音服务连接失败（5）。可能是当前引擎无法供第三方应用调用，可尝试系统语音窗口或更换系统识别服务。"
            12, 13 -> "当前系统语音服务不支持所选语言，或尚未下载对应语言包"
            10 -> "系统语音请求过于频繁，请稍后重试"
            4, 11 -> "系统语音服务不可用，请尝试系统语音窗口或检查语音服务设置"
            else -> "系统语音识别失败（$error），请重试"
        }
    }
}
