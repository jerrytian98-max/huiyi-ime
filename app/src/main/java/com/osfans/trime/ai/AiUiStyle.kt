// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeColors
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber

internal object AiUiStyle {
    // Image-backed external themes use the complete fallback palette for AI panels.
    private val colors: ThemeColors?
        get() = runCatching {
            ColorManager.currentScope()?.colors?.also {
                it.backColor
                it.keyBackColor
            }
        }.getOrNull()

    private inline fun color(fallback: Int, read: (ThemeColors) -> Int): Int = runCatching { colors?.let(read) }.getOrNull() ?: fallback

    val background get() = color(Color.rgb(237, 237, 237)) { it.backColor }
    val surface get() = color(Color.WHITE) { it.keyBackColor }
    val text get() = color(Color.rgb(25, 25, 25)) { it.candidateTextColor }
    val secondary get() = color(Color.rgb(98, 103, 107)) { it.commentTextColor }
    val accent get() = color(Color.rgb(6, 116, 55)) { it.hilitedTextColor }
    private val border get() = color(Color.rgb(212, 213, 216)) { it.borderColor }
    private val isDark get() = ColorUtils.calculateLuminance(background) < 0.5
    private val actionBackground = Color.rgb(7, 193, 96)
    private val actionText = Color.rgb(16, 35, 24)

    fun prepare(activity: Activity, replyOverlay: Boolean = false) {
        runCatching { ThemeManager.init(activity.resources.configuration) }.onFailure { Timber.w(it, "Using fallback AI panel colors") }
        activity.setTheme(
            if (replyOverlay) {
                if (isDark) R.style.Theme_HuiyiAiReplyDark else R.style.Theme_HuiyiAiReplyLight
            } else if (isDark) {
                R.style.Theme_HuiyiAiDark
            } else {
                R.style.Theme_HuiyiAiLight
            },
        )
    }

    fun dialog(context: Context): AlertDialog.Builder = AlertDialog.Builder(
        ContextThemeWrapper(context, if (isDark) R.style.Theme_HuiyiAiDialogDark else R.style.Theme_HuiyiAiDialogLight),
    )

    fun content(activity: Activity, view: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.decorView.setBackgroundColor(background)
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        applyTextColors(view)
        activity.setContentView(view)
        ViewCompat.requestApplyInsets(view)
    }
    private fun applyTextColors(view: View) {
        if (view is TextView && view !is Button && view !is EditText && view.currentTextColor != secondary) view.setTextColor(text)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTextColors(view.getChildAt(index))
        }
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun panel(context: Context, color: Int = surface, radius: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), border)
    }
    fun input(view: EditText) {
        view.background = panel(view.context)
        view.setTextColor(text)
        view.setHintTextColor(secondary)
        view.backgroundTintList = null
        view.setPadding(dp(view.context, 14), dp(view.context, 12), dp(view.context, 14), dp(view.context, 12))
        view.minHeight = dp(view.context, 48)
        view.textSize = 16f
    }
    fun button(view: Button, primary: Boolean = false) {
        view.isAllCaps = false
        view.textSize = 15f
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.minHeight = dp(view.context, 48)
        view.backgroundTintList = null
        view.background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else ColorUtils.setAlphaComponent(accent, 26)), panel(view.context, if (primary) actionBackground else surface, 8), null)
        view.elevation = 0f
        view.stateListAnimator = null
        view.setPadding(dp(view.context, 16), dp(view.context, 8), dp(view.context, 16), dp(view.context, 8))
        view.setTextColor(if (primary) actionText else text)
    }
    fun showDialog(service: TrimeInputMethodService, dialog: AlertDialog, alignToKeyboard: Boolean = false, onDismiss: (() -> Unit)? = null) {
        service.showDialog(dialog, alignToKeyboard, onDismiss)
        styleDialog(service, dialog)
    }

    fun styleDialog(context: Context, dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(panel(context, radius = 20))
        dialog.window?.decorView?.let { applyTextColors(it) }
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                isAllCaps = false
                textSize = 14f
                setTextColor(if (which == AlertDialog.BUTTON_POSITIVE) accent else secondary)
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(AiUiStyle.text)
            textSize = 16f
            setLineSpacing(dp(context, 4).toFloat(), 1f)
        }
    }
}
