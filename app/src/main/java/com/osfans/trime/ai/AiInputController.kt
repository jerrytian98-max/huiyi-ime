// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** User-started AI operations and hold-to-dictate with a bound editor target. */
class AiInputController(private val service: TrimeInputMethodService) {
    private val handler = Handler(Looper.getMainLooper())
    private var systemSpeech: AiSystemSpeech? = null
    private var cloudVoiceJob: Job? = null
    private var cloudRecorder: AiAudioRecorder? = null
    private var heldVoice: HeldVoice? = null
    private var nextHeldVoiceId = 0L
    private class LongCapture(val id: String, val status: (String?) -> Unit) {
        var job: Job? = null
        var started = false
        var released = false
    }
    private var longCapture: LongCapture? = null
    private class HeldVoice(val id: Long, val epoch: Long, val revision: Long, val recorder: AiAudioRecorder, val status: (String?) -> Unit) {
        val released = CompletableDeferred<Unit>()
        var job: Job? = null
    }
    private var voiceDialog: AlertDialog? = null
    private var textDialog: AlertDialog? = null
    private var textJob: Job? = null
    private var generation = 0L
    private var selectionRevision = 0L
    private var captureTarget: AiEditTarget? = null
    private var captureRequestId = ""
    private var lastShownState: CaptureState? = null
    private var captureDialog: AlertDialog? = null
    private var captureDialogState: CaptureState? = null
    private var destroyed = false
    private val captureObserver: (CaptureState) -> Unit = { handler.post { maybeShowPendingSuggestions() } }
    private val reviewObserver: () -> Unit = { handler.post { maybeApplyPendingReview() } }
    private var reviewApplyJob: Job? = null
    private var reviewRestoreJob: Job? = null

    init {
        AiEvents.addCaptureObserver(captureObserver)
        AiTextReviewStore.addObserver(reviewObserver)
    }

    fun onEditorStarted() {
        val editor = service.currentInputEditorInfo
        val original = captureTarget
        val sameReleasedEditor = longCapture?.released == true && original != null &&
            editor?.packageName == original.packageName && editor.fieldId == original.fieldId
        if (!sameReleasedEditor) cancelLongScreenAnalysis()
        dismissCaptureDialog(endSession = true)
        generation++
        cancelText()
        stopVoice()
    }

    fun onEditorHidden() {
        longCapture?.takeIf { !it.released }?.let { finishLongScreenAnalysis(it.id) }
        dismissCaptureDialog(endSession = true)
        generation++
        cancelText()
        stopVoice()
    }

    fun onSelectionChanged() {
        if (longCapture?.released != true) cancelLongScreenAnalysis()
        selectionRevision++
        cancelHeldVoice()
    }

    fun openSettings() = openActivity(AiSettingsActivity::class.java)

