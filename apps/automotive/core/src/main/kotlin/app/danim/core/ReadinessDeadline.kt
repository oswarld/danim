package app.danim.core

/**
 * Bounded wait for an asynchronous readiness signal (the AAOS Car lifecycle callback). Times are monotonic
 * milliseconds (`SystemClock.elapsedRealtime()`), never wall time, so a wall-clock change cannot expire or extend it.
 *
 * Not thread-safe: the owner confines it to one thread.
 */
class ReadinessDeadline(val timeoutMs: Long) {
    init {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
    }

    private var startedAtMs: Long? = null

    /** True while an attempt is waiting for readiness. */
    val isWaiting: Boolean get() = startedAtMs != null

    /** Starts (or restarts, for a new attempt) the wait at [nowMs]. */
    fun start(nowMs: Long) {
        startedAtMs = nowMs
    }

    /** Readiness arrived, the attempt failed, or the owner stopped: nothing is pending. */
    fun clear() {
        startedAtMs = null
    }

    /**
     * True once [timeoutMs] has elapsed since [start] (the exact deadline counts as expired). A timestamp before the
     * start cannot come from a monotonic clock; it re-anchors the wait there instead of expiring early or waiting forever.
     */
    fun isExpired(nowMs: Long): Boolean {
        val started = startedAtMs ?: return false
        if (nowMs < started) {
            startedAtMs = nowMs
            return false
        }
        return nowMs - started >= timeoutMs
    }
}
