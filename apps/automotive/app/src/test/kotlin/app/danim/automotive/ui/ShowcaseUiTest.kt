package app.danim.automotive.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The showcase is fixed-record presentation state only: a tab and a reset counter, nothing simulated. */
class ShowcaseUiTest {
    @Test
    fun `the showcase opens on the fixed status with no selection or simulation state`() {
        val s = ShowcaseUi()
        assertEquals(ShowcaseTab.STATUS, s.tab)
        assertEquals(0, s.resets)
        assertEquals(false, s.limits)
        assertEquals(listOf("STATUS", "REPORT", "MAINTENANCE", "CONNECT", "ROADMAP"), ShowcaseTab.entries.map { it.name })
    }

    @Test
    fun `reset returns to the fixed status and counts, and exit discards the showcase`() {
        val moved = ShowcaseUi(tab = ShowcaseTab.CONNECT, resets = 1, limits = true)
        val reset = moved.reset() // what MainViewModel.mockReset publishes
        assertEquals(ShowcaseTab.STATUS, reset.tab); assertEquals(2, reset.resets); assertEquals(false, reset.limits)
        val entered = UiState().enteringMock().copy(mock = moved)
        assertEquals(Phase.MOCK, entered.phase)
        val exited = if (entered.phase == Phase.MOCK) entered.copy(phase = entered.mockReturn, mock = null) else entered // MainViewModel.exitMock
        assertEquals(Phase.SETUP, exited.phase); assertNull(exited.mock)
        // Re-entry starts from the documented default again.
        assertEquals(ShowcaseUi(), exited.enteringMock().mock)
    }

    @Test
    fun `the limitations detail toggles without moving the tab, and a tab change keeps it`() {
        val open = ShowcaseUi(tab = ShowcaseTab.MAINTENANCE).toggleLimits()
        assertEquals(true, open.limits); assertEquals(ShowcaseTab.MAINTENANCE, open.tab)
        assertEquals(false, open.toggleLimits().limits)
        assertEquals(true, open.copy(tab = ShowcaseTab.CONNECT).limits)
    }
}
