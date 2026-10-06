package com.jev.probe.core

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class WorkScopeTest {
    @Test fun cancellationClosesRegisteredResourcesOnlyOnce() {
        val token = WorkToken()
        var closed = 0
        token.onCancel { closed++ }
        token.cancel(); token.cancel()
        assertEquals(1, closed)
    }

    @Test fun registeringAfterCancellationClosesImmediately() {
        val token = WorkToken().apply { cancel() }
        var closed = false
        token.onCancel { closed = true }
        assertTrue(closed)
    }

    @Test fun finishedResourceIsDetachedFromCancellation() {
        val token = WorkToken()
        var closed = false
        val detach = token.onCancel { closed = true }
        detach(); token.cancel()
        assertFalse(closed)
    }

    @Test fun cancelledRoundCannotMakeTheNextRequest() {
        val token = WorkToken()
        WorkScope.run(token) {
            WorkScope.checkActive()
            token.cancel()
            try { WorkScope.checkActive(); fail("cancelled request was allowed") }
            catch (_: CancellationException) { }
        }
        // The worker thread must not retain the previous round's scope.
        WorkScope.checkActive()
    }
}
