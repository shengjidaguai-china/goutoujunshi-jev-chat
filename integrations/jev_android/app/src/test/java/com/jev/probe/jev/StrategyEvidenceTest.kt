package com.jev.probe.jev

import org.junit.Assert.*
import org.junit.Test

class StrategyEvidenceTest {
    private val valid = """{"strategy":"降压","intent":"可能需要休息","confidence":0.6,"facts":["对方说忙"],"unknowns":["具体时间"]}"""

    @Test fun acceptsCompleteEvidenceAndUnknownConfidence() {
        assertNotNull(StrategyEvidence.parse(valid))
        assertNotNull(StrategyEvidence.parse("```json\n$valid\n```"))
        assertNotNull(StrategyEvidence.parse(valid.replace("0.6", "null")))
    }

    @Test fun rejectsIncompleteEvidenceAndWrongFieldTypes() {
        assertNull(StrategyEvidence.parse("""{"strategy":"降压"}"""))
        assertNull(StrategyEvidence.parse(valid.replace("0.6", "6")))
        assertNull(StrategyEvidence.parse(valid.replace("0.6", "\"0.6\"")))
        assertNull(StrategyEvidence.parse(valid.replace("[\"对方说忙\"]", "[1]")))
        assertNull(StrategyEvidence.parse(valid.replace("降压", "未知策略")))
    }
}
