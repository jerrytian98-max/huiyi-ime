// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** A short-lived Activity is required for Android's runtime microphone permission prompt. */
class AiMicrophonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finishAndResume()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            finishAndResume()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishAndResume() {
        finish()
        Toast.makeText(this, "已允许麦克风，请返回输入框重新使用语音", Toast.LENGTH_LONG).show()
    }
}
