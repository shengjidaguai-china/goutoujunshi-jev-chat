package com.jev.probe.capture.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class CapturePixelsTest {
    @Test fun ignoresRowPaddingAndPreservesRgbaChannels() {
        val bytes = ByteBuffer.wrap(byteArrayOf(
            99, 99, // non-zero buffer position
            -1, 0, 0, -1, 0, -1, 0, -1, 88, 88, 88, 88,
            0, 0, -1, -1, -1, -1, -1, -1))
        bytes.position(2)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(),
            0xff0000ff.toInt(), 0xffffffff.toInt()), rgbaPixels(bytes, 2, 2, 12, 4))
        assertEquals(2, bytes.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedImagePlane() { rgbaPixels(ByteBuffer.allocate(7), 2, 1, 8, 4) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingRows() { rgbaPixels(ByteBuffer.allocate(16), 2, 2, 4, 4) }

    @Test fun permissionWindowsWaitAndSwitchedChatsInvalidateTheCapture() {
        assertEquals(CaptureReadiness.WAIT,
            captureReadiness("com.goutoujunshi.chat", "com.goutoujunshi.chat", "com.jev.fixture", false))
        assertEquals(CaptureReadiness.WAIT,
            captureReadiness("com.android.systemui", "com.goutoujunshi.chat", "com.jev.fixture", false))
        assertEquals(CaptureReadiness.READY,
            captureReadiness("com.jev.fixture", "com.goutoujunshi.chat", "com.jev.fixture", true))
        assertEquals(CaptureReadiness.CHANGED,
            captureReadiness("com.jev.fixture", "com.goutoujunshi.chat", "com.jev.fixture", false))
        assertEquals(CaptureReadiness.CHANGED,
            captureReadiness("com.example.other", "com.goutoujunshi.chat", "com.jev.fixture", true))
    }

    @Test fun transientPermissionFocusRestartsTheSettlingPeriodButChatChangesStillFail() {
        val focus = CaptureFocusStability()
        assertEquals(CaptureReadiness.WAIT, focus.observe(CaptureReadiness.READY, 0))
        assertEquals(CaptureReadiness.WAIT, focus.observe(CaptureReadiness.READY, 300))
        assertEquals(CaptureReadiness.WAIT, focus.observe(CaptureReadiness.WAIT, 350))
        assertEquals(CaptureReadiness.WAIT, focus.observe(CaptureReadiness.READY, 500))
        assertEquals(CaptureReadiness.WAIT, focus.observe(CaptureReadiness.READY, 899))
        assertEquals(CaptureReadiness.READY, focus.observe(CaptureReadiness.READY, 900))
        assertEquals(CaptureReadiness.CHANGED, focus.observe(CaptureReadiness.CHANGED, 950))
    }

    @org.junit.Test fun incompleteMirrorIsRejectedButDarkScreenWithEdgeContentIsAccepted() {
        val incomplete = FrameCoverage(100, 100)
        for (y in 0 until 100) incomplete.addRow(IntArray(100) { x ->
            if (x in 35..64 && y in 0..55) 0xffeeeeee.toInt() else 0xff000000.toInt()
        }, y)
        org.junit.Assert.assertFalse(incomplete.isBlack)
        org.junit.Assert.assertTrue(incomplete.hasHeavyPadding)
        val complete = FrameCoverage(100, 100)
        for (y in 0 until 100) complete.addRow(IntArray(100) { x ->
            if ((x == 5 || x == 94) && (y == 5 || y == 94)) -1 else 0xff000000.toInt()
        }, y)
        org.junit.Assert.assertFalse(complete.hasHeavyPadding)
        val black = FrameCoverage(100, 100)
        for (y in 0 until 100) black.addRow(IntArray(100) { 0xff000000.toInt() }, y)
        org.junit.Assert.assertTrue(black.isBlack)
    }
}
