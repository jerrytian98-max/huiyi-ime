// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

private fun pcmSamples(vararg values: Int): ByteArray = values.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

class PcmAudioLevelTest :
    FunSpec({
        test("silence and empty input have no level") {
            PcmAudioLevel.measure(ByteArray(1024), 1024) shouldBe 0f
            PcmAudioLevel.measure(byteArrayOf(), 0) shouldBe 0f
            PcmAudioLevel.measure(byteArrayOf(127), 1) shouldBe 0f
        }

        test("signed samples have equal levels and saturate") {
            PcmAudioLevel.measure(pcmSamples(5000), 2) shouldBe (PcmAudioLevel.measure(pcmSamples(-5000), 2) plusOrMinus 0.001f)
            PcmAudioLevel.measure(pcmSamples(32767, -32768), 4) shouldBe 1f
        }

        test("speech level increases with volume and ignores unread bytes") {
            val quiet = PcmAudioLevel.measure(pcmSamples(100, -100), 4)
            val loud = PcmAudioLevel.measure(pcmSamples(5000, -5000), 4)
            (loud > quiet) shouldBe true
            PcmAudioLevel.measure(byteArrayOf(0, 0, 127, 127), 2) shouldBe 0f
        }
    })
