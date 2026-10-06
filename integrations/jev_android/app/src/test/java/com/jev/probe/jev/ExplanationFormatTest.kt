package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test

class ExplanationFormatTest {
    private val details = """{"intent":"可能想先缓一缓","support":"先不急着下结论","facts":["对方说忙"],"hypotheses":["可能没确定安排"],"unknowns":["下周有没有空"],"next_step":"先接住安排","stop_condition":"明确拒绝就停止追问"}"""

    @Test fun detailsAcceptsPlainJsonAndCompleteCodeFences() {
        val expected = ExplanationFormat.details(details)
        assertTrue(expected.contains("已知事实\n• 对方说忙"))
        assertTrue(expected.contains("合理推测\n• 可能没确定安排"))
        assertTrue(expected.contains("仍未知\n• 下周有没有空"))
        assertEquals(expected, ExplanationFormat.details("```json\n$details\n```"))
        assertEquals(expected, ExplanationFormat.details("```\n$details\n```"))
    }

    @Test fun explanationAcceptsFenceAndKeepsCandidateSeparate() {
        val raw = """{"reason":"给对方留点余地","tradeoff":"这轮可能不会展开"}"""
        assertEquals("候选回复\n好，你先忙\n\n理由\n给对方留点余地\n\n代价\n这轮可能不会展开",
            ExplanationFormat.explain("```json\n$raw\n```", "好，你先忙"))
    }

    @Test fun detailsRejectsMissingFieldsAndIncorrectTypes() {
        rejected { ExplanationFormat.details("{}") }
        rejected { ExplanationFormat.details(details.replace("[\"对方说忙\"]", "\"对方说忙\"")) }
        rejected { ExplanationFormat.details(details.replace("[\"对方说忙\"]", "[1]")) }
        rejected { ExplanationFormat.details(details.replace("\"可能想先缓一缓\"", "null")) }
    }

    @Test fun detailsRejectsTruncationProseAndTrailingOutput() {
        rejected { ExplanationFormat.details(details.dropLast(1)) }
        rejected { ExplanationFormat.details("分析如下：$details") }
        rejected { ExplanationFormat.details("$details {}") }
        rejected { ExplanationFormat.details("模型暂不可用") }
    }

    @Test fun emptyEvidenceListsAreShownWithoutInventedFacts() {
        val text = ExplanationFormat.details(details.replace("[\"对方说忙\"]", "[]"))
        assertTrue(text.contains("已知事实\n暂无补充"))
        assertFalse(text.contains("对方说忙"))
    }

    @Test fun explanationRejectsMissingAndNonStringFields() {
        rejected { ExplanationFormat.explain("""{"reason":"接话"}""", "好") }
        rejected { ExplanationFormat.explain("""{"reason":1,"tradeoff":"冷场"}""", "好") }
    }

    @Test fun providerErrorsShowStatusAndActionWithoutResponseBody() {
        val text = ExplanationFormat.failureMessage("详细分析", ApiException(Route.REPLY, 401, "private-provider-response"))
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("API 密钥"))
        assertFalse(text.contains("private-provider-response"))
        assertTrue(ExplanationFormat.failureMessage("详细分析", ApiException(Route.REPLY, null, "timeout"))
            .contains("连接失败或超时"))
    }

    @Test fun formatErrorsRemainActionableAndOtherErrorsDoNotLeakMessages() {
        assertEquals("详细分析格式不正确，请重试", ExplanationFormat.failureMessage("详细分析",
            IllegalArgumentException("详细分析格式不正确，请重试")))
        assertFalse(ExplanationFormat.failureMessage("详细分析", IllegalStateException("private-text"))
            .contains("private-text"))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("invalid explanation accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("格式不正确")) }
    }
}
