package app.danim.automotive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The mock demo must never be entered on top of a pairing in flight. `startPairing` marks the state
 * busy before `POST /pairing/start` is sent; its late answer then shows the pairing code. If mock
 * entry were allowed in between, that answer would land on top of the mock session (saving the
 * pending secret, starting the poll and forcing the permission prompt over the demo).
 */
class MockEntryTest {
    private val setup = UiState(apiBaseUrl = "https://api.example.test", siteUrl = "https://example.test")
    private val code = PairingUi("ABCD-1234", "https://example.test/pair?code=ABCD-1234", arrayOf(booleanArrayOf(true)), expiresAtMs = 1L)

    @Test
    fun `mock entry is refused while the pairing start is in flight`() {
        val inFlight = setup.copy(busy = true, message = null) // what startPairing publishes before the request
        val afterTap = inFlight.enteringMock()
        assertSame(inFlight, afterTap)
        assertEquals(Phase.SETUP, afterTap.phase)
        assertNull(afterTap.mock)
    }

    @Test
    fun `a delayed start answer shows the pairing the user started, never over a mock`() {
        val inFlight = setup.copy(busy = true)
        val afterTap = inFlight.enteringMock() // ignored (see above)
        val answered = afterTap.showingPairing(code)
        assertEquals(Phase.PAIRING, answered.phase)
        assertSame(code, answered.pairing)
        assertNull(answered.mock)
        assertEquals(false, answered.busy)
        // Once the code is showing, mock entry is still refused; cancelling the pairing is the explicit way out.
        assertSame(answered, answered.enteringMock())
    }

    @Test
    fun `mock entry from an idle setup or paired screen remembers where to return`() {
        val fromSetup = setup.enteringMock()
        assertEquals(Phase.MOCK, fromSetup.phase)
        assertEquals(Phase.SETUP, fromSetup.mockReturn)
        assertEquals(ShowcaseUi(), fromSetup.mock)

        val fromPaired = setup.copy(phase = Phase.PAIRED).enteringMock()
        assertEquals(Phase.MOCK, fromPaired.phase)
        assertEquals(Phase.PAIRED, fromPaired.mockReturn)

        // Re-entering while already in the mock keeps the running session instead of resetting it.
        val running = fromSetup.copy(mock = ShowcaseUi(tab = ShowcaseTab.CONNECT, resets = 2))
        assertSame(running, running.enteringMock())
    }
}
