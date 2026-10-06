package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteKeysTest {
    @org.junit.Test fun strategyTestUsesOnlyTheCurrentOfficialReplyRoute() {
        org.junit.Assert.assertEquals("typed-key", RouteKeys.strategy("", "typed-key", "https://api.deepseek.com"))
        org.junit.Assert.assertEquals("typed-key", RouteKeys.strategy("", "typed-key", "https://api.deepseek.com/v1/"))
        org.junit.Assert.assertEquals("", RouteKeys.strategy("", "other-key", "https://openrouter.ai/api/v1"))
        org.junit.Assert.assertEquals("", RouteKeys.strategy("", "other-key", "http://api.deepseek.com/v1"))
        org.junit.Assert.assertEquals("explicit", RouteKeys.strategy("explicit", "other-key", "https://openrouter.ai/api/v1"))
    }
    @Test fun sameOriginCanReuseKey() {
        assertEquals("openrouter-key", RouteKeys.reply("", "openrouter-key",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://openrouter.ai/api/alpha/decisions"))
    }

    @Test fun anotherProviderCannotReceiveJudgeKey() {
        assertEquals("", RouteKeys.reply("", "typesafe-key",
            "https://api.deepseek.com/v1/chat/completions",
            "https://api.typesafe.ai/v1/systemone"))
    }

    @Test fun visionUsesOnlyMatchingRoute() {
        assertEquals("reply-key", RouteKeys.vision("", "reply-key", "judge-key",
            "https://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com/v1/chat/completions",
            "https://api.typesafe.ai/v1/systemone"))
        assertEquals("", RouteKeys.vision("", "reply-key", "judge-key",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://api.deepseek.com/v1/chat/completions",
            "https://api.typesafe.ai/v1/systemone"))
    }

    @Test fun schemeAndPortMatter() {
        assertEquals("", RouteKeys.reply("", "key", "http://localhost:8080/v1",
            "http://localhost:8081/alpha/decisions"))
        assertEquals("", RouteKeys.reply("", "key", "http://example.com/v1",
            "https://example.com/alpha/decisions"))
    }
}
