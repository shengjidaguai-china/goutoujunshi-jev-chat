package com.jev.probe.jev

import org.json.JSONTokener

internal object ModelJson {
    fun decode(content: String): Any {
        val raw = content.trim().let {
            if (it.startsWith("```json\n") && it.endsWith("```"))
                it.removePrefix("```json\n").removeSuffix("```").trim()
            else if (it.startsWith("```\n") && it.endsWith("```"))
                it.removePrefix("```\n").removeSuffix("```").trim()
            else it
        }
        val reader = JSONTokener(raw)
        val value = reader.nextValue()
        require(reader.nextClean() == '\u0000') { "模型返回了 JSON 之外的内容" }
        return value
    }
}
