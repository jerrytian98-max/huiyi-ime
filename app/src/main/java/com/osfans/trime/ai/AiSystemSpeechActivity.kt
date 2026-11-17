// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent

/** Explicit user-selected fallback for vendor engines exposing only the recognition Activity. */
class AiSystemSpeechActivity : Activity() {
    private var requestId = ""
    private var waitingForResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AiUiStyle.prepare(this)
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(AiTextReviewActivity.EXTRA_ID).orEmpty()
        if (AiTextReviewStore.current?.id != requestId) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            waitingForResult = savedInstanceState.getBoolean("speech_window_pending")
            if (!waitingForResult) showUnavailable()
            return
        }
        waitingForResult = true
        try {
            startActivityForResult(
                AiSystemSpeech.recognitionIntent(this, intent.getStringExtra("language") ?: "zh-CN")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "会意语音输入"),
                1,
            )
        } catch (_: ActivityNotFoundException) {
            showUnavailable()
        } catch (_: SecurityException) {
            showUnavailable()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // A failed launch can both throw and deliver RESULT_CANCELED. Keep its help dialog open.
        if (requestCode != 1 || !waitingForResult) return
        waitingForResult = false
        val session = AiTextReviewStore.current?.takeIf { it.id == requestId }
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
        if (resultCode == RESULT_OK && text.isNotBlank() && session != null) {
            val id = AiTextReviewStore.begin(session.target, text, text)
            startActivity(Intent(this, AiTextReviewActivity::class.java).putExtra(AiTextReviewActivity.EXTRA_ID, id))
        }
        finish()
    }

    private fun showUnavailable() {
        waitingForResult = false
        AiUiStyle.dialog(this).setTitle("系统语音窗口不可用")
            .setMessage("手机未安装或未启用可供其他应用调用的语音识别服务。请在系统设置中启用支持的语音服务，并检查麦克风权限及中文语言包。默认 DeepSeek 配置用于文字和图片处理，未配置音频转写服务。")
            .setPositiveButton("语音服务设置") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                finish()
            }
            .setNegativeButton("关闭") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("speech_window_pending", waitingForResult)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) AiTextReviewStore.cancelEditing(requestId)
        super.onDestroy()
    }
}
