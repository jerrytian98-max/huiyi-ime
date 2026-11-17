// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HuiyiPaletteTest :
    FunSpec({
        for (file in listOf("rime_ai.trime.yaml", "trime.yaml", "tongwenfeng.trime.yaml")) {
            test("$file offers light and dark with light as the explicit default") {
                val theme = ThemeTestSupport.decodeBuiltinTheme(file)
                val schemes = theme.presetColorSchemes
                schemes.keys shouldBe setOf("default", "dark")
                schemes.getValue("default")["name"] shouldBe "浅色"
                schemes.getValue("dark")["name"] shouldBe "深色"
                theme.presetKeyboards.values.flatMap { it.keys }.filter { it.click == com.osfans.trime.data.theme.model.KeyActionToken.Plain("Return") }.forEach {
                    (it.keyBackColor in setOf("ai_action_back_color", "benter")) shouldBe true
                }
                for (night in listOf(false, true)) {
                    ColorSchemeResolver.resolve(schemes, "default", false, night) shouldBe schemes.getValue("default")
                    ColorSchemeResolver.resolve(schemes, "dark", false, night) shouldBe schemes.getValue("dark")
                    ColorSchemeResolver.resolve(schemes, "old-color", false, night) shouldBe schemes.getValue("default")
                    ColorSchemeResolver.resolve(schemes, "default", true, night) shouldBe schemes.getValue(if (night) "dark" else "default")
                }
            }
        }
    })
