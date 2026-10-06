package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.GoutouGuidance
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(private val prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)
    private val deepSeekStrategy = DeepSeekStrategyClient(prefs)

    fun details(snapshot: ChatSnapshot, relationship: String, judgment: Analysis, ctx: ChatContext? = null): String =
        replyClient.details(snapshot, relationship, judgment, ctx)

    fun explain(snapshot: ChatSnapshot, relationship: String, judgment: Analysis, candidate: String, ctx: ChatContext? = null): String =
        replyClient.explain(snapshot, relationship, judgment, candidate, ctx)

    fun rewrite(snapshot: ChatSnapshot, judgment: Analysis, candidates: List<String>): List<String> =
        replyClient.rewrite(snapshot, judgment, candidates)

    fun rerank(snapshot: ChatSnapshot, relationship: String, judgment: Analysis,
               candidates: List<String>, ctx: ChatContext? = null): List<RankedReply> = try {
        if (prefs.strategyProvider == "deepseek")
            deepSeekStrategy.rank(snapshot, relationship, judgment.strategy ?: "澄清", candidates, ctx)
        else judgeClient.rank(snapshot, relationship, candidates, ctx)
    } catch (_: Exception) { candidates.map { RankedReply(it, 0.0) } }

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        if (GoutouGuidance.explicitBoundary(snapshot)) GoutouGuidance.boundaryAnalysis()
        else if (prefs.strategyProvider == "deepseek") deepSeekStrategy.judge(snapshot, relationship, ctx)
        else judgeClient.judge(snapshot, relationship, ctx)

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        judgment: Analysis? = null
    ): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship, ctx, judgment)
        if (candidates.isEmpty()) return emptyList()
        // A ranking outage must not discard drafts that were already generated.
        // Zero means "ranking pending" in the overlay, not a 0% success chance.
        return try {
            if (prefs.strategyProvider == "deepseek")
                deepSeekStrategy.rank(snapshot, relationship, judgment?.strategy ?: "澄清", candidates, ctx)
            else judgeClient.rank(snapshot, relationship, candidates, ctx)
        }
        catch (_: Exception) { candidates.map { RankedReply(it, 0.0) } }
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx, a) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
