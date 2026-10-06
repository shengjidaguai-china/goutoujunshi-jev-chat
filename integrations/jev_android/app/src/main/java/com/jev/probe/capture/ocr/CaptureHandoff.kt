package com.jev.probe.capture.ocr

import android.graphics.Bitmap
import java.util.UUID

/** In-process handoff only. No images, grants, or callbacks survive a process restart. */
internal object CaptureHandoff {
    class Request(
        val isActive: () -> Boolean,
        val readiness: () -> CaptureReadiness,
        val setHidden: (Boolean) -> Unit,
        val onResult: (ScreenCapture.Result) -> Unit
    ) {
        val id = UUID.randomUUID().toString()
        var cleanup: (() -> Unit)? = null
    }

    private var pending: Request? = null

    fun register(request: Request): String {
        pending?.let { cancel(it.id) }
        pending = request
        return request.id
    }

    fun get(id: String): Request? = pending?.takeIf { it.id == id && it.isActive() }

    fun cancel(id: String) = complete(id, ScreenCapture.Result.Failed(-10, "本轮截屏已取消"))

    fun complete(id: String, result: ScreenCapture.Result) {
        val request = pending?.takeIf { it.id == id }
        if (request == null) {
            if (result is ScreenCapture.Result.Ok) result.bitmap.recycle()
            return
        }
        pending = null // Clear first: stopping a projection can call back synchronously.
        runCatching { request.cleanup?.invoke() }
        request.cleanup = null
        request.setHidden(false)
        request.onResult(result)
    }
}

internal fun isBlackFrame(bitmap: Bitmap): Boolean {
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { (it and 0x00ffffff) != 0 }) return false
    }
    return true
}

internal fun inspectCaptureFrame(bitmap: Bitmap): FrameCoverage {
    val coverage = FrameCoverage(bitmap.width, bitmap.height)
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        coverage.addRow(row, y)
    }
    return coverage
}
