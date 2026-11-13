package app.danim.automotive.ui

import app.danim.automotive.SignalInfo
import app.danim.core.Availability
import app.danim.core.Signal
import app.danim.core.Value
import app.danim.core.copilot.Intent
import app.danim.core.copilot.Origin
import app.danim.core.copilot.Say
import app.danim.core.copilot.TripRecap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copilot's context: live readings win when they exist, the fixed record is used otherwise, and unknown stays unknown. */
class CopilotModelTest {
    private val at = 1_794_300_000_000L
    private fun num(v: Double) = SignalInfo(Availability.AVAILABLE, Value.Num(v), at)
    private fun text(v: String) = SignalInfo(Availability.AVAILABLE, Value.Text(v), at)

    @Test
    fun `without live readings the context is the fixed record`() {
        val c = CopilotModel.context(null, null, 8)
        assertEquals(Origin.FIXED, c.now.origin)
        assertEquals(74.0, c.now.socPercent!!, 0.0); assertEquals(296.0, c.now.rangeKm!!, 0.0)
        assertEquals(89.5, c.week.distanceKm, 0.0); assertEquals(17_000, c.week.costKrw)
        assertEquals(7L, c.service!!.daysRemaining)
        assertEquals(Origin.FIXED, c.lastTrip!!.origin); assertNull(c.lastTrip!!.socUsed)
        assertEquals(Origin.FIXED, CopilotModel.context(emptyMap(), null, 8).now.origin)
    }

    @Test
    fun `live readings replace the fixed state and a refused reading is not a value`() {
        val signals = mapOf(
            Signal.BATTERY_SOC_PERCENT to num(62.0), Signal.RANGE_KM to num(310.0), Signal.ODOMETER_KM to num(12_046.0),
            Signal.VEHICLE_MOTION to text("parked"),
        )
        val now = CopilotModel.liveNow(signals)!!
        assertEquals(Origin.LIVE, now.origin); assertEquals(62.0, now.socPercent!!, 0.0); assertEquals(true, now.parked)
        val denied = mapOf(Signal.BATTERY_SOC_PERCENT to SignalInfo(Availability.PERMISSION_DENIED, null, at), Signal.RANGE_KM to num(310.0))
        assertNull(CopilotModel.liveNow(denied)!!.socPercent)
        assertNull(CopilotModel.liveNow(mapOf(Signal.BATTERY_SOC_PERCENT to SignalInfo(Availability.UNSUPPORTED, null, at))))
        assertNull(CopilotModel.liveNow(mapOf(Signal.VEHICLE_MOTION to text("unknown"), Signal.RANGE_KM to num(10.0)))!!.parked)
        val live = TripRecap(12.0, 12, 3.0, Origin.LIVE)
        assertSame(live, CopilotModel.context(signals, live, 8).lastTrip)
        assertEquals(Triple("parked", 12_046.0, 62.0), CopilotModel.trackerInput(signals))
    }

    @Test
    fun `session state keeps the last three turns and toggles concept switches`() {
        var ui = CopilotUi(CopilotModel.context(null, null, 8))
        repeat(5) { i -> ui = ui.asked(Turn("q$i", Say("a$i", "a$i"), Intent.BATTERY)) }
        assertEquals(listOf("q2", "q3", "q4"), ui.turns.map { it.question })
        ui = ui.toggled("precondition"); assertTrue("precondition" in ui.enabled)
        ui = ui.toggled("precondition"); assertTrue(ui.enabled.isEmpty())
    }

    @Test
    fun `entering the copilot follows the showcase entry rules`() {
        val c = CopilotModel.context(null, null, 8)
        val entered = UiState().enteringCopilot(c)
        assertEquals(Phase.COPILOT, entered.phase); assertEquals(Phase.SETUP, entered.mockReturn)
        assertEquals(Phase.PAIRED, UiState(phase = Phase.PAIRED).enteringCopilot(c).mockReturn)
        val busy = UiState(busy = true); assertSame(busy, busy.enteringCopilot(c))
        assertSame(entered, entered.enteringCopilot(c)); assertSame(entered, entered.enteringMock())
    }
}
