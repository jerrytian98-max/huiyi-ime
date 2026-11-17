// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.net.toUri

/** A user opt-in, separate from model credentials. This never grants system permissions. */
object AiQuickCapture {
    private const val PREFS = "ai_capture_options"
    private const val ENABLED = "quick_capture_enabled"

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isRequested(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun isReady(context: Context): Boolean = isSupported && isRequested(context) && AiAccessibilityCaptureService.isConnected

    fun setRequested(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled && isSupported) AiAccessibilityCaptureService.cancelCurrent()
    }

    fun openAuthorization(context: Context) {
        if (!isSupported) return
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
