// SPDX-FileCopyrightText: 2026 Rime AI contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.UUID

/** Each one-shot capture uses fresh system consent, including on Android 14+. */
class AiCaptureActivity : Activity() {
    private var requestId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(EXTRA_REQUEST_ID)
            ?: intent.getStringExtra(EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString()
        val requiresIntent = intent.getBooleanExtra(EXTRA_REQUIRE_INTENT, false)
        if (requiresIntent && !AiEvents.hasReplyIntent(requestId)) {
            AiEvents.cancelCapture(requestId)
            Toast.makeText(this, "本次回复要求已失效，请重新下滑说话", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            if (!requiresIntent && !AiEvents.beginCapture(requestId)) {
                startActivity(Intent(this, AiReplyActivity::class.java))
                finish()
                return
            }
        } else if (AiEvents.captureState.requestId != requestId) {
            // The process may have been recreated while system consent was on screen.
            AiEvents.beginCapture(requestId)
        }
        if (savedInstanceState == null) {
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                startActivityForResult(captureIntent, REQUEST_CAPTURE)
            } catch (e: Exception) {
                AiEvents.failCapture(requestId, "无法打开截屏授权：${e.message ?: "请重试"}")
                Toast.makeText(this, AiEvents.captureState.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_REQUEST_ID, requestId)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("The platform projection consent flow returns through this callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (AiEvents.captureState.requestId != requestId || !AiEvents.isCaptureInProgress) {
            finish()
            return
        }
        if (resultCode == RESULT_OK && data != null) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AiCaptureService::class.java).apply {
                        putExtra(EXTRA_REQUEST_ID, requestId)
                        putExtra(AiCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(AiCaptureService.EXTRA_RESULT_DATA, data)
                    },
                )
            } catch (e: Exception) {
                AiEvents.failCapture(requestId, "截屏服务启动失败：${e.message ?: "请重试"}")
                Toast.makeText(this, AiEvents.captureState.message, Toast.LENGTH_LONG).show()
            }
        } else {
            AiEvents.cancelCapture(requestId)
            Toast.makeText(this, "已取消截屏", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "ai_capture_request_id"
        const val EXTRA_REQUIRE_INTENT = "ai_capture_requires_intent"
        private const val REQUEST_CAPTURE = 7301
    }
}
