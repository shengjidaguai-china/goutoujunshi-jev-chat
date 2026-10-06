package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test

class ReplyFormatTest {
    @Test fun acceptsCompleteArrayAndCodeFence() {
        assertEquals(listOf("好，你先忙", "下周再说"), ReplyFormat.parse("[\"好，你先忙\",\"下周再说\"]"))
        assertEquals(listOf("收到"), ReplyFormat.parse("```json\n[\"收到\"]\n```"))
        assertEquals(emptyList<String>(), ReplyFormat.parse("[]"))
    }

    @Test fun rejectsModelErrorsAndJsonObjectsAsCandidates() {
        rejected("模型暂时不可用，请稍后再试")
        rejected("{\"reply\":\"好的\"}")
        rejected("以下是回复：[\"好的\"]")
        rejected("[\"好的\"] [\"忽略这一段\"]")
    }

    @Test fun rejectsTruncatedArraysAndWrongElementTypes() {
        rejected("[\"好的\"")
        rejected("[1]")
        rejected("[{\"text\":\"好的\"}]")
        rejected("[\" \" , null]")
    }

    @Test fun rejectsExcessCandidatesAndLongRepliesWithoutTruncating() {
        rejected("[\"一\",\"二\",\"三\",\"四\"]")
        rejected("[\"${"长".repeat(41)}\"]")
    }

    private fun rejected(raw: String) {
        try { ReplyFormat.parse(raw); fail("accepted invalid candidate: $raw") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("回复格式不正确")) }
    }
}
