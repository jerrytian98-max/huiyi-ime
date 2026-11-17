/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment

class ClipboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.groupSettings(
            R.string.settings_clipboard_usage,
            AppPrefs.Clipboard.CLIPBOARD_LISTENING,
            AppPrefs.Clipboard.CLIPBOARD_LIMIT,
            AppPrefs.Clipboard.CLIPBOARD_SUGGESTION,
            AppPrefs.Clipboard.CLIPBOARD_SUGGESTION_TIMEOUT,
            AppPrefs.Clipboard.CLIPBOARD_RETURN_AFTER_PASTE,
        )
        screen.groupSettings(
            R.string.settings_clipboard_rules,
            AppPrefs.Clipboard.CLIPBOARD_COMPARE_RULES,
            AppPrefs.Clipboard.CLIPBOARD_OUTPUT_RULES,
        )
    }
}
