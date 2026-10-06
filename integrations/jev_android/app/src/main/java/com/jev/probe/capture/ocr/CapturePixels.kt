package com.jev.probe.capture.ocr

import java.nio.ByteBuffer

/** ImageReader rows can include padding; never interpret it as image pixels. */
internal fun rgbaPixels(buffer: ByteBuffer, width: Int, height: Int,
                        rowStride: Int, pixelStride: Int): IntArray {
    require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000)
    require(pixelStride >= 4 && rowStride.toLong() >= width.toLong() * pixelStride)
    val bytes = buffer.duplicate()
    val start = bytes.position()
    val end = start.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
    require(end <= bytes.limit()) { "Incomplete screenshot plane" }
    return IntArray(width * height) { index ->
        val at = start + (index / width) * rowStride + (index % width) * pixelStride
        val red = bytes.get(at).toInt() and 255
        val green = bytes.get(at + 1).toInt() and 255
        val blue = bytes.get(at + 2).toInt() and 255
        val alpha = bytes.get(at + 3).toInt() and 255
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

internal enum class CaptureReadiness { WAIT, READY, CHANGED }

/** Permission windows may temporarily cover the original chat. */
internal fun captureReadiness(livePackage: String?, ownPackage: String,
                              expectedPackage: String, sameContent: Boolean): CaptureReadiness = when {
    livePackage == null || livePackage == ownPackage || livePackage == "com.android.systemui" -> CaptureReadiness.WAIT
    livePackage != expectedPackage || !sameContent -> CaptureReadiness.CHANGED
    else -> CaptureReadiness.READY
}

/** A permission window returning once is not enough: rotation can briefly cover it again. */
internal class CaptureFocusStability(private val settleMs: Long = 400) {
    private var readySince: Long? = null
    fun observe(state: CaptureReadiness, now: Long): CaptureReadiness {
        if (state != CaptureReadiness.READY) {
            readySince = null
            return state
        }
        val since = readySince ?: now.also { readySince = it }
        return if (now - since >= settleMs) CaptureReadiness.READY else CaptureReadiness.WAIT
    }
}

/** Reject incomplete screen mirrors with large all-black margins before OCR. */
internal class FrameCoverage(private val width: Int, private val height: Int) {
    private var left = width
    private var right = -1
    private var top = height
    private var bottom = -1
    fun addRow(row: IntArray, y: Int) {
        require(row.size >= width && y in 0 until height)
        for (x in 0 until width) if ((row[x] and 0x00ffffff) != 0) {
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
    }
    val isBlack get() = right < left
    val hasHeavyPadding get() = !isBlack &&
        ((right - left + 1) < width * 0.6f || (bottom - top + 1) < height * 0.6f)
}
