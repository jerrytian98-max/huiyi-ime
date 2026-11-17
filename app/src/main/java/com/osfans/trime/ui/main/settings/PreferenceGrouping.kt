// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.settings

import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen

/** Reuses the existing preferences and their listeners while giving the page a readable order. */
internal fun PreferenceScreen.groupSettings(@StringRes title: Int, vararg keys: String) {
    val category = PreferenceCategory(context).apply {
        setTitle(title)
        isIconSpaceReserved = false
    }
    addPreference(category)
    keys.forEachIndexed { index, key ->
        findPreference<Preference>(key)?.let { preference ->
            removePreference(preference)
            preference.order = index
            preference.isIconSpaceReserved = false
            category.addPreference(preference)
        }
    }
}
