// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class ScreenshotOverlapTest :
    FunSpec({
        test("backward scrolling matches older bottom to later top without reversing chronology") {
            val random = Random(18)
            val width = 72
            val height = 200
            val content = IntArray(width * 320) {
                val v = random.nextInt(256)
                -0x1000000 or (v shl 16) or (v shl 8) or v
            }
            val older = content.copyOfRange(0, width * height)
            val later = content.copyOfRange(width * 120, width * 320)
            ScreenshotOverlap.find(older, later, width, height) shouldBe 80
        }
        test("blank or repeated patterns are not evidence for dropping source rows") {
            ScreenshotOverlap.find(IntArray(72 * 200) { -1 }, IntArray(72 * 200) { -1 }, 72, 200) shouldBe null
            val repeated = IntArray(72 * 200) { if ((it / 72) % 20 < 10) -1 else -0x1000000 }
            ScreenshotOverlap.find(repeated, repeated, 72, 200) shouldBe null
        }
        test("unrelated viewports retain both full screens") {
            val random = Random(28)
            fun image() = IntArray(72 * 200) { random.nextInt() or -0x1000000 }
            ScreenshotOverlap.find(image(), image(), 72, 200) shouldBe null
        }
    })
