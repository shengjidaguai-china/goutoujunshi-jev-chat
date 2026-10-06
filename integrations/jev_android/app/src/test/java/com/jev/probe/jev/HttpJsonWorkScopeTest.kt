package com.jev.probe.jev

import com.jev.probe.core.WorkScope
import com.jev.probe.core.WorkToken
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpJsonWorkScopeTest {
    @Test fun cancelledRoundCannotStartReplyButNextRoundCanRequest() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.close()
            val bytes = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/chat"
            val old = WorkToken()
            WorkScope.run(old) {
                assertTrue(HttpJson.post(url, "fixture-key", JSONObject(), Route.JUDGE).getBoolean("ok"))
                old.cancel()
                try {
                    HttpJson.post(url, "fixture-key", JSONObject(), Route.REPLY)
                    fail("cancelled reply request was sent")
                } catch (_: CancellationException) { }
            }
            assertEquals(1, requests.get())
            WorkScope.run(WorkToken()) {
                HttpJson.post(url, "fixture-key", JSONObject(), Route.REPLY)
            }
            assertEquals(2, requests.get())
        } finally { server.stop(0) }
    }
}
