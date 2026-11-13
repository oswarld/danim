package app.danim.automotive.ui

import app.danim.automotive.SignalInfo
import app.danim.core.Availability
import app.danim.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The paired car's live tiles show this car's latest reading or the exact reason there is none; never a zero. */
class LiveModelTest {
    private val at = 1_793_370_000_000L
    private fun num(v: Double) = SignalInfo(Availability.AVAILABLE, Value.Num(v), at)
    private fun text(v: String) = SignalInfo(Availability.AVAILABLE, Value.Text(v), at)

    @Test
    fun `numbers carry their unit and time and are grouped`() {
        assertEquals(LiveModel.Tile(LiveModel.Kind.VALUE, "81%", at), LiveModel.number(num(81.0), "%"))
        assertEquals("12,003 km", LiveModel.number(num(12003.0), "km").text)
        assertEquals("406.5 km", LiveModel.number(num(406.5), "km").text)
        assertEquals("0 km", LiveModel.number(num(0.0), "km").text) // a known zero stays a zero
    }

    @Test
    fun `missing and refused readings are labelled, never zero`() {
        assertEquals(LiveModel.Tile(LiveModel.Kind.NO_DATA, null, null), LiveModel.number(null, "%"))
        assertEquals(LiveModel.Kind.UNSUPPORTED, LiveModel.number(SignalInfo(Availability.UNSUPPORTED, null, at), "%").kind)
        assertEquals(LiveModel.Kind.DENIED, LiveModel.number(SignalInfo(Availability.PERMISSION_DENIED, null, at), "%").kind)
        assertEquals(LiveModel.Kind.ERROR, LiveModel.number(SignalInfo(Availability.ERROR, null, at), "%").kind)
        assertEquals(LiveModel.Kind.UNAVAILABLE, LiveModel.number(SignalInfo(Availability.UNAVAILABLE, null, at), "%").kind)
        assertEquals(LiveModel.Kind.UNAVAILABLE, LiveModel.number(text("74"), "%").kind)
    }

    @Test
    fun `motion and charging words map to states and anything else is unavailable`() {
        assertEquals(LiveModel.Motion.MOVING, LiveModel.motion(text("moving")).second)
        assertEquals(LiveModel.Motion.PARKED, LiveModel.motion(text("parked")).second)
        assertEquals(LiveModel.Motion.UNKNOWN, LiveModel.motion(text("unknown")).second)
        assertNull(LiveModel.motion(text("flying")).second)
        assertEquals(LiveModel.Kind.UNAVAILABLE, LiveModel.motion(text("flying")).first.kind)
        assertEquals(LiveModel.Charging.CHARGING, LiveModel.charging(text("charging")).second)
        assertEquals(LiveModel.Charging.COMPLETE, LiveModel.charging(text("complete")).second)
        assertEquals(LiveModel.Kind.NO_DATA, LiveModel.charging(null).first.kind)
    }

    @Test
    fun `a reading older than the freshness window is stale and its age decides the time label`() {
        val minute = 60_000L
        assertEquals(false, LiveModel.isStale(at, at + 6 * minute))
        assertEquals(true, LiveModel.isStale(at, at + 6 * minute + 1))
        assertEquals(false, LiveModel.isStale(at, at - minute)) // a clock behind the reading is not stale
        val zone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        // 2026-10-30 23:24 KST read at 2026-10-31 16:13 KST: another day, so the label carries the date.
        val lateYesterday = 1_793_370_000_000L - (1_793_370_000_000L % 86_400_000L) + 14 * 3_600_000L + 24 * minute
        assertEquals(false, LiveModel.sameDay(lateYesterday, lateYesterday + 16 * 3_600_000L + 49 * minute, zone))
        assertEquals(true, LiveModel.sameDay(lateYesterday, lateYesterday + 30 * minute, zone))
    }
}
