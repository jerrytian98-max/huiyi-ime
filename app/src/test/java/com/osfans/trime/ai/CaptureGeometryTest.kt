// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CaptureGeometryTest :
    FunSpec({
        val full = CaptureGeometry.Bounds(0, 0, 1080, 2400)
        fun plan(window: CaptureGeometry.Bounds? = full, editor: CaptureGeometry.Bounds? = null, keyboard: Int = 1529) = CaptureGeometry.plan(1080, 2400, keyboard, 128, window, editor, 126)
        test("real bottom composer is excluded") {
            plan(editor = CaptureGeometry.Bounds(20, 1379, 1060, 1529)) shouldBe CaptureGeometry.Bounds(0, 128, 1080, 1379)
        }
        test("stale editor rectangle near screen top does not shrink chat to a toolbar") {
            plan(editor = CaptureGeometry.Bounds(0, 350, 1080, 420)) shouldBe CaptureGeometry.Bounds(0, 128, 1080, 1403)
        }
        test("virtual editor exposing whole window is ignored") {
            plan(editor = full) shouldBe plan()
        }
        test("missing window geometry uses actual screenshot size") {
            plan(window = CaptureGeometry.Bounds(0, 0, 0, 0)) shouldBe plan()
        }
        test("cropping stays inside physical screenshot and live keyboard boundary") {
            plan(window = CaptureGeometry.Bounds(-100, -100, 2000, 4000), keyboard = 1400) shouldBe CaptureGeometry.Bounds(0, 128, 1080, 1274)
        }
        test("floating app window remains bounded to its actual surface") {
            plan(window = CaptureGeometry.Bounds(100, 250, 900, 1300)) shouldBe CaptureGeometry.Bounds(100, 250, 900, 1174)
        }
        test("window fully hidden behind keyboard does not produce a blind swipe") {
            shouldThrow<IllegalStateException> { plan(window = CaptureGeometry.Bounds(0, 1700, 1080, 2200)) }
        }
    })
