package com.jev.probe.jev

import org.json.JSONArray

/** Only a complete JSON string array can become text that the user may fill. */
internal object ReplyFormat {
    fun parse(content: String): List<String> {
        try {
            val array = ModelJson.decode(content) as? JSONArray
                ?: throw IllegalArgumentException("候选必须是字符串数组")
            require(array.length() <= 3)
            return (0 until array.length()).map { index ->
                val value = array.get(index)
                require(value is String)
                value.trim().also {
                    require(it.isNotBlank() && it.codePointCount(0, it.length) <= 40)
                }
            }.distinct()
        } catch (e: Exception) {
            throw IllegalArgumentException("回复格式不正确；需要最多 3 条、每条不超过 40 字的 JSON 字符串数组，请重试", e)
        }
    }
}
