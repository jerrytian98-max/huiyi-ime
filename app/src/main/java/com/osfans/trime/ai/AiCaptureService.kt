// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.osfans.trime.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AiCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captured = false
    private var requestId = ""
    private var completed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            AiEvents.cancelCapture(requestId)
            completed = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (requestId.isNotEmpty()) return START_NOT_STICKY
        requestId = intent?.getStringExtra(AiCaptureActivity.EXTRA_REQUEST_ID).orEmpty()
        createChannel()
        val working = notification("正在截取屏幕并生成回复建议")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, working, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, working)
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, ACTIVITY_RESULT_CANCELLED) ?: ACTIVITY_RESULT_CANCELLED

        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            fail("没有取得截屏授权")
            return START_NOT_STICKY
        }
        try {
            check(AiEvents.captureState.requestId == requestId && AiEvents.isCaptureInProgress) { "截屏请求已结束，请重新点击AI回复" }
            AiEvents.updateCapture(requestId, CaptureStatus.CAPTURING, "正在截取屏幕")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (!captured && !completed) fail("系统已停止截屏，请重新授权")
                    }
                },
                handler,
            )
            // Let the consent Activity leave the screen before the first frame is read.
            handler.postDelayed({ beginCapture() }, 650)
        } catch (e: Exception) {
            fail("截屏启动失败：${e.message ?: "未知错误"}")
        }
        return START_NOT_STICKY
    }

    private fun beginCapture() {
        val activeProjection = projection ?: return fail("截屏会话已结束")
        try {
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            } else {
                null
            }
            val width = bounds?.width() ?: resources.displayMetrics.widthPixels
            val height = bounds?.height() ?: resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.densityDpi
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source ->
                if (captured) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                captured = true
                scope.launch {
                    try {
                        val jpeg = withContext(Dispatchers.Default) { imageToJpeg(image) }
                        releaseCapture()
                        val analysis = AiEvents.beginAnalysis(requestId, jpeg)
                        if (analysis != null && AiCaptureReplies.generate(AiConfigStore.load(this@AiCaptureService), analysis)) {
                            showReady(AiEvents.suggestions.size)
                        } else if (AiEvents.captureState.requestId == requestId && AiEvents.captureState.status == CaptureStatus.ERROR) {
                            fail(AiEvents.captureState.message)
                        } else {
                            completed = true
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } catch (e: CancellationException) {
                        completed = true
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        throw e
                    } catch (e: Exception) {
                        releaseCapture()
                        fail("AI回复分析失败：${e.message ?: "未知错误"}")
                    }
                }
            }, handler)
            display = activeProjection.createVirtualDisplay(
                "Huiyi one-shot capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler,
            )
            handler.postDelayed({ if (!captured) fail("截屏超时，请重试") }, 5_000)
        } catch (e: Exception) {
            fail("截屏失败：${e.message ?: "未知错误"}")
        }
    }

    private fun imageToJpeg(image: Image): ByteArray {
        try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val raw = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            val maxDimension = maxOf(cropped.width, cropped.height)
            val finalBitmap = if (maxDimension > 2400) {
                val ratio = 2400f / maxDimension
                Bitmap.createScaledBitmap(cropped, (cropped.width * ratio).toInt(), (cropped.height * ratio).toInt(), true)
            } else {
                cropped
            }
            val output = ByteArrayOutputStream()
            check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "图片编码失败" }
            if (finalBitmap !== cropped) finalBitmap.recycle()
            cropped.recycle()
            if (raw !== cropped) raw.recycle()
            return output.toByteArray()
        } finally {
            image.close()
        }
    }

    private fun showReady(count: Int) {
        completed = true
        if (!AiConfigStore.showReplyOptions(this) && AiEvents.captureState.requestId != requestId) {
            dismissReadyNotification(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val title = if (count > 0) "回复建议已就绪，点击查看" else "没有识别出可回复的对话"
        manager.notify(NOTIFICATION_ID, notification(title, openResults = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun fail(message: String) {
        if (completed) return
        completed = true
        AiEvents.failCapture(requestId, message)
        releaseCapture()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, openResults = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun releaseCapture() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        val old = projection
        projection = null
        old?.stop()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.ai_capture_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notification(text: String, openResults: Boolean = false): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(R.mipmap.ic_app_icon)
            .setContentTitle(getText(R.string.trime_app_name))
            .setContentText(text)
            .setOngoing(!openResults)
        if (openResults) {
            val intent = Intent(this, AiReplyActivity::class.java)
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending).setAutoCancel(true)
        } else {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, AiCaptureService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "取消", cancel).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        if (!completed) AiEvents.failCapture(requestId, "AI回复服务已结束，请重新点击AI回复")
        handler.removeCallbacksAndMessages(null)
        releaseCapture()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "ai_capture_result_code"
        const val EXTRA_RESULT_DATA = "ai_capture_result_data"
        private const val CHANNEL_ID = "ai_capture"
        private const val NOTIFICATION_ID = 7302
        private const val ACTIVITY_RESULT_CANCELLED = 0
        private const val ACTION_CANCEL = "com.rimeai.keyboard.CANCEL_CAPTURE"

        fun dismissReadyNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
