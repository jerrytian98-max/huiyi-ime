// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-triggered screenshots, bounded downward swipes and source-editor focus; never reads node text. */
@RequiresApi(Build.VERSION_CODES.R)
class AiAccessibilityCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var activeRequestId = ""
    private var longRelease: CompletableDeferred<Unit>? = null

    private fun captureLong(requestId: String, expectedPackage: String, initialKeyboardTop: Int, keyboardTop: () -> Int?, status: (String?) -> Unit) {
        activeRequestId = requestId
        val released = CompletableDeferred<Unit>()
        longRelease = released
        captureJob = scope.launch {
            val stitcher = LongScreenshotStitcher()
            try {
                AiEvents.updateCapture(requestId, CaptureStatus.CAPTURING, "按住时尝试滚动截长图；当前页面不支持时松手后继续")
                status("准备中")
                ensureCaptureOwner(expectedPackage)
                check(serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0) {
                    "请在系统无障碍设置中关闭后重新开启会意快捷截屏，以启用模拟下拉"
                }
                var establishedRegion: Rect? = null
                var screenSize: Pair<Int, Int>? = null
                suspend fun captureFrame(): Boolean {
                    ensureCaptureOwner(expectedPackage)
                    val screenshot = withTimeout(8_000) { takeOneScreenshot() }
                    val region = try {
                        val size = screenshot.width to screenshot.height
                        check(screenSize == null || screenSize == size) { "屏幕方向或尺寸已变化，请重新上拖AI回复" }
                        screenSize = size
                        establishedRegion ?: captureRegion(expectedPackage, keyboardTop() ?: initialKeyboardTop, screenshot.width, screenshot.height)
                    } catch (error: Throwable) {
                        screenshot.recycle()
                        throw error
                    }
                    val frame = try {
                        establishedRegion = region
                        withContext(Dispatchers.Default) { LongScreenshotStitcher.crop(screenshot, region) }
                    } finally {
                        screenshot.recycle()
                    }
                    val added = try {
                        withContext(Dispatchers.Default) { stitcher.addEarlier(frame) }
                    } catch (error: Throwable) {
                        frame.recycle()
                        throw error
                    }
                    return added
                }

                // Accessibility scroll actions do not inject a touch stream, so the
                // keyboard can still receive the user's UP while the list moves.
                var attemptedHeldScroll = false
                val holdDeadline = SystemClock.elapsedRealtime() + 12_000
                if (!released.isCompleted) {
                    captureFrame()
                    while (!released.isCompleted && stitcher.count < 5 && SystemClock.elapsedRealtime() < holdDeadline) {
                        val region = checkNotNull(establishedRegion)
                        if (!scrollBackward(expectedPackage, region)) break
                        attemptedHeldScroll = true
                        delay(550)
                        if (released.isCompleted) break
                        if (!captureFrame()) break
                        status("${stitcher.count}屏·松手生成")
                        AiEvents.updateCapture(requestId, CaptureStatus.CAPTURING, "按住滚动，已截${stitcher.count}屏；松手生成回复")
                    }
                }

                if (!released.isCompleted) status("松手")
                released.await()
                if (!attemptedHeldScroll) {
                    // dispatchGesture cancels the user's original touch. Use it only
                    // after UP when the chat view has no usable scroll action.
                    var interruptedSwipe = false
                    var stalledRetries = 0
                    val deadline = SystemClock.elapsedRealtime() + 12_000
                    if (stitcher.count > 0) {
                        ensureCaptureOwner(expectedPackage)
                        interruptedSwipe = !withTimeout(4000) { swipeDown(checkNotNull(establishedRegion)) }
                        delay(650)
                    }
                    while (true) {
                        val added = captureFrame()
                        status("${stitcher.count}屏")
                        if (!added) {
                            if (!interruptedSwipe || ++stalledRetries > 2) break
                        } else {
                            stalledRetries = 0
                        }
                        if (stitcher.count >= 5 || SystemClock.elapsedRealtime() >= deadline) break
                        ensureCaptureOwner(expectedPackage)
                        interruptedSwipe = !withTimeout(4000) { swipeDown(checkNotNull(establishedRegion)) }
                        delay(650) // Settle the app's scroll animation and respect screenshot rate limits.
                    }
                }
                ensureCaptureOwner(expectedPackage)
                status("拼接中")
                val jpeg = withContext(Dispatchers.Default) { stitcher.encode() }
                ensureActive()
                val analysis = AiEvents.beginAnalysis(requestId, jpeg) ?: return@launch
                status("识别中")
                AiCaptureReplies.generate(AiConfigStore.load(this@AiAccessibilityCaptureService), analysis)
            } catch (_: TimeoutCancellationException) {
                AiEvents.failCapture(requestId, "长截图超时，请短按AI回复或重新上拖重试")
            } catch (cancelled: CancellationException) {
                AiEvents.cancelCapture(requestId)
                throw cancelled
            } catch (error: Exception) {
                AiEvents.failCapture(requestId, error.message ?: "长截图失败，请短按AI回复截取当前屏幕")
            } finally {
                stitcher.close()
                if (activeRequestId == requestId) {
                    activeRequestId = ""
                    captureJob = null
                    longRelease = null
                }
                status(null)
            }
        }
    }

    /** Scrolls only an exposed chat list; this never replaces the held physical touch. */
    private fun scrollBackward(expectedPackage: String, region: Rect): Boolean {
        var best: AccessibilityNodeInfo? = null
        var bestAction = 0
        var bestArea = 0L
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() != expectedPackage) {
                root.recycle()
                continue
            }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                try {
                    val action = when {
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id } -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        else -> 0
                    }
                    if (action != 0) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val visible = Rect(bounds)
                        if (visible.intersect(region) && visible.width() >= region.width() / 2 && visible.height() >= region.height() / 4) {
                            val area = visible.width().toLong() * visible.height()
                            if (area > bestArea) {
                                best?.recycle()
                                best = node
                                bestAction = action
                                bestArea = area
                            }
                        }
                    }
                    if (++visited < 400) {
                        for (index in 0 until node.childCount) {
                            runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
                        }
                    }
                } finally {
                    if (node !== best) node.recycle()
                }
            }
        }
        return best?.let { node ->
            try {
                runCatching { node.performAction(bestAction) }.getOrDefault(false)
            } finally {
                node.recycle()
            }
        } ?: false
    }

    private suspend fun swipeDown(region: Rect): Boolean = suspendCancellableCoroutine { continuation ->
        val x = region.exactCenterX()
        val endY = region.top + region.height() * 0.84f
        val path = Path().apply {
            moveTo(x, region.top + region.height() * 0.32f)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 420, true)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Pause at the end before lifting, so a chat app does not fling past uncaptured rows.
                    // Also release the pointer if the capture job was cancelled during the moving stroke.
                    // A tiny drift emits final MOVE samples; a perfectly stationary path can
                    // leave ScrollView's velocity tracker using the fast moving stroke.
                    val hold = stroke.continueStroke(
                        Path().apply {
                            moveTo(x, endY)
                            lineTo(x, endY + resources.displayMetrics.density)
                        },
                        0,
                        180,
                        false,
                    )
                    val lifted = dispatchGesture(
                        GestureDescription.Builder().addStroke(hold).build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                        null,
                    )
                    if (!lifted && continuation.isActive) continuation.resume(false)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // A chat app may dismiss its IME as the finger moves. The partial
                    // scroll is still useful; capture it and retry if no rows moved.
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统拒绝模拟下拉，请重新开启快捷截屏授权"))
        // Only the finite 180ms pointer release may follow cancellation; no new scroll/capture starts.
    }

    private fun captureRegion(expectedPackage: String, keyboardTop: Int, width: Int, height: Int): Rect {
        // WindowManager's bounds describe the actual surface. A root accessibility node
        // can describe only an editor or an invisible wrapper on Samsung/custom views.
        var appWindow: AccessibilityWindowInfo? = null
        var root: AccessibilityNodeInfo? = null
        var largest = -1L
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val candidate = window.root ?: continue
            if (candidate.packageName?.toString() != expectedPackage) {
                candidate.recycle()
                continue
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            val visible = Rect(bounds)
            if (!visible.intersect(0, 0, width, minOf(height, keyboardTop))) visible.setEmpty()
            val area = visible.width().coerceAtLeast(0).toLong() * visible.height().coerceAtLeast(0)
            if (area > largest) {
                root?.recycle()
                root = candidate
                appWindow = window
                largest = area
            } else {
                candidate.recycle()
            }
        }
        val appRoot = root ?: error("无法确认当前聊天窗口，请重新开启快捷截屏授权")
        try {
            val windowBounds = Rect().also { appWindow!!.getBoundsInScreen(it) }
            val input = appRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editorBounds = try {
                if (input?.isEditable == true) Rect().also(input::getBoundsInScreen) else null
            } finally {
                input?.recycle()
            }
            val status = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            fun Rect.asBounds() = CaptureGeometry.Bounds(left, top, right, bottom)
            val planned = CaptureGeometry.plan(width, height, keyboardTop, status, windowBounds.asBounds(), editorBounds?.asBounds(), (48 * resources.displayMetrics.density).toInt())
            return Rect(planned.left, planned.top, planned.right, planned.bottom)
        } finally {
            appRoot.recycle()
        }
    }

    private fun ensureCaptureOwner(expectedPackage: String) {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁定，本次长截图已停止" }
        check(AiQuickCapture.isReady(this)) { "快捷截屏授权已关闭，本次图片未发送" }
        val activeApp = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
        val root = activeApp?.root ?: rootInActiveWindow
        try {
            check(root?.packageName?.toString() == expectedPackage) { "已离开原聊天应用，本次长截图已停止" }
        } finally {
            root?.recycle()
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private fun focusOriginalEditor(expectedPackage: String): Boolean {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) } ?: return false
        val root = window.root ?: return false
        try {
            if (root.packageName?.toString() != expectedPackage) return false
            val editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            try {
                if (!editor.isEditable || editor.packageName?.toString() != expectedPackage) return false
                return editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                editor.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() = cancelPending()

    private fun capture(requestId: String) {
        activeRequestId = requestId
        captureJob = scope.launch {
            try {
                check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机再使用AI回复" }
                check(AiQuickCapture.isReady(this@AiAccessibilityCaptureService)) { "快捷截屏授权已关闭，请重新点击AI回复" }
                AiEvents.updateCapture(requestId, CaptureStatus.CAPTURING, "正在快捷截取当前屏幕")
                val bitmap = withTimeout(8_000) { takeOneScreenshot() }
                val jpeg = try {
                    withContext(Dispatchers.Default) { encode(bitmap) }
                } finally {
                    bitmap.recycle()
                }
                ensureActive()
                check(AiQuickCapture.isReady(this@AiAccessibilityCaptureService)) { "快捷截屏授权已关闭，本次图片未发送" }
                val analysis = AiEvents.beginAnalysis(requestId, jpeg) ?: return@launch
                AiCaptureReplies.generate(AiConfigStore.load(this@AiAccessibilityCaptureService), analysis)
            } catch (_: TimeoutCancellationException) {
                AiEvents.failCapture(requestId, "快捷AI回复超时，请重试")
            } catch (error: CancellationException) {
                AiEvents.cancelCapture(requestId)
                throw error
            } catch (error: Exception) {
                AiEvents.failCapture(requestId, error.message ?: "快捷AI回复失败，请重试")
            } finally {
                if (activeRequestId == requestId) {
                    activeRequestId = ""
                    captureJob = null
                }
            }
        }
    }

    private suspend fun takeOneScreenshot(): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        if (!continuation.isActive) return
                        val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)) { "无法读取截屏图片" }
                        val bitmap = try {
                            checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false)) { "无法复制截屏图片" }
                        } finally {
                            wrapped.recycle()
                        }
                        continuation.resume(bitmap) { _, unused, _ -> unused.recycle() }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!continuation.isActive) return
                    val message = when {
                        errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "点击太快，请稍后再次AI回复"
                        errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "快捷截屏权限已失效，请在 AI 设置中重新授权"
                        Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面禁止截屏，请切换到允许截屏的聊天页面"
                        else -> "系统未能截取当前屏幕，请重试或在 AI 设置中关闭快捷模式"
                    }
                    continuation.resumeWithException(IllegalStateException(message))
                }
            },
        )
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxDimension > 2400) {
            val ratio = 2400f / maxDimension
            Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "图片编码失败" }
                output.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun cancelPending() {
        if (activeRequestId.isNotEmpty()) AiEvents.cancelCapture(activeRequestId)
        captureJob?.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        cancelPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        cancelPending()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private var instance: AiAccessibilityCaptureService? = null
        val isConnected: Boolean get() = instance != null

        /** Main thread only. False means the caller must use ordinary projection consent. */
        fun tryCapture(context: android.content.Context, requestId: String): Boolean {
            val service = instance ?: return false
            if (!AiQuickCapture.isReady(context)) return false
            if (AiEvents.beginCapture(requestId)) service.capture(requestId)
            return true
        }

        fun tryLongCapture(context: android.content.Context, requestId: String, packageName: String, keyboardTop: Int, keyboardTopProvider: () -> Int? = { keyboardTop }, status: (String?) -> Unit): Boolean {
            val service = instance ?: return false
            if (!AiQuickCapture.isReady(context) || !AiEvents.beginCapture(requestId)) return false
            service.captureLong(requestId, packageName, keyboardTop, keyboardTopProvider, status)
            return true
        }

        fun finishLongCapture(requestId: String) {
            instance?.takeIf { it.activeRequestId == requestId }?.longRelease?.complete(Unit)
        }

        fun cancelLongCapture(requestId: String) {
            instance?.takeIf { it.activeRequestId == requestId }?.cancelPending()
        }

        fun cancelCurrent() = instance?.cancelPending()

        fun ownsRequest(requestId: String): Boolean = instance?.activeRequestId == requestId

        /** Only reopens an already focused editable node in the original foreground app. */
        fun focusOriginalEditor(packageName: String): Boolean = instance?.focusOriginalEditor(packageName) == true
    }
}
