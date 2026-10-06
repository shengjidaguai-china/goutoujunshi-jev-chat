package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class GoutouContractsTest {
    @Test fun identicalMessagesInDifferentContactsHaveDifferentSignatures() {
        val messages = listOf(Msg("other", "好"))
        assertNotEquals(ChatSnapshot("甲", messages).signature(), ChatSnapshot("乙", messages).signature())
    }

    @Test fun signatureCannotConfuseTextWithMessageSeparators() {
        assertNotEquals(ChatSnapshot("甲", listOf(Msg("other", "a|other:b"))).signature(),
            ChatSnapshot("甲", listOf(Msg("other", "a"), Msg("other", "b"))).signature())
    }

    @Test fun ownMessageDoesNotRemoveStopContactBoundary() {
        val snapshot = ChatSnapshot("甲", listOf(Msg("other", "别再联系我"), Msg("me", "好")))
        assertTrue(GoutouGuidance.explicitBoundary(snapshot))
        assertTrue(GoutouGuidance.boundaryAnalysis().rankedReplies.isEmpty())
    }

    @Test fun otherPersonCanReopenTheConversation() {
        val snapshot = ChatSnapshot("甲", listOf(Msg("other", "别再联系我"), Msg("me", "好"),
            Msg("other", "我想清楚了，可以再聊聊吗")))
        assertFalse(GoutouGuidance.explicitBoundary(snapshot))
    }

    @Test fun allDeepSeekStrategiesHaveSpecificAdvice() {
        val fallback = GoutouGuidance.nextStep(null)
        for (strategy in listOf("承接", "降压", "调侃", "轻推", "约见", "澄清", "收线")) {
            assertEquals(strategy, GoutouGuidance.actionLabel(strategy))
            assertNotEquals(fallback, GoutouGuidance.nextStep(strategy))
        }
    }
}
