// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.switches

import com.osfans.trime.core.RimeSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SwitchOptionEntryTest :
    FunSpec({
        test("keep Chinese English switching and hide uncommon Rime options") {
            val names =
                listOf(
                    "ascii_mode",
                    "ascii_punct",
                    "traditionalization",
                    "emoji",
                    "full_shape",
                    "search_single_char",
                )
            names.filter { SwitchOptionEntry.isVisibleSwitch(RimeSchema.Switch(name = it)) } shouldBe
                listOf("ascii_mode")
        }

        test("other schema options remain available") {
            SwitchOptionEntry.isVisibleSwitch(RimeSchema.Switch(name = "custom_option")) shouldBe true
        }
    })
