package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureSessionTest {
    @Test fun hidingReviewAllowsAnotherReviewWithoutRestart() {
        val session = CaptureSession().apply { setEnabled(true) }
        val hidden = session.beginReview()!!
        session.reset()
        assertFalse(session.reviewPending)
        assertFalse(session.confirmReview(hidden))
        assertNotNull(session.beginReview())
    }

    @Test fun disablingDuringFocusRetryInvalidatesTheConfirmation() {
        val session = CaptureSession().apply { setEnabled(true) }
        val waiting = session.beginReview()!!
        session.setEnabled(false)
        assertFalse(session.confirmReview(waiting))
        assertFalse(session.beginAnalysis(waiting))
        assertNull(session.beginReview())
        session.setEnabled(true)
        assertNotNull(session.beginReview())
    }

    @Test fun finishingOldAnalysisCannotClearNewAnalysis() {
        val session = CaptureSession().apply { setEnabled(true) }
        val old = session.beginReview()!!
        assertTrue(session.confirmReview(old))
        assertTrue(session.beginAnalysis(old))
        session.reset()
        val current = session.beginReview()!!
        assertTrue(session.confirmReview(current))
        assertTrue(session.beginAnalysis(current))
        assertFalse(session.finishAnalysis(old))
        assertTrue(session.analyzing)
        assertTrue(session.finishAnalysis(current))
    }

    @Test fun disablingIsEffectiveBeforePreferenceCallbackRuns() {
        var preference = true
        val session = CaptureSession { preference }.apply { setEnabled(true) }
        val waiting = session.beginReview()!!
        preference = false
        assertFalse(session.isCurrent(waiting))
        assertFalse(session.confirmReview(waiting))
    }

    @Test fun destroyedServiceCannotStartAnotherRound() {
        val session = CaptureSession().apply { setEnabled(true) }
        session.destroy()
        session.setEnabled(true)
        assertNull(session.beginReview())
    }

    @Test fun automaticOcrRespectsItsSwitchAndIncomingSide() {
        assertFalse(shouldReviewOcr(false, false, "other"))
        assertFalse(shouldReviewOcr(false, true, "me"))
        assertTrue(shouldReviewOcr(false, true, "other"))
        assertTrue(shouldReviewOcr(true, false, "me"))
    }
}
