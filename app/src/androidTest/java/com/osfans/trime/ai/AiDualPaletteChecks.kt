// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.preference.PreferenceManager
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeLoader
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.AlwaysUi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Production controls and migration checks without AI or microphone calls. */
internal object AiDualPaletteChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val rows = mutableListOf<String>()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                checkMigration(context, rows)
                ThemeManager.init(context.resources.configuration)
                ThemeManager.syncToDeviceEncryptedStorage()
                check(PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext()).getBoolean(ThemePrefs.DUAL_PALETTE_APPLIED, false))
                rows += "PASS: direct-boot storage retains the one-time palette migration marker"
                val preferences = ThemeManager.prefs
                val original = preferences.normalModeColor.getValue()
                val followSystem = preferences.followSystemDayNight.getValue()
                try {
                    preferences.followSystemDayNight.setValue(false)
                    for (id in listOf("rime_ai.trime", "trime", "tongwenfeng.trime")) {
                        val theme = (ThemeLoader.loadTheme(id) as ThemeLoader.ThemeLoadResult.Success).theme
                        check(theme.presetColorSchemes.keys == setOf("default", "dark"))
                        val scope = ThemeScope(theme)
                        for ((scheme, palette) in theme.presetColorSchemes) {
                            scope.updateScheme(palette)
                            checkKeyboardContrast(scope)
                            rows += "PASS: $id / $scheme keyboard, candidate and preview color contrast >= 4.5"
                        }
                    }
                    val theme = ThemeManager.activeTheme
                    for (fontScale in listOf(1f, 1.3f)) {
                        val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
                        val viewContext = context.createConfigurationContext(configuration)
                        val scope = ThemeScope(theme).apply { updateScheme(theme.presetColorSchemes.getValue("default")) }
                        val clicks = IntArray(3)
                        val ui = AlwaysUi(viewContext, scope, onAiVoice = { clicks[0]++ }, onAiPolish = { clicks[1]++ }, onAiScreen = { clicks[2]++ })
                        val descriptions = listOf("语音输入和 AI 整理", "AI 整理输入框文字或选中文字", "AI回复")
                        val buttons = descriptions.map { description -> ui.root.children.single { it.contentDescription?.startsWith(description) == true } as LinearLayout }
                        for (scheme in listOf("default", "dark")) {
                            scope.updateScheme(theme.presetColorSchemes.getValue(scheme))
                            ColorManager.setColorScheme(scheme)
                            check(ThemePrefs(preferences.normalModeColor.sharedPreferences).normalModeColor.getValue() == scheme)
                            checkAiControls(viewContext)
                            ui.refreshColors()
                            ui.root.setBackgroundColor(scope.colors.candidateBackground)
                            for (widthDp in listOf(320, 411)) {
                                for (state in AlwaysUi.State.entries) {
                                    ui.updateState(state)
                                    val density = viewContext.resources.displayMetrics.density
                                    val width = (widthDp * density).toInt()
                                    val height = ((theme.style.candidateViewHeight + theme.style.commentHeight) * density).toInt()
                                    ui.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                                    ui.root.layout(0, 0, width, height)
                                    buttons.forEachIndexed { index, button ->
                                        check(button.visibility == View.VISIBLE && button.alpha == 1f && button.width > 0)
                                        check(button.left >= 0 && button.right <= width)
                                        if (index > 0) check(button.left >= buttons[index - 1].right)
                                        val icon = button.getChildAt(0) as ImageView
                                        val label = button.getChildAt(1) as TextView
                                        check(icon.imageTintList?.defaultColor == scope.colors.candidateTextColor)
                                        check(label.currentTextColor == scope.colors.candidateTextColor)
                                        check(icon.width > 0 && label.width > 0)
                                        if (index == 2) {
                                            check(label.text.toString() == "AI回复")
                                            check(label.layout.getEllipsisCount(0) == 0) { "AI reply label is clipped" }
                                        }
                                        contrast(label.currentTextColor, scope.colors.candidateBackground)
                                        button.performClick()
                                    }
                                    if (state == AlwaysUi.State.Toolbar && widthDp == 411) {
                                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                        try {
                                            ui.root.draw(Canvas(bitmap))
                                            File(context.filesDir, "dual-toolbar-$scheme-$fontScale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                    rows += "PASS: $scheme / font $fontScale / ${widthDp}dp / $state AI icons, labels and clicks"
                                }
                            }
                        }
                        check(clicks.toList() == listOf(12, 12, 12))
                    }
                    rows += "PASS: both palettes have readable AI buttons, disabled labels, editable text, hints and dialog reply rows"
                } finally {
                    preferences.followSystemDayNight.setValue(followSystem)
                    ColorManager.setColorScheme(original)
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        val result = Bundle()
        val error = failure
        if (error == null) {
            File(context.filesDir, "dual-palette-result.json").writeText(
                buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("api", android.os.Build.VERSION.SDK_INT)
                    put("network_requests", 0)
                    put("checks", JsonArray(rows.map(::JsonPrimitive)))
                }.toString(),
            )
            result.putString("stream", rows.joinToString("\n"))
        } else {
            result.putString("stream", error.stackTraceToString())
        }
        instrumentation.finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun contrast(foreground: Int, background: Int) {
        check(ColorUtils.calculateContrast(foreground, background) >= 4.5) { "Insufficient contrast ${foreground.toUInt().toString(16)} on ${background.toUInt().toString(16)}" }
    }

    private fun checkKeyboardContrast(scope: ThemeScope) {
        with(scope.colors) {
            for ((foreground, background) in listOf(
                keyTextColor to keyBackColor, keySymbolColor to keyBackColor,
                offKeyTextColor to offKeyBackColor, onKeyTextColor to onKeyBackColor,
                hilitedKeyTextColor to hilitedKeyBackColor, hilitedKeySymbolColor to hilitedKeyBackColor,
                candidateTextColor to candidateBackground, commentTextColor to candidateBackground,
                hilitedCandidateTextColor to hilitedCandidateBackColor, hilitedCommentTextColor to hilitedCandidateBackColor,
                scope.color("preview_text_color") to scope.color("preview_back_color"), textColor to textBackColor, longTextColor to longTextBackColor,
            )) {
                contrast(foreground, background)
            }
        }
    }

    private fun checkAiControls(context: Context) {
        for (primary in listOf(false, true)) {
            val button = Button(context).apply { AiUiStyle.button(this, primary) }
            val background = ((button.background as RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor
            contrast(button.currentTextColor, background)
            button.isEnabled = false
            contrast(button.currentTextColor, background)
        }
        val input = EditText(context).apply { AiUiStyle.input(this) }
        contrast(input.currentTextColor, AiUiStyle.surface)
        contrast(input.currentHintTextColor, AiUiStyle.surface)
        val dialogContext = AiUiStyle.dialog(context).context
        val row = ArrayAdapter(dialogContext, android.R.layout.simple_list_item_1, listOf("回复建议")).getView(0, null, LinearLayout(dialogContext)) as TextView
        contrast(row.currentTextColor, AiUiStyle.surface)
    }

    private fun checkMigration(context: Context, rows: MutableList<String>) {
        val shared = context.getSharedPreferences("dual-palette-migration-test", Context.MODE_PRIVATE)
        try {
            shared.edit().clear().commit()
            val fresh = ThemePrefs(shared)
            check(fresh.normalModeColor.getValue() == "default" && !fresh.followSystemDayNight.getValue())
            rows += "PASS: new settings default to light without automatic night following"
            fresh.normalModeColor.setValue("dark")
            check(ThemePrefs(shared).normalModeColor.getValue() == "dark")
            rows += "PASS: explicit dark choice survives preference reconstruction"
            shared.edit().clear().putString(ThemePrefs.SELECTED_THEME, "rime_ai.trime").putString(ThemePrefs.NORMAL_MODE_COLOR, "steam").putBoolean(ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT, true).commit()
            val legacy = ThemePrefs(shared)
            check(legacy.normalModeColor.getValue() == "default" && !legacy.followSystemDayNight.getValue())
            rows += "PASS: old built-in palette upgrades to light once"
            shared.edit().clear().putString(ThemePrefs.SELECTED_THEME, "external").putString(ThemePrefs.NORMAL_MODE_COLOR, "ink").putBoolean(ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT, true).commit()
            val external = ThemePrefs(shared)
            check(external.normalModeColor.getValue() == "ink" && external.followSystemDayNight.getValue())
            rows += "PASS: externally supplied themes retain their palette preferences"
        } finally {
            shared.edit().clear().commit()
        }
    }
}
