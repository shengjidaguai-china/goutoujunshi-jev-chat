package com.jev.probe.capture

import com.jev.probe.core.WorkToken

/** Review and analysis state belongs to one enabled, still-current capture round. */
internal class CaptureSession(private val allowed: () -> Boolean = { true }) {
    @Volatile private var enabled = false
    @Volatile private var destroyed = false
    @Volatile var current = newToken()
        private set
    var reviewPending = false
        private set
    var analyzing = false
        private set

    private fun newToken() = WorkToken { enabled && !destroyed && allowed() }

    fun isCurrent(token: WorkToken): Boolean = token === current && token.isActive()

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reset()
    }

    fun reset() {
        val previous = current
        current = newToken()
        reviewPending = false
        analyzing = false
        previous.cancel()
    }

    fun beginReview(): WorkToken? {
        if (!current.isActive() || reviewPending || analyzing) return null
        reviewPending = true
        return current
    }

    fun confirmReview(token: WorkToken): Boolean {
        if (!isCurrent(token) || !reviewPending) return false
        reviewPending = false
        return true
    }

    fun beginAnalysis(token: WorkToken): Boolean {
        if (!isCurrent(token) || reviewPending || analyzing) return false
        analyzing = true
        return true
    }

    fun finishAnalysis(token: WorkToken): Boolean {
        if (!isCurrent(token)) return false
        analyzing = false
        return true
    }

    fun destroy() { destroyed = true; reset() }
}
