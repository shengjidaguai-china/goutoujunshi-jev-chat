package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject

internal object StrategyEvidence {
    val strategies = listOf("承接", "降压", "调侃", "轻推", "约见", "澄清", "收线")

    fun parse(raw: String): JSONObject? = try {
        val data = ModelJson.decode(raw) as? JSONObject
            ?: throw IllegalArgumentException("策略证据必须是对象")
        require(data.opt("strategy") is String && data.getString("strategy") in strategies)
        require(data.opt("intent") is String && data.getString("intent").isNotBlank())
        for (key in listOf("facts", "unknowns")) {
            val rows = data.opt(key) as? JSONArray ?: throw IllegalArgumentException("证据列表格式错误")
            require(rows.length() <= 20)
            for (index in 0 until rows.length()) require(rows.get(index) is String)
        }
        require(data.has("confidence"))
        if (!data.isNull("confidence")) {
            val confidence = data.get("confidence") as? Number
                ?: throw IllegalArgumentException("判断把握必须是数字或 null")
            require(confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0)
        }
        data
    } catch (_: Exception) { null }
}
