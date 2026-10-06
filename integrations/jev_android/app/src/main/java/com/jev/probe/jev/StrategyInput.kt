package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

internal object StrategyInput {
    fun build(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?, historyLimit: Int): JSONObject {
        val effectiveRelationship = ctx?.contact?.relationship?.takeIf { it.isNotBlank() } ?: relationship
        val result = JSONObject().put("relationship", effectiveRelationship)
            .put("transcript", JSONArray().also { array ->
                snapshot.messages.takeLast(30).forEach { message ->
                    array.put(JSONObject().put("speaker", message.side).put("text", message.text.take(500)))
                }
            })
        ctx?.background(effectiveRelationship)?.takeIf { it.isNotBlank() }?.let { result.put("background", it) }
        val history = ctx?.history?.takeLast(historyLimit.coerceIn(0, 100)).orEmpty()
        if (history.isNotEmpty()) result.put("history", JSONArray().also { array ->
            history.forEach { message ->
                array.put(JSONObject().put("speaker", message.side).put("text", message.text.take(500)))
            }
        })
        return result
    }
}
