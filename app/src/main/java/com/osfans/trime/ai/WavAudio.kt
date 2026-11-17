// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Bounded, memory-only mono PCM16 WAV for the transcription API. */
internal object WavAudio {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 60
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS

    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size in SAMPLE_RATE..MAX_PCM_BYTES && pcm.size % 2 == 0) { "录音太短或过长，请录制0.5至60秒语音" }
        var audible = 0
        for (index in pcm.indices step 2) {
            val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
            if (abs(sample) > 256) audible++
        }
        require(audible > SAMPLE_RATE / 20) { "录音没有清晰的声音，请检查麦克风后重试" }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()
    }
}
