/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment

class GeneralSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().general) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.groupSettings(
            R.string.settings_general_text,
            AppPrefs.General.INLINE_PREEDIT_MODE,
            AppPrefs.General.ASCII_SWITCH_TIPS,
            AppPrefs.General.INLINE_SUGGESTIONS,
        )
        screen.groupSettings(R.string.settings_general_voice, AppPrefs.General.PREFERRED_VOICE_INPUT)
    }
}
