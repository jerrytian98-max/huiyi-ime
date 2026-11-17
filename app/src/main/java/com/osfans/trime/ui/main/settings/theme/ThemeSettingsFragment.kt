/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.ui.main.settings.groupSettings
import com.osfans.trime.util.startActivity
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(requireContext()).apply {
                key = "theme_diagnostics"
                setTitle(R.string.theme_diagnostics)
                setSummary(R.string.theme_diagnostics_summary)
                setOnPreferenceClickListener {
                    startActivity<ThemeDiagnosticsActivity>()
                    true
                }
            },
        )
        screen.groupSettings(
            R.string.settings_theme_appearance,
            ThemePrefs.SELECTED_THEME,
            ThemePrefs.NORMAL_MODE_COLOR,
            ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT,
        )
        screen.groupSettings(R.string.settings_theme_system, ThemePrefs.NAVBAR_BACKGROUND)
        screen.groupSettings(R.string.settings_theme_tools, "theme_diagnostics")
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
