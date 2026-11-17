// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import kotlin.math.abs
import kotlin.math.sqrt

/** Matches the bottom of an earlier viewport to the top of a later viewport. */
internal object ScreenshotOverlap {
    fun find(earlier: IntArray, later: IntArray, width: Int, height: Int): Int? {
        require(width > 0 && height > 0 && earlier.size == width * height && later.size == earlier.size)
        var bestRows = 0
        var bestError = Double.MAX_VALUE
        val scores = mutableListOf<Pair<Int, Double>>()
        for (overlap in maxOf(24, height / 12) until height) {
            var error = 0.0
            var sum = 0.0
            var square = 0.0
            val rows = minOf(48, overlap)
            val columns = minOf(32, width)
            val count = rows * columns
            for (y in 0 until rows) {
                val row = y * (overlap - 1) / maxOf(1, rows - 1)
                for (x in 0 until columns) {
                    val col = x * (width - 1) / maxOf(1, columns - 1)
                    val a = luma(earlier[(height - overlap + row) * width + col])
                    val b = luma(later[row * width + col])
                    error += abs(a - b)
                    sum += a
                    square += a * a
                }
            }
            val meanError = error / count
            val contrast = sqrt((square / count - (sum / count) * (sum / count)).coerceAtLeast(0.0))
            if (contrast < 12 || meanError > 4) continue
            scores += overlap to meanError
            if (meanError < bestError) {
                bestError = meanError
                bestRows = overlap
            }
        }
        if (bestRows == 0) return null
        // Repeated identical bubbles or blank rows are not evidence of a unique overlap.
        if (scores.any { abs(it.first - bestRows) > 3 && it.second <= bestError + 0.5 }) return null
        return bestRows
    }

    private fun luma(color: Int): Int = ((color shr 16 and 255) * 77 + (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8
}
