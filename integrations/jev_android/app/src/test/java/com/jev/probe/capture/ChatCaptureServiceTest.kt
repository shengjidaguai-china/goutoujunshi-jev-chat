package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCaptureServiceTest {
    @Test fun accessibilityEventsCannotReplacePendingReview() {
        assertTrue(shouldIgnoreAccessibilityEvents(reviewPending = true))
    }

    @Test fun accessibilityEventsResumeAfterReview() {
        assertFalse(shouldIgnoreAccessibilityEvents(reviewPending = false))
    }

    @Test fun confirmationWaitsForOverlayFocusToReturnToChat() {
        assertEquals(ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW,
            reviewConfirmationState("com.goutoujunshi.chat",
                "com.goutoujunshi.chat", "cn.soulapp.android"))
        assertEquals(ReviewConfirmationState.WAIT_FOR_CHAT_WINDOW,
            reviewConfirmationState(null,
                "com.goutoujunshi.chat", "cn.soulapp.android"))
    }

    @Test fun confirmationAcceptsOnlyTheOriginalChatApp() {
        assertEquals(ReviewConfirmationState.CHAT_CURRENT,
            reviewConfirmationState("cn.soulapp.android",
                "com.goutoujunshi.chat", "cn.soulapp.android"))
        assertEquals(ReviewConfirmationState.CHAT_CHANGED,
            reviewConfirmationState("com.tencent.mobileqq",
                "com.goutoujunshi.chat", "cn.soulapp.android"))
    }
}
