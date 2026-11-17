// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity

/** Return to the app underneath the result task; never relaunch a chat app at its home page. */
internal fun Activity.returnToInputApp() {
    moveTaskToBack(true)
    finish()
}
