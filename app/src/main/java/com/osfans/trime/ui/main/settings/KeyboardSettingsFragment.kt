/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import timber.log.Timber

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {
    private val viewModel: MainViewModel by activityViewModels()
    private val layoutHintKey = "keyboard_pinyin_layout_hint"

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<ListPreference>(AppPrefs.Keyboard.PINYIN_LAYOUT)?.apply {
            order = -2
            setOnPreferenceChangeListener { _, raw ->
                val selected = AppPrefs.Keyboard.PinyinLayout.valueOf(raw as String)
                val prefs = AppPrefs.defaultInstance().keyboard.pinyinLayout
                val previous = prefs.getValue()
                val ctx = requireContext()
                isEnabled = false
                lifecycleScope.launch {
                    try {
                        Timber.i("Selecting pinyin layout %s", selected.schemaId)
                        withLoadingDialog(ctx) {
                            viewModel.rime.runOnReady {
                                clearComposition()
                                // Recover if the app was closed during the first schema deployment.
                                if (!selectSchema(selected.schemaId)) deploy()
                                check(selectSchema(selected.schemaId)) { "Unable to select pinyin schema" }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.w(error, "Unable to select pinyin layout %s", selected.schemaId)
                        prefs.setValue(previous)
                        value = previous.name
                        ctx.toast(R.string.pinyin_layout_switch_failed)
                    } finally {
                        isEnabled = true
                    }
                }
                true
            }
        }
        screen.addPreference(
            Preference(requireContext()).apply {
                key = layoutHintKey
                order = -1
                isSelectable = false
                isIconSpaceReserved = false
                setSummary(R.string.pinyin_layout_hint)
            },
        )
        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext())
                        .show()
                }
                true
            }
        }
        screen.groupSettings(
            R.string.settings_keyboard_layout,
            AppPrefs.Keyboard.PINYIN_LAYOUT,
            layoutHintKey,
            AppPrefs.Keyboard.LANDSCAPE_MODE,
            AppPrefs.Keyboard.SPLIT_SPACE_PERCENT,
            AppPrefs.Keyboard.HIDE_INPUT_BAR,
            AppPrefs.Keyboard.HIDE_KEY_SYMBOL,
            AppPrefs.Keyboard.HIDE_KEY_HINT,
            AppPrefs.Keyboard.USE_SOFT_CURSOR,
        )
        screen.groupSettings(
            R.string.settings_keyboard_feedback,
            AppPrefs.Keyboard.SOUND_ON_KEYPRESS,
            AppPrefs.Keyboard.KEY_SOUND_VOLUME,
            AppPrefs.Keyboard.USE_CUSTOM_SOUND_EFFECT,
            AppPrefs.Keyboard.CUSTOM_SOUND_EFFECT,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_PRESS,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_RELEASE,
            AppPrefs.Keyboard.VIBRATE_ON_KEY_REPEAT,
            AppPrefs.Keyboard.VIBRATION_DURATION,
            AppPrefs.Keyboard.VIBRATION_AMPLITUDE,
            AppPrefs.Keyboard.SPEAK_ON_KEYPRESS,
            AppPrefs.Keyboard.SPEAK_ON_COMMIT,
            AppPrefs.Keyboard.POPUP_ON_KEY_PRESS,
        )
        screen.groupSettings(
            R.string.settings_keyboard_gestures,
            AppPrefs.Keyboard.EXPAND_KEYPRESS_AREA,
            AppPrefs.Keyboard.SWIPE_TRAVEL,
            AppPrefs.Keyboard.SWIPE_VELOCITY,
            AppPrefs.Keyboard.LONG_PRESS_TIMEOUT,
            AppPrefs.Keyboard.REPEAT_INTERVAL,
            AppPrefs.Keyboard.DOUBLE_TAP_TIMEOUT,
            AppPrefs.Keyboard.SLIDE_STEP_SIZE,
        )
        screen.groupSettings(
            R.string.settings_keyboard_candidate_bar,
            AppPrefs.Keyboard.HORIZONTAL_CANDIDATE_MODE,
            AppPrefs.Keyboard.MAX_SPAN_COUNT,
            AppPrefs.Keyboard.MAX_SPAN_COUNT_LANDSCAPE,
        )
        screen.groupSettings(
            R.string.settings_keyboard_shortcuts,
            AppPrefs.Keyboard.HOOK_CTRL_A,
            AppPrefs.Keyboard.HOOK_CTRL_CV,
            AppPrefs.Keyboard.HOOK_CTRL_LR,
            AppPrefs.Keyboard.HOOK_CTRL_ZY,
            AppPrefs.Keyboard.HOOK_SHIFT_SPACE,
            AppPrefs.Keyboard.HOOK_SHIFT_NUM,
            AppPrefs.Keyboard.HOOK_SHIFT_SYMBOL,
            AppPrefs.Keyboard.HOOK_SHIFT_ARROW,
        )
    }
}