    private fun openActivity(type: Class<*>) {
        service.startActivity(Intent(service, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun safeEditor(): Boolean {
        val info = service.currentInputEditorInfo ?: return false
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val kind = type and InputType.TYPE_MASK_CLASS
        val password = (
            kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            ) || (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return !password && type != InputType.TYPE_NULL && service.currentInputConnection != null &&
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }

    private fun requireEditor(): Boolean {
        if (safeEditor()) return true
        toast("请在普通文字输入框使用 AI 功能；密码或隐私输入框不可使用")
        return false
    }

    private suspend fun target(): AiEditTarget? {
        if (!safeEditor()) return null
        val info = service.currentInputEditorInfo ?: return null
        val connection = service.currentInputConnection ?: return null
        val epoch = generation
        val revision = selectionRevision
        // InputConnection reads can wait for a remote application's main thread.
        // Never perform them on the keyboard main thread, including during capture callbacks.
        val snapshot = try {
            withTimeoutOrNull(2500) {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val before = async { connection.getTextBeforeCursor(64, 0)?.toString() }
                        val selected = async { connection.getSelectedText(0)?.toString() }
                        val after = async { connection.getTextAfterCursor(64, 0)?.toString() }
                        AiEditTarget(info.packageName.orEmpty(), info.fieldId, before.await(), selected.await(), after.await())
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (destroyed || epoch != generation || revision != selectionRevision || !safeEditor()) return null
        if (snapshot?.before == null && snapshot?.after == null) return null
        return snapshot
    }

    private suspend fun matches(value: AiEditTarget?): Boolean = value != null && service.isInputViewShown && value == target()

    private fun insertOrCopy(text: String, original: AiEditTarget?, wholeDraft: AiWholeDraft? = null) {
        service.lifecycleScope.launch {
            if (wholeDraft != null && original != null) {
                replaceWholeOrCopy(text, original, wholeDraft)
            } else {
                commitOrCopy(text, matches(original))
            }
        }
    }

    private suspend fun replaceWholeOrCopy(text: String, original: AiEditTarget, wholeDraft: AiWholeDraft) {
        val current = readWholeDraft(original).snapshot
        if (service.isInputViewShown && current == wholeDraft &&
            service.replaceAiWholeDraft(text, wholeDraft.text.length, wholeDraft.cursor)
        ) {
            return
        }
        copyResult(text)
    }

    private data class WholeDraftRead(val snapshot: AiWholeDraft? = null, val tooLong: Boolean = false)

    private suspend fun readWholeDraft(original: AiEditTarget): WholeDraftRead {
        if (!safeEditor()) return WholeDraftRead()
        val info = service.currentInputEditorInfo ?: return WholeDraftRead()
        if (info.packageName.orEmpty() != original.packageName || info.fieldId != original.fieldId) return WholeDraftRead()
        val connection = service.currentInputConnection ?: return WholeDraftRead()
        val epoch = generation
        val revision = selectionRevision
        val result = try {
            withTimeoutOrNull(3500) {
                withContext(Dispatchers.IO) {
                    val extracted = connection.getExtractedText(
                        ExtractedTextRequest().apply {
                            hintMaxChars = AiClient.MAX_TEXT_LENGTH + 1
                            hintMaxLines = Int.MAX_VALUE
                        },
                        0,
                    ) ?: return@withContext WholeDraftRead()
                    val text = extracted.text?.toString() ?: return@withContext WholeDraftRead()
                    if (text.length > AiClient.MAX_TEXT_LENGTH) return@withContext WholeDraftRead(tooLong = true)
                    val before = connection.getTextBeforeCursor(AiClient.MAX_TEXT_LENGTH + 1, 0)?.toString()
                    val after = connection.getTextAfterCursor(AiClient.MAX_TEXT_LENGTH + 1, 0)?.toString()
                    WholeDraftRead(AiWholeDraft.verify(text, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd, before, after))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (destroyed || epoch != generation || revision != selectionRevision || !safeEditor() || service.currentInputConnection !== connection) return WholeDraftRead()
        return result?.takeIf { it.snapshot?.matches(original) ?: it.tooLong } ?: WholeDraftRead()
    }

    private fun commitOrCopy(text: String, sameTarget: Boolean) {
        if (sameTarget && service.commitAiText(text)) return
        if (!sameTarget && safeEditor() && service.isInputViewShown) {
            val dialog = AiUiStyle.dialog(service)
                .setTitle("插入 AI 文字")
                .setMessage("光标或输入框已变化。是否将这段文字插入现在的光标位置？\n\n$text")
                .setPositiveButton("插入当前光标") { _, _ ->
                    service.lifecycleScope.launch {
                        val destination = target()
                        if (destination != null && matches(destination) && service.commitAiText(text)) return@launch
                        copyResult(text)
                    }
                }
                .setNeutralButton("复制文字") { _, _ -> copyResult(text) }
                .setNegativeButton("取消", null)
                .create()
            AiUiStyle.showDialog(service, dialog)
            return
        }
        copyResult(text)
    }

    private fun copyResult(text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AI 输入", text))
        val message = "输入框已变化或暂时无响应，结果已复制，请在目标位置粘贴"
        if (!destroyed && service.isInputViewShown && service.window?.window?.decorView?.windowToken != null) {
            textDialog = AiUiStyle.dialog(service).setTitle("结果已复制").setMessage(message)
                .setPositiveButton("知道了", null).create().also { AiUiStyle.showDialog(service, it) }
        } else {
            toast(message)
        }
    }

    private fun maybeApplyPendingReview() {
        if (destroyed || reviewApplyJob?.isActive == true) return
        val session = AiTextReviewStore.current?.takeIf { it.result != null && it.returnedToEditor } ?: return
        if (!service.isInputViewShown || !safeEditor()) {
            if (reviewRestoreJob?.isActive != true) {
                reviewRestoreJob = service.lifecycleScope.launch {
                    try {
                        repeat(20) {
                            if (destroyed || AiTextReviewStore.current?.id != session.id) return@launch
                            if (service.isInputViewShown && safeEditor()) {
                                maybeApplyPendingReview()
                                return@launch
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                AiAccessibilityCaptureService.focusOriginalEditor(session.target.packageName)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && service.currentInputEditorInfo?.packageName == session.target.packageName) {
                                service.requestShowSelf(0)
                            }
                            kotlinx.coroutines.delay(250)
                        }
                        if (AiTextReviewStore.current?.id == session.id) {
                            AiTextReviewStore.clear(session.id)
                            copyResult(session.result.orEmpty())
                        }
                    } finally {
                        reviewRestoreJob = null
                    }
                }
            }
            return
        }
        reviewApplyJob = service.lifecycleScope.launch {
            try {
                if (destroyed || AiTextReviewStore.current?.id != session.id) return@launch
                if (session.wholeDraft != null) {
                    replaceWholeOrCopy(session.result.orEmpty(), session.target, session.wholeDraft)
                } else {
                    commitOrCopy(session.result.orEmpty(), matches(session.target))
                }
                AiTextReviewStore.clear(session.id)
            } finally {
                reviewApplyJob = null
            }
        }
    }

    /** Called once after the space key's long-press threshold; the key retains its touch stream. */
    fun startHeldVoice(status: (String?) -> Unit): Long? {
        if (destroyed || !service.isInputViewShown || !requireEditor()) return null
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openActivity(AiMicrophonePermissionActivity::class.java)
            return null
        }
        stopVoice()
        cancelText()
        dismissCaptureDialog(endSession = true)
        val session = HeldVoice(++nextHeldVoiceId, generation, selectionRevision, AiAudioRecorder(service), status)
        heldVoice = session
        status("准备录音")
        service.showHeldVoiceRecording(true)
        session.job = service.lifecycleScope.launch {
            try {
                coroutineScope {
                    // Capture immediately while the editor snapshot and configuration load in parallel.
                    val recording = async {
                        session.recorder.record(onLevel = { level ->
                            handler.post {
                                if (heldVoice === session && !session.released.isCompleted) service.updateHeldVoiceRecording(level = level)
                            }
                        }) { seconds ->
                            handler.post {
                                if (heldVoice === session && !session.released.isCompleted) {
                                    status("${seconds}秒·松开转写")
                                    service.updateHeldVoiceRecording(seconds = seconds)
                                }
                            }
                        }
                    }
                    val original = target() ?: error("暂时无法读取输入位置，请重新长按空格")
                    val config = withContext(Dispatchers.IO) { AiConfigStore.load(service) }
                    check(config.cloudSpeech && config.isSpeechReady) { "请先在 AI 设置中配置并启用云端语音" }
                    val audio = recording.await()
                    // At the 60-second limit the microphone stops, but upload still waits for release.
                    if (heldVoice === session && !session.released.isCompleted) {
                        status("录音结束·松开转写")
                        service.updateHeldVoiceRecording(seconds = 60)
                        service.showHeldVoiceRecording(true, atLimit = true)
                    }
                    session.released.await()
                    if (!isCurrentHeldVoice(session)) return@coroutineScope
                    service.showHeldVoiceRecording(false)
                    status("转写中…")
                    val transcript = withTimeout(60_000) { AiSpeechClient.transcribe(config, audio) }
                    if (!isCurrentHeldVoice(session)) return@coroutineScope
                    val sameTarget = matches(original)
                    if (!isCurrentHeldVoice(session)) return@coroutineScope
                    heldVoice = null
                    status(null)
                    if (!sameTarget) {
                        toast("输入位置已变化，本次语音已取消")
                    } else if (!service.commitDictationText(transcript)) {
                        toast("输入框暂时无法接收文字，请重新长按空格")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (heldVoice === session) toast("语音转写超时，请重新长按空格")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (heldVoice === session) toast(error.message ?: "语音转写失败，请重新长按空格")
            } finally {
                session.recorder.finish()
                if (heldVoice === session) {
                    heldVoice = null
                    service.showHeldVoiceRecording(false)
                    status(null)
                }
            }
        }
        return session.id
    }

    private fun isCurrentHeldVoice(session: HeldVoice): Boolean = heldVoice === session && !destroyed &&
        session.epoch == generation && session.revision == selectionRevision && service.isInputViewShown

    fun finishHeldVoice(id: Long) {
        val session = heldVoice?.takeIf { it.id == id } ?: return
        session.released.complete(Unit)
        session.recorder.finish()
        service.showHeldVoiceRecording(false)
        session.status("转写中…")
    }

    fun cancelHeldVoice(id: Long? = null) {
        val session = heldVoice ?: return
        if (id != null && session.id != id) return
        heldVoice = null
        service.showHeldVoiceRecording(false)
        session.recorder.finish()
        session.job?.cancel()
        session.status(null)
    }

    fun startVoice() {
        service.lifecycleScope.launch { startVoiceInternal() }
    }

    private suspend fun startVoiceInternal() {
        if (!requireEditor()) return
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openActivity(AiMicrophonePermissionActivity::class.java)
            return
        }
        val config = AiConfigStore.load(service)
        if (config.cloudSpeech && !config.isSpeechReady) {
            toast("请先在 AI 设置中配置所选语音服务的凭证")
            openSettings()
            return
        }
        stopVoice()
        cancelText()
        val original = target() ?: return toast("暂时无法读取输入框，请回到聊天框后重试").let { }
        stopVoice()
        val epoch = generation
        if (config.cloudSpeech) {
            startCloudVoice(config, original, epoch)
            return
        }
        val dialog = AiUiStyle.dialog(service)
            .setTitle("语音输入 · ${if (config.speechLanguage == "en-US") "English" else "中文"}")
            .setMessage("正在连接系统语音服务，请稍候…")
            .setNegativeButton("取消") { _, _ -> stopVoice() }
            .setPositiveButton("完成", null)
            .create()
        voiceDialog = dialog
        dialog.setOnCancelListener { stopVoice() }
        val speech = AiSystemSpeech(
            service,
            config.speechLanguage,
            onStatus = { message, canFinish ->
                if (epoch == generation && voiceDialog === dialog) {
                    dialog.setMessage(message)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canFinish
                }
            },
            onText = { transcript ->
                if (epoch == generation && voiceDialog === dialog) {
                    stopVoice(cancel = false)
                    if (config.isTextReady) {
                        polish(transcript, original, epoch)
                    } else {
                        showTextResult(transcript, transcript, original, "尚未配置 AI 服务，可先插入语音原文。")
                    }
                }
            },
            onFailure = { message, partial ->
                if (epoch == generation && voiceDialog === dialog) {
                    stopVoice(cancel = false)
                    if (partial.isNotBlank()) {
                        showTextResult(partial, partial, original, "$message。以下为临时识别，请核对。")
                    } else {
                        showSystemVoiceError(message, original, config.speechLanguage)
                    }
                }
            },
        )
        systemSpeech = speech
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener { speech.finish() }
            }
        }
        AiUiStyle.showDialog(service, dialog)
        speech.start()
    }

    private fun showSystemVoiceError(message: String, original: AiEditTarget, language: String) {
        textDialog = AiUiStyle.dialog(service).setTitle("系统语音暂不可用")
            .setMessage(message)
            .setPositiveButton("重试") { _, _ -> startVoice() }
            .setNeutralButton("系统语音窗口") { _, _ ->
                val id = AiTextReviewStore.begin(original, "", "")
                try {
                    service.startActivity(
                        Intent(service, AiSystemSpeechActivity::class.java)
                            .putExtra(AiTextReviewActivity.EXTRA_ID, id)
                            .putExtra("language", language)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {
                    AiTextReviewStore.clear(id)
                    toast("无法打开系统语音窗口，请在 AI 设置中检查系统语音服务")
                }
            }
            .setNegativeButton("关闭", null)
            .create().also { AiUiStyle.showDialog(service, it) }
    }

    private fun startCloudVoice(config: AiConfig, original: AiEditTarget, epoch: Long) {
        val recorder = AiAudioRecorder(service)
        cloudRecorder = recorder
        val dialog = AiUiStyle.dialog(service)
            .setTitle("正在录音 · 云端转写")
            .setMessage("请说话，点击完成后上传录音并转写。最长60秒；取消则放弃。")
            .setPositiveButton("完成", null)
            .setNegativeButton("取消") { _, _ -> stopVoice() }
            .create()
        voiceDialog = dialog
        dialog.setOnCancelListener { stopVoice() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                recorder.finish()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            }
        }
        AiUiStyle.showDialog(service, dialog)
        cloudVoiceJob = service.lifecycleScope.launch {
            try {
                val wav = recorder.record { seconds ->
                    handler.post {
                        if (epoch == generation && cloudRecorder === recorder && dialog.isShowing) {
                            dialog.setMessage("已录音 $seconds 秒，点击完成后上传并转写。最长60秒；取消则放弃。")
                        }
                    }
                }
                if (epoch != generation || destroyed) return@launch
                cloudRecorder = null
                dialog.setTitle("正在转写")
                dialog.setMessage("录音已结束，正在发送到配置的语音服务。取消可停止请求。")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val transcript = withTimeout(60_000) { AiSpeechClient.transcribe(config, wav) }
                if (epoch != generation || destroyed) return@launch
                voiceDialog = null
                cloudVoiceJob = null
                dialog.dismiss()
                if (config.isTextReady) {
                    polish(transcript, original, epoch)
                } else {
                    showTextResult(transcript, transcript, original, "尚未配置 AI 整理，可插入语音原文。")
                }
            } catch (_: TimeoutCancellationException) {
                if (epoch == generation && !destroyed) showVoiceError("云端转写超时，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == generation && !destroyed) showVoiceError(error.message ?: "云端转写失败，请检查麦克风及服务设置")
            } finally {
                if (cloudRecorder === recorder) cloudRecorder = null
                if (voiceDialog === dialog) {
                    voiceDialog = null
                    cloudVoiceJob = null
                }
                dialog.dismiss()
            }
        }
    }

    private fun showVoiceError(message: String) {
        cloudVoiceJob = null
        textDialog = AiUiStyle.dialog(service).setTitle("语音转写未完成")
            .setMessage(message)
            .setPositiveButton("重试") { _, _ -> startVoice() }
            .setNegativeButton("关闭", null)
            .create().also { AiUiStyle.showDialog(service, it) }
    }

    fun polishSelection() {
        service.lifecycleScope.launch { polishSelectionInternal() }
    }

    private suspend fun polishSelectionInternal() {
        if (!requireEditor()) return
        val original = target() ?: return toast("暂时无法读取输入框，请回到聊天框后重试").let { }
        val selected = original.selected.orEmpty()
        val hasSelection = selected.isNotEmpty()
        val wholeRead = if (!hasSelection) readWholeDraft(original) else null
        val wholeDraft = wholeRead?.snapshot
        val raw = if (hasSelection) selected else wholeDraft?.text.orEmpty()
        if (wholeRead?.tooLong == true || raw.length > AiClient.MAX_TEXT_LENGTH) {
            toast("一次最多整理 ${AiClient.MAX_TEXT_LENGTH} 个字符，请分段选择")
            return
        }
        if (!hasSelection && wholeDraft == null) {
            toast("无法确认输入框的完整内容；请手动选中要整理的文字")
            return
        }
        if (raw.isBlank()) return toast("输入框中没有可整理的文字")
        if (!AiConfigStore.load(service).isTextReady) {
            toast("请先配置 AI 文字服务")
            openSettings()
            return
        }
        stopVoice()
        polish(raw, original, generation, wholeDraft)
    }

    private fun polish(raw: String, original: AiEditTarget, epoch: Long, wholeDraft: AiWholeDraft? = null) {
        cancelText()
        val dialog = AiUiStyle.dialog(service).setTitle("正在整理")
            .setMessage(raw)
            .setNeutralButton(if (wholeDraft == null) "使用原文" else "保留原文") { _, _ ->
                textJob?.cancel()
                if (wholeDraft == null) insertOrCopy(raw, original)
            }
            .setNegativeButton("取消") { _, _ -> textJob?.cancel() }
            .create()
        textDialog = dialog
        dialog.setOnCancelListener { textJob?.cancel() }
        AiUiStyle.showDialog(service, dialog)
        textJob = service.lifecycleScope.launch {
            try {
                val polished = withTimeout(60_000) {
                    AiClient.polish(AiConfigStore.load(service), raw) { partial ->
                        handler.post { if (generation == epoch && dialog.isShowing) dialog.setMessage(partial) }
                    }
                }
                if (epoch == generation && !destroyed) showTextResult(raw, polished, original, wholeDraft = wholeDraft)
            } catch (_: TimeoutCancellationException) {
                if (epoch == generation && !destroyed) showTextResult(raw, raw, original, "AI 整理超时，可使用原文", wholeDraft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == generation && !destroyed) showTextResult(raw, raw, original, error.message ?: "AI 整理失败，可使用原文", wholeDraft)
            } finally {
                dialog.dismiss()
                if (textDialog === dialog) textDialog = null
            }
        }
    }

    private fun showTextResult(raw: String, polished: String, original: AiEditTarget, warning: String = "", wholeDraft: AiWholeDraft? = null) {
        val dialog = AiUiStyle.dialog(service)
            .setTitle("整理结果")
            .setMessage(if (warning.isEmpty()) "整理后：\n$polished\n\n原文：\n$raw" else "$warning\n\n原文：\n$raw")
            .setPositiveButton(
                if (warning.isEmpty()) {
                    "插入整理稿"
                } else if (wholeDraft != null) {
                    "保留原文"
                } else {
                    "插入原文"
                },
            ) { _, _ ->
                if (warning.isEmpty() || wholeDraft == null) insertOrCopy(if (warning.isEmpty()) polished else raw, original, wholeDraft)
            }
            .setNegativeButton("取消", null)
            .setView(
                android.widget.FrameLayout(service).apply {
                    setPadding(AiUiStyle.dp(service, 24), AiUiStyle.dp(service, 4), AiUiStyle.dp(service, 24), 0)
                    addView(
                        Button(service).apply {
                            text = "编辑后插入"
                            AiUiStyle.button(this)
                            setOnClickListener {
                                val id = AiTextReviewStore.begin(original, raw, if (warning.isEmpty()) polished else raw, wholeDraft)
                                cancelText()
                                service.startActivity(
                                    Intent(service, AiTextReviewActivity::class.java)
                                        .putExtra(AiTextReviewActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                            }
                        },
                        android.widget.FrameLayout.LayoutParams(-1, -2),
                    )
                },
            )
        if (warning.isEmpty()) {
            dialog.setNeutralButton(if (wholeDraft == null) "插入原文" else "保留原文") { _, _ ->
                if (wholeDraft == null) insertOrCopy(raw, original)
            }
        }
        textDialog = dialog.create().also { AiUiStyle.showDialog(service, it) }
    }

    fun startScreenAnalysis() {
        if (longCapture != null) {
            cancelLongScreenAnalysis()
            toast("已停止本次长截图")
            return
        }
        if (!requireEditor()) return
        val state = AiEvents.captureState
        if (state.isInProgress || state.status == CaptureStatus.READY || state.status == CaptureStatus.ERROR) {
            showCaptureState(state)
            return
        }
        launchCapture()
    }

    fun startLongScreenAnalysis(keyboardTop: Int, status: (String?) -> Unit): String? {
        if (destroyed || !service.isInputViewShown || !requireEditor()) return null
        if (!AiQuickCapture.isSupported || !AiQuickCapture.isReady(service)) {
            toast("长截图需要 Android 11 及以上，请先在 AI 设置中开启并授权快捷AI回复")
            status(null)
            return null
        }
        cancelLongScreenAnalysis()
        stopVoice()
        cancelText()
        dismissCaptureDialog(endSession = true)
        val session = LongCapture(UUID.randomUUID().toString(), status)
        if (AiQuickCapture.isSupported) AiAccessibilityCaptureService.cancelCurrent()
        AiEvents.finishSession(AiEvents.captureState.requestId)
        val epoch = generation
        longCapture = session
        status("准备中")
        session.job = service.lifecycleScope.launch {
            try {
                check(AiConfigStore.load(service).isVisionReady) { "请先配置 AI回复服务" }
                val original = target() ?: error("无法读取输入位置，请重新上拖AI回复")
                if (longCapture !== session || epoch != generation || !service.isInputViewShown) return@launch
                captureTarget = original
                captureRequestId = session.id
                AiCaptureInsertion.remember(session.id, original)
                if (!AiQuickCapture.isSupported) return@launch
                session.started = AiAccessibilityCaptureService.tryLongCapture(service, session.id, original.packageName, keyboardTop, keyboardTopProvider = { service.currentKeyboardTopOnScreen() }) { text ->
                    if (longCapture === session) {
                        status(text)
                        if (text == null) {
                            longCapture = null
                            val result = AiEvents.captureState
                            if (!service.isInputViewShown && result.requestId == session.id &&
                                (
                                    result.status == CaptureStatus.ERROR ||
                                        (result.status == CaptureStatus.READY && AiConfigStore.showReplyOptions(service))
                                    )
                            ) {
                                service.startActivity(
                                    Intent(service, AiReplyActivity::class.java)
                                        .putExtra(AiReplyActivity.EXTRA_KEYBOARD_TOP, keyboardTop)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                            } else {
                                maybeShowPendingSuggestions()
                            }
                        }
                    }
                }
                check(session.started) { "快捷截屏服务未就绪，请重新开启授权" }
                if (session.released) AiAccessibilityCaptureService.finishLongCapture(session.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (longCapture === session) toast(error.message ?: "长截图失败，请重试")
            } finally {
                if (!session.started && longCapture === session) {
                    longCapture = null
                    status(null)
                }
            }
        }
        return session.id
    }

    fun finishLongScreenAnalysis(id: String) {
        val session = longCapture?.takeIf { it.id == id } ?: return
        session.released = true
        session.status("截图中")
        if (AiQuickCapture.isSupported && session.started) AiAccessibilityCaptureService.finishLongCapture(id)
    }

    fun cancelLongScreenAnalysis(id: String? = null) {
        val session = longCapture ?: return
        if (id != null && session.id != id) return
        longCapture = null
        session.job?.cancel()
        if (AiQuickCapture.isSupported) AiAccessibilityCaptureService.cancelLongCapture(session.id)
        AiEvents.finishSession(session.id)
        session.status(null)
    }

    private fun launchCapture() {
        service.lifecycleScope.launch { launchCaptureInternal() }
    }

    private suspend fun launchCaptureInternal() {
        if (!AiConfigStore.load(service).isVisionReady) {
            toast("请先配置 AI回复服务")
            openSettings()
            return
        }
        captureTarget = target() ?: return toast("暂时无法读取输入框，请回到聊天框后重试").let { }
        captureRequestId = UUID.randomUUID().toString()
        AiCaptureInsertion.remember(captureRequestId, captureTarget!!)
        if (AiQuickCapture.isSupported && AiAccessibilityCaptureService.tryCapture(service, captureRequestId)) return
        service.startActivity(
            Intent(service, AiCaptureActivity::class.java)
                .putExtra(AiCaptureActivity.EXTRA_REQUEST_ID, captureRequestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun maybeShowPendingSuggestions() {
        if (AiTextReviewStore.current?.result != null) {
            maybeApplyPendingReview()
            return
        }
        val state = AiEvents.captureState
        if (!destroyed && state.requestId == captureRequestId && longCapture == null && state.status == CaptureStatus.READY &&
            state.suggestions.isNotEmpty() && !AiConfigStore.showReplyOptions(service)
        ) {
            dismissCaptureDialog(endSession = false)
            val reply = state.suggestions.first()
            if (AiCaptureInsertion.submit(state, reply)) {
                AiTextReviewStore.notifyReturnedToEditor()
                maybeApplyPendingReview()
            } else if (AiTextReviewStore.current?.result == null) {
                copyResult(reply)
            }
            AiEvents.finishSession(state.requestId)
            AiCaptureService.dismissReadyNotification(service)
            return
        }
        if (destroyed || !service.isInputViewShown || state.requestId != captureRequestId ||
            state == lastShownState ||
            (state.status != CaptureStatus.READY && state.status != CaptureStatus.ERROR) ||
            heldVoice != null || longCapture != null || systemSpeech != null || cloudVoiceJob != null || textDialog?.isShowing == true
        ) {
            return
        }
        service.lifecycleScope.launch {
            if (matches(captureTarget) && state.requestId == AiEvents.captureState.requestId &&
                state != lastShownState && state == AiEvents.captureState && !destroyed
            ) {
                showCaptureState(state)
            }
        }
    }

    private fun showCaptureState(state: CaptureState) {
        if (captureDialogState?.requestId != state.requestId || captureDialog?.isShowing != true) {
            dismissCaptureDialog(endSession = false)
            val dialog = AiReplyChooser.create(service)
            captureDialog = dialog
            captureDialogState = state
            AiUiStyle.showDialog(service, dialog, alignToKeyboard = true) {
                if (captureDialog === dialog) {
                    captureDialog = null
                    captureDialogState = null
                    AiEvents.finishSession(state.requestId)
                }
            }
            dialog.listView.setOnItemClickListener { _, _, which, _ ->
                val current = captureDialogState ?: return@setOnItemClickListener
                if (current != AiEvents.captureState || which !in current.suggestions.indices) return@setOnItemClickListener
                insertOrCopy(current.suggestions[which], captureTarget)
                AiEvents.clearSuggestions()
                dialog.dismiss()
            }
            // Keep one window while regenerating so focus never leaves the original editor.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = captureDialogState ?: return@setOnClickListener
                if (current.isInProgress) {
                    dismissCaptureDialog(endSession = false)
                    openActivity(AiReplyActivity::class.java)
                } else if (AiCaptureReplies.regenerate(service, current)) {
                    showCaptureState(AiEvents.captureState)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (AiQuickCapture.isSupported) AiAccessibilityCaptureService.cancelCurrent()
                dismissCaptureDialog(endSession = true)
            }
        }
        lastShownState = state
        captureDialogState = state
        val dialog = captureDialog ?: return
        AiReplyChooser.render(dialog, state)
    }

    private fun dismissCaptureDialog(endSession: Boolean) {
        val dialog = captureDialog ?: return
        val requestId = captureDialogState?.requestId.orEmpty()
        captureDialog = null
        captureDialogState = null
        if (endSession) AiEvents.finishSession(requestId)
        dialog.dismiss()
    }

    private fun stopVoice(cancel: Boolean = true) {
        cancelHeldVoice()
        cloudRecorder?.finish()
        cloudRecorder = null
        cloudVoiceJob?.cancel()
        cloudVoiceJob = null
        systemSpeech?.close(cancel)
        systemSpeech = null
        voiceDialog?.dismiss()
        voiceDialog = null
    }
    private fun cancelText() {
        textJob?.cancel()
        textJob = null
        textDialog?.dismiss()
        textDialog = null
    }
    fun destroy() {
        cancelLongScreenAnalysis()
        destroyed = true
        dismissCaptureDialog(endSession = true)
        stopVoice()
        cancelText()
        handler.removeCallbacksAndMessages(null)
        AiEvents.removeCaptureObserver(captureObserver)
        AiTextReviewStore.removeObserver(reviewObserver)
    }
    private fun toast(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()
}
