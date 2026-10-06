package com.jev.probe.core

import java.util.concurrent.CancellationException

/** One capture round owns its queued work and open HTTP connections. */
class WorkToken(private val enabled: () -> Boolean = { true }) {
    @Volatile private var cancelled = false
    private val cancellations = LinkedHashSet<() -> Unit>()

    fun isActive(): Boolean = !cancelled && enabled()

    fun checkActive() {
        if (!isActive() || Thread.currentThread().isInterrupted)
            throw CancellationException("本轮任务已取消")
    }

    fun onCancel(callback: () -> Unit): () -> Unit {
        val cancelNow = synchronized(this) {
            if (cancelled) true else { cancellations.add(callback); false }
        }
        if (cancelNow) callback()
        return { synchronized(this) { cancellations.remove(callback) }; Unit }
    }

    fun cancel() {
        val callbacks = synchronized(this) {
            if (cancelled) return
            cancelled = true
            cancellations.toList().also { cancellations.clear() }
        }
        callbacks.forEach { runCatching { it() } }
    }
}

/** Requests made by a service worker inherit the capture round's cancellation. */
object WorkScope {
    private val current = ThreadLocal<WorkToken>()

    fun checkActive() { current.get()?.checkActive() }

    fun onCancel(callback: () -> Unit): () -> Unit =
        current.get()?.onCancel(callback) ?: { }

    fun <T> run(token: WorkToken, block: () -> T): T {
        val previous = current.get()
        current.set(token)
        return try { token.checkActive(); block() }
        finally { if (previous == null) current.remove() else current.set(previous) }
    }
}
