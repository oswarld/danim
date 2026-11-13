package app.danim.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TIMEOUT = 30_000L

class ReadinessDeadlineTest {
    @Test
    fun `not waiting until started`() {
        val d = ReadinessDeadline(TIMEOUT)
        assertFalse(d.isWaiting)
        assertFalse(d.isExpired(Long.MAX_VALUE / 2))
    }

    @Test
    fun `before the deadline is not expired`() {
        val d = ReadinessDeadline(TIMEOUT)
        d.start(1_000)
        assertTrue(d.isWaiting)
        assertFalse(d.isExpired(1_000))
        assertFalse(d.isExpired(1_000 + TIMEOUT - 1))
    }

    @Test
    fun `the exact deadline is expired`() {
        val d = ReadinessDeadline(TIMEOUT)
        d.start(1_000)
        assertTrue(d.isExpired(1_000 + TIMEOUT))
        assertTrue(d.isExpired(1_000 + TIMEOUT + 1))
    }

    @Test
    fun `cleared after readiness never expires`() {
        val d = ReadinessDeadline(TIMEOUT)
        d.start(1_000)
        d.clear()
        assertFalse(d.isWaiting)
        assertFalse(d.isExpired(1_000 + TIMEOUT))
        assertFalse(d.isExpired(1_000 + 10 * TIMEOUT))
    }

    @Test
    fun `a new attempt restarts the window`() {
        val d = ReadinessDeadline(TIMEOUT)
        d.start(0)
        d.start(20_000)
        assertFalse(d.isExpired(TIMEOUT))
        assertFalse(d.isExpired(20_000 + TIMEOUT - 1))
        assertTrue(d.isExpired(20_000 + TIMEOUT))
    }

    @Test
    fun `a backwards timestamp re-anchors instead of expiring or waiting forever`() {
        val d = ReadinessDeadline(TIMEOUT)
        d.start(100_000)
        assertFalse(d.isExpired(50_000))
        assertTrue(d.isWaiting)
        assertFalse(d.isExpired(50_000 + TIMEOUT - 1))
        assertTrue(d.isExpired(50_000 + TIMEOUT))
    }

    @Test
    fun `timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> { ReadinessDeadline(0) }
        assertFailsWith<IllegalArgumentException> { ReadinessDeadline(-1) }
    }
}
