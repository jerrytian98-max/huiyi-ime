// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAudioTest :
    FunSpec({
        test("WAV has correct PCM format, length and unchanged sample bytes") {
            val pcm = ByteArray(32000) { if (it % 2 == 0) 0 else 16 }
            val wav = WavAudio.encode(pcm)
            wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) shouldBe "RIFF"
            val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            header.getInt(4) shouldBe wav.size - 8
            header.getInt(24) shouldBe 16000
            header.getShort(22).toInt() shouldBe 1
            header.getShort(34).toInt() shouldBe 16
            header.getInt(40) shouldBe pcm.size
            wav.copyOfRange(44, wav.size).contentEquals(pcm) shouldBe true
        }
        test("silence, undersized, unaligned and unbounded audio is refused") {
            for (pcm in listOf(ByteArray(32000), ByteArray(100), ByteArray(16001), ByteArray(WavAudio.MAX_PCM_BYTES + 2))) {
                shouldThrow<IllegalArgumentException> { WavAudio.encode(pcm) }
            }
        }
    })
