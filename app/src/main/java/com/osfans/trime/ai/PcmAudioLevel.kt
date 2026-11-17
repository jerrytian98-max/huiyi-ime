// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import kotlin.math.log10
import kotlin.math.sqrt

/** Normalizes signed little-endian PCM16 RMS to a useful speech meter range. */
internal object PcmAudioLevel {
    fun measure(pcm: ByteArray, count: Int): Float {
        val samples = minOf(count, pcm.size).coerceAtLeast(0) / 2
        if (samples == 0) return 0f
        var sum = 0.0
        repeat(samples) { index ->
            val offset = index * 2
            val sample = ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / samples)
        if (rms <= 0.001) return 0f
        return ((20 * log10(rms) + 60) / 52).toFloat().coerceIn(0f, 1f)
    }
}
