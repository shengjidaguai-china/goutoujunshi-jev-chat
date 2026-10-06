package com.jev.probe.jev

import com.jev.probe.core.WorkScope
import com.jev.probe.core.WorkToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpJsonWorkScopeTest {
    @Test fun cancelledRoundCannotStartReplyButNextRoundCanRequest() {
        val requests = AtomicInteger()
        // Use Android's standard Java APIs; jdk.httpserver is absent from the
        // Android Gradle unit-test compile classpath even on a desktop JDK.
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val handling = executor.submit {
            repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    assertEquals("POST /chat HTTP/1.1", readHttpLine(input))
                    var contentLength = 0
                    while (true) {
                        val header = readHttpLine(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { check(input.read() != -1) }
                    requests.incrementAndGet()
                    val bytes = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                        output.flush()
                    }
                }
            }
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}/chat"
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
            // Surface server-side failures instead of silently losing them in
            // a background thread, and bound the wait to avoid hanging CI.
            handling.get(10, TimeUnit.SECONDS)
            assertEquals(2, requests.get())
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun readHttpLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val next = input.read()
            check(next != -1) { "Incomplete HTTP request" }
            if (next == '\n'.code) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(next)
        }
        error("HTTP header line too long")
    }
}
