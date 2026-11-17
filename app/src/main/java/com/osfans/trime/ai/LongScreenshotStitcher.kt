// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/** Owns cropped frames in memory, oldest first; uncertain boundaries preserve both screens. */
internal class LongScreenshotStitcher : AutoCloseable {
    private val frames = mutableListOf<Bitmap>()
    private val overlaps = mutableListOf<Int?>()
    val count get() = frames.size

    fun addEarlier(frame: Bitmap): Boolean {
        if (frames.isEmpty()) {
            frames += frame
            return true
        }
        val current = frames.first()
        require(current.width == frame.width && current.height == frame.height) { "页面尺寸发生变化，请重新截取长图" }
        val first = pixels(frame)
        val last = pixels(current)
        if (first.contentEquals(last)) {
            frame.recycle()
            return false
        }
        val overlap = ScreenshotOverlap.find(first, last, frame.width, frame.height)
        frames.add(0, frame)
        overlaps.add(0, overlap)
        return true
    }

    fun encode(): ByteArray {
        check(frames.isNotEmpty()) { "尚未截取到聊天内容" }
        val divider = 32
        val height = frames.sumOf { it.height } - overlaps.sumOf { it ?: -divider }
        check(height <= 10000) { "长截图达到长度上限，请分段截取" }
        val combined = Bitmap.createBitmap(frames.first().width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(combined)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 18f
            }
            var top = 0
            frames.forEachIndexed { index, frame ->
                val overlap = if (index == 0) 0 else overlaps[index - 1] ?: 0
                if (index > 0 && overlaps[index - 1] == null) {
                    canvas.save()
                    canvas.clipRect(0, top, combined.width, top + divider)
                    canvas.drawColor(Color.rgb(235, 235, 235))
                    canvas.drawText("连续截屏 · 边界可能有重复", 16f, top + 23f, paint)
                    canvas.restore()
                    top += divider
                }
                val source = Rect(0, overlap, frame.width, frame.height)
                val destination = Rect(0, top, frame.width, top + source.height())
                canvas.drawBitmap(frame, source, destination, null)
                top += source.height()
            }
            return ByteArrayOutputStream().use { bytes ->
                check(combined.compress(Bitmap.CompressFormat.JPEG, 88, bytes)) { "长图编码失败" }
                check(bytes.size() <= 10 * 1024 * 1024) { "长图过大，请分段截取" }
                bytes.toByteArray()
            }
        } finally {
            combined.recycle()
        }
    }

    override fun close() {
        frames.forEach { it.recycle() }
        frames.clear()
        overlaps.clear()
    }

    companion object {
        fun crop(screen: Bitmap, region: Rect): Bitmap {
            val ratio = minOf(1f, 800f / region.width(), 1800f / region.height())
            val cropped = Bitmap.createBitmap(maxOf(1, (region.width() * ratio).toInt()), maxOf(1, (region.height() * ratio).toInt()), Bitmap.Config.ARGB_8888)
            Canvas(cropped).drawBitmap(screen, region, Rect(0, 0, cropped.width, cropped.height), Paint(Paint.FILTER_BITMAP_FLAG))
            return cropped
        }

        private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}
