package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Details and explanations accept the same complete JSON fences as reply drafts. */
internal object ExplanationFormat {
    fun details(raw: String): String = formatted("详细分析") {
        val data = objectValue(raw)
        "对方可能的意图\n${text(data, "intent")}\n\n" +
            "先照顾好自己的感受\n${text(data, "support")}\n\n" +
            "已知事实\n${rows(data, "facts")}\n\n" +
            "合理推测\n${rows(data, "hypotheses")}\n\n" +
            "仍未知\n${rows(data, "unknowns")}\n\n" +
            "下一步\n${text(data, "next_step")}\n\n" +
            "停止条件\n${text(data, "stop_condition")}"
    }

    fun explain(raw: String, candidate: String): String = formatted("回复理由与代价") {
        val data = objectValue(raw)
        "候选回复\n$candidate\n\n理由\n${text(data, "reason").take(400)}\n\n" +
            "代价\n${text(data, "tradeoff").take(400)}"
    }

    private fun objectValue(raw: String): JSONObject =
        ModelJson.decode(raw) as? JSONObject ?: throw IllegalArgumentException()

    private fun text(data: JSONObject, key: String): String =
        (data.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException()

    private fun rows(data: JSONObject, key: String): String {
        val values = data.opt(key) as? JSONArray ?: throw IllegalArgumentException()
        require(values.length() <= 20)
        val strings = (0 until values.length()).map { index ->
            (values.get(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException()
        }
        return strings.take(5).joinToString("\n") { "• " + it.take(200) }.ifBlank { "暂无补充" }
    }

    private fun formatted(label: String, block: () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw IllegalArgumentException("${label}格式不正确，请重试")
    }

    /** Provider response bodies may contain private text; show status and an action only. */
    fun failureMessage(label: String, error: Exception): String {
        if (error is ApiException) {
            val advice = when (error.status) {
                401 -> "请检查回复接口的 API 密钥"
                402 -> "请检查回复接口账户余额"
                403 -> "回复接口拒绝访问，请检查权限和模型"
                404 -> "请检查回复接口地址和模型名称"
                429, 529 -> "服务繁忙，请稍后重试"
                null -> "连接失败或超时，请检查网络后重试"
                else -> "请检查回复接口配置或稍后重试"
            }
            val status = error.status?.let { " HTTP $it" } ?: ""
            return "$label 暂不可用：${error.route}$status；$advice。"
        }
        if (error is IllegalArgumentException && error.message in setOf(
                "详细分析格式不正确，请重试", "回复理由与代价格式不正确，请重试",
                "回复模型没有返回结果，请重试", "回复输出不完整，请重试", "回复模型返回空内容，请重试"))
            return error.message!!
        return "$label 暂不可用，请检查回复接口配置后重试。"
    }
}
