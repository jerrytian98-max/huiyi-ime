// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import kotlin.math.PI
import kotlin.math.sin

/** Opaque drawing layer only: the original Space key keeps ownership of MOVE/UP. */
internal class HeldVoiceWaveformView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_ai_mic)?.mutate()
    private var targetLevel = 0f
    private var displayedLevel = 0f
    private var lastFrame = 0L
    private var startedAt = 0L
    private var seconds = 0
    private var limitReached = false

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun showRecording(show: Boolean, atLimit: Boolean = false) {
        if (!show) {
            visibility = GONE
            targetLevel = 0f
            displayedLevel = 0f
            lastFrame = 0L
            return
        }
        if (visibility != VISIBLE) {
            startedAt = SystemClock.uptimeMillis()
            lastFrame = 0L
            seconds = 0
        }
        limitReached = atLimit
        if (atLimit) {
            targetLevel = 0f
            displayedLevel = 0f
        }
        contentDescription = if (atLimit) "录音已达60秒，松开空格开始转写" else "正在语音输入，松开空格转写，上滑取消"
        visibility = VISIBLE
        invalidate()
    }

    fun update(seconds: Int? = null, level: Float? = null) {
        seconds?.let { this.seconds = it }
        level?.let { targetLevel = if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(AiUiStyle.background)
        val now = SystemClock.uptimeMillis()
        val delta = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1, 64)
        lastFrame = now
        val response = if (targetLevel > displayedLevel) 85f else 220f
        displayedLevel += (targetLevel - displayedLevel) * (delta / response).coerceAtMost(1f)
        val phase = (now - startedAt) / 1000f
        val cx = width / 2f
        val cy = height * 0.48f
        val spacing = minOf(11 * density, width / 32f)
        val barWidth = minOf(4 * density, spacing * 0.42f)
        paint.color = AiUiStyle.accent
        for (i in 0 until 25) {
            val position = i - 12
            val envelope = (sin(PI * (i + 1) / 26) * 0.7 + 0.3).toFloat()
            val rhythm = (0.55 + 0.45 * sin(phase * 7 + i * 0.72)).toFloat()
            val half = 2.5f * density + height * 0.16f * displayedLevel * envelope * rhythm
            val x = cx + position * spacing
            canvas.drawRoundRect(x - barWidth / 2, cy - half, x + barWidth / 2, cy + half, barWidth, barWidth, paint)
        }
        drawText(canvas, if (limitReached) "录音已完成" else "正在聆听", cx, height * 0.19f, 19f, AiUiStyle.text, true)
        drawText(canvas, "%02d:%02d".format(seconds / 60, seconds % 60), cx, height * 0.71f, 14f, AiUiStyle.secondary)
        drawText(canvas, if (limitReached) "已达 60 秒，松开空格开始转写" else "松开空格转写 · 上滑取消", cx, height * 0.88f, 14f, AiUiStyle.secondary)
        val size = (19 * density).toInt()
        mic?.apply {
            setTint(AiUiStyle.accent)
            val x = (cx - 50 * density).toInt()
            val y = (height * 0.19f - size / 2f).toInt()
            setBounds(x - size, y, x, y + size)
            draw(canvas)
        }
        if (!limitReached && isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, medium: Boolean = false) {
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * resources.displayMetrics.scaledDensity
        paint.typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        canvas.drawText(text, x, y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
    }

    override fun onDetachedFromWindow() {
        showRecording(false)
        super.onDetachedFromWindow()
    }
}
