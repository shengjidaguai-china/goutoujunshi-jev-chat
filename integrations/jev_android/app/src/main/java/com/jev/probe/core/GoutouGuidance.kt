package com.jev.probe.core

/** Evidence-first rules shared by the Android draft prompt and overlay. */
object GoutouGuidance {
    private val noContact = listOf(
        "不要再联系我", "别再联系我", "不要再给我发消息", "别再给我发消息",
        "不要再找我", "别再找我", "请不要联系我"
    )

    fun explicitBoundary(snapshot: ChatSnapshot): Boolean {
        val latest = snapshot.messages.lastOrNull { it.side == "other" } ?: return false
        return noContact.any { latest.text.contains(it) }
    }

    fun boundaryAnalysis(): Analysis = Analysis(
        trueIntent = Choice("对方明确要求停止联系", Double.NaN, emptyMap()),
        dangerLevel = null, sheNeeds = null, shouldReplyNow = null,
        bestAction = Choice("say_less", Double.NaN, emptyMap()),
        tensionResolved = null, literalQuestion = null, rankedReplies = emptyList(), latencyMs = 0)

    fun actionLabel(action: String?): String = when (action) {
        "承接", "降压", "调侃", "轻推", "约见", "澄清", "收线" -> action
        "check_history" -> "翻聊天记录"
        "apologize" -> "先道歉"
        "give_commitment" -> "给承诺"
        "explain" -> "解释清楚"
        "acknowledge" -> "接住情绪"
        "say_less" -> "少说两句"
        "make_plan" -> "定个安排"
        else -> "先核对原文"
    }

    fun nextStep(action: String?): String = when (action) {
        "承接" -> "接住这句话，留出对方继续表达的空间。"
        "降压" -> "先接住对方的安排，这轮不追问、不催促。"
        "调侃" -> "顺着双方已有的玩笑轻轻接一句，不拿痛处开玩笑。"
        "轻推" -> "只推进一小步，给对方轻松选择或拒绝的空间。"
        "约见" -> "提出你能做到的具体安排，让对方决定是否参与。"
        "澄清" -> "只问一个关键未知，不把猜测写成事实。"
        "收线" -> "停止推进和追问；对方要求停止联系时就不再发消息。"
        "check_history" -> "先核对原聊天，再决定怎么回。"
        "apologize" -> "只为已确认的问题道歉，观察对方是否愿意继续谈。"
        "give_commitment" -> "确认自己真能做到的时间和行动，再给出承诺。"
        "explain" -> "只说明自己知道的事实，缺的部分先查证。"
        "acknowledge" -> "接住这句话，留出对方继续表达的空间。"
        "say_less" -> "这轮可以少说，必要时不回复。"
        "make_plan" -> "提出可执行的安排，并让对方选择或修正。"
        else -> "先核对原文，再决定下一步。"
    }

    const val stopCondition = "对方明确拒绝或要求停止联系时，停止推进。"

    const val draftRules = """
狗头军师规则：先区分可见事实、暂定推测与仍未知；一轮回复只做一个主动作。
对方的意图只是可能解释，别在回复里宣称看穿了 TA。
尊重拒绝与边界；不以得到某个人为唯一目标，不使用操控、施压、贬低或虚假时间限制。
不要编造见面时间、共同经历、自己做过的事或做不到的承诺。
回复像用户平时发的一句话；不把分析术语、理由、代价塞进可发送文本。
"""
}
