package com.jev.probe.capture.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.jev.probe.CaptureInputActivity
import com.jev.probe.R

/** One consent, one virtual display, one usable frame. Stops on every exit path. */
class ProjectionCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var requestId = ""
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var stopped = false
    private var realWidth = 1
    private var realHeight = 1
    private var captureWidth = 1
    private var captureHeight = 1

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopped) fail("系统截屏已停止，请重试或检查截屏方式")
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (stopped || display == null || width <= 0 || height <= 0) return
            configureReader(width, height)
            display?.resize(captureWidth, captureHeight, resources.configuration.densityDpi)
            display?.surface = reader?.surface
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (intent.getStringExtra(CaptureInputActivity.EXTRA_REQUEST) == requestId)
                fail("已取消系统截屏")
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(CaptureInputActivity.EXTRA_REQUEST) ?: ""
        val request = CaptureHandoff.get(id)
        @Suppress("DEPRECATION")
        val grant = intent?.getParcelableExtra<Intent>("grant")
        if (request == null || grant == null || projection != null) {
            stopSelf(startId); return START_NOT_STICKY
        }
        requestId = id
        request.cleanup = { release() }
        try {
            startCaptureNotification()
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("resultCode", -1), grant)
            projection!!.registerCallback(callback, main)
            val deadline = SystemClock.elapsedRealtime() + 5000
            waitForChat(deadline, CaptureFocusStability())
        } catch (e: Exception) {
            Log.w(TAG, "projection start failed: ${e.javaClass.simpleName}")
            fail("系统拒绝截屏，请重新确认授权或检查截屏方式")
        }
        return START_NOT_STICKY
    }

    private fun waitForChat(deadline: Long, focus: CaptureFocusStability) {
        val request = CaptureHandoff.get(requestId)
        if (request == null) { release(); return }
        when (focus.observe(request.readiness(), SystemClock.elapsedRealtime())) {
            CaptureReadiness.CHANGED -> fail("会话已变化，请回到目标聊天重新截屏")
            CaptureReadiness.WAIT -> if (SystemClock.elapsedRealtime() >= deadline) {
                fail("请返回目标聊天后重新截屏")
            } else main.postDelayed({ waitForChat(deadline, focus) }, 100)
            CaptureReadiness.READY -> {
                request.setHidden(true)
                main.postDelayed({ beginFrames() }, 220)
            }
        }
    }

    private fun beginFrames() {
        val request = CaptureHandoff.get(requestId)
        if (request == null) { release(); return }
        if (request.readiness() != CaptureReadiness.READY) {
            fail("聊天画面已变化，请重新截屏"); return
        }
        try {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            realWidth = bounds.width(); realHeight = bounds.height()
            configureReader(realWidth, realHeight)
            display = projection!!.createVirtualDisplay("GoutouSingleScreenshot", captureWidth,
                captureHeight, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
            main.postDelayed({ fail("系统截屏未拿到有效画面，请重试；模拟器可能不兼容截屏") }, 6000)
        } catch (e: Exception) {
            Log.w(TAG, "projection display failed: ${e.javaClass.simpleName}")
            fail("系统截屏失败，请重试或检查截屏方式")
        }
    }

    private fun configureReader(width: Int, height: Int) {
        val scale = (2560f / maxOf(width, height)).coerceAtMost(1f)
        captureWidth = (width * scale).toInt().coerceAtLeast(1)
        captureHeight = (height * scale).toInt().coerceAtLeast(1)
        val previous = reader
        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ source -> readFrame(source) }, main)
        }
        previous?.setOnImageAvailableListener(null, null)
        previous?.close()
    }

    private fun readFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        var bitmap: Bitmap? = null
        try {
            if (stopped) return
            val request = CaptureHandoff.get(requestId) ?: return
            if (request.readiness() != CaptureReadiness.READY) {
                fail("聊天画面已变化，请重新截屏"); return
            }
            val plane = image.planes.first()
            val pixels = rgbaPixels(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            val coverage = inspectCaptureFrame(bitmap)
            if (coverage.isBlack) { bitmap.recycle(); bitmap = null; return }
            if (coverage.hasHeavyPadding) {
                fail("系统投射画面不完整，可能是模拟器显示兼容问题。请重试或在实际设备上测试")
                return
            }
            Log.i(TAG, "projection screenshot ${bitmap.width}x${bitmap.height}")
            val result = ScreenCapture.Result.Ok(bitmap, bitmap.width / realWidth.toFloat(),
                bitmap.height / realHeight.toFloat())
            bitmap = null // Ownership moves to the OCR callback after the Image is closed.
            image.close()
            CaptureHandoff.complete(requestId, result)
            return
        } catch (e: Exception) {
            Log.w(TAG, "projection frame failed: ${e.javaClass.simpleName}")
            fail("系统图片读取失败，请重试或检查截屏方式")
        } finally {
            bitmap?.recycle()
            runCatching { image.close() }
        }
    }

    private fun startCaptureNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "单次系统截屏", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_CANCEL)
            .putExtra(CaptureInputActivity.EXTRA_REQUEST, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("狗头军师 · 单次截屏")
            .setContentText("读取一张有效画面后立即停止，不录制声音")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
        startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun fail(message: String) {
        if (!stopped) CaptureHandoff.complete(requestId, ScreenCapture.Result.Failed(-10, message))
        release()
    }

    private fun release() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { projection?.unregisterCallback(callback) }
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped && requestId.isNotEmpty())
            CaptureHandoff.complete(requestId, ScreenCapture.Result.Failed(-10, "系统截屏已结束，请重试"))
        release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val CHANNEL = "single_screenshot"
        private const val ACTION_CANCEL = "cancel_single_screenshot"
    }
}
