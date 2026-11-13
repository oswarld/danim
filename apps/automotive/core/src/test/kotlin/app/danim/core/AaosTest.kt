package app.danim.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEC = 1_000_000_000L

class AaosMappingTest {
    @Test
    fun `ignition mapping`() {
        for (n in listOf("ON", "START")) assertEquals("on", AaosMapping.ignition(n))
        for (n in listOf("OFF", "LOCK")) assertEquals("off", AaosMapping.ignition(n))
        for (n in listOf("ACC", "UNDEFINED", "FUTURE_STATE", null)) assertEquals("unknown", AaosMapping.ignition(n))
    }

    @Test
    fun `charge state mapping never infers discharging`() {
        assertEquals(Availability.AVAILABLE to Value.Text("charging"), AaosMapping.charging("STATE_CHARGING"))
        assertEquals(Availability.AVAILABLE to Value.Text("complete"), AaosMapping.charging("STATE_FULLY_CHARGED"))
        assertEquals(Availability.AVAILABLE to Value.Text("idle"), AaosMapping.charging("STATE_NOT_CHARGING"))
        assertEquals(Availability.AVAILABLE to Value.Text("unknown"), AaosMapping.charging("STATE_UNKNOWN"))
        assertEquals(Availability.ERROR to null, AaosMapping.charging("STATE_ERROR"))
        assertEquals(Availability.UNAVAILABLE to null, AaosMapping.charging("STATE_NEW"))
    }

    @Test
    fun `range metres to km`() {
        assertEquals(123.456, AaosMapping.rangeMetresToKm(123_456.0)!!, 1e-9)
        assertEquals(0.0, AaosMapping.rangeMetresToKm(0.0)!!, 0.0)
        assertNull(AaosMapping.rangeMetresToKm(-1.0))
        assertNull(AaosMapping.rangeMetresToKm(Double.NaN))
        assertNull(AaosMapping.rangeMetresToKm(null))
    }

    @Test
    fun `timestamp age is preserved and impossible stamps rejected`() {
        val nowElapsed = 1_000 * SEC
        val nowWall = 1_700_000_000_000L
        assertEquals(nowWall - 30_000, AaosTime.toWallMs(nowElapsed - 30 * SEC, nowElapsed, nowWall))
        assertEquals(nowWall, AaosTime.toWallMs(nowElapsed, nowElapsed, nowWall))
        assertEquals(nowWall, AaosTime.toWallMs(nowElapsed + SEC / 2, nowElapsed, nowWall)) // small skew tolerated, clamped to now
        assertNull(AaosTime.toWallMs(nowElapsed + 5 * SEC, nowElapsed, nowWall)) // future
        assertNull(AaosTime.toWallMs(0, nowElapsed, nowWall))
        assertNull(AaosTime.toWallMs(-1, nowElapsed, nowWall))
        assertNull(AaosTime.toWallMs(1, 1_000_000 * SEC, nowWall)) // older than a week
    }
}

class MotionEstimatorTest {
    private val now = 1_000_000L

    @Test
    fun `fresh non-zero speed is moving, including reverse`() {
        val m = MotionEstimator()
        m.onSpeed(8.3, now - 1_000)
        assertEquals("moving" to now - 1_000, m.estimate(now))
        m.onSpeed(-1.2, now - 500)
        assertEquals("moving" to now - 500, m.estimate(now))
    }

    @Test
    fun `zero speed alone is unknown, zero speed plus park is parked`() {
        val m = MotionEstimator()
        m.onSpeed(0.0, now - 2_000)
        assertEquals("unknown" to now, m.estimate(now))
        m.onGear(false, now - 1_000) // drive/neutral: still unknown
        assertEquals("unknown" to now, m.estimate(now))
        m.onGear(true, now - 1_000)
        assertEquals("parked" to now - 1_000, m.estimate(now))
    }

    @Test
    fun `park gear without speed is never parked`() {
        val m = MotionEstimator()
        m.onGear(true, now - 100)
        assertEquals("unknown" to now, m.estimate(now))
    }

    @Test
    fun `park holds while the gear stays in park, even when zero speed is no longer re-published`() {
        // Gear is an on-change property and many VHALs (including the Pleos emulator) publish speed only on
        // change, so a parked car stops sending samples. Park + last speed zero is positive parked evidence
        // at any age; the car cannot move in P without first leaving it, which is a new gear event.
        val m = MotionEstimator(speedMaxAgeMs = 15_000)
        m.onSpeed(0.0, now - 20_000); m.onGear(true, now - 1_000)
        assertEquals("parked" to now, m.estimate(now)) // speed no longer fresh: the hold is reported now
        // Still in park an hour later: the hold is re-confirmed at evaluation time, so the collector can
        // re-report "parked" as a heartbeat instead of a duplicate of the hour-old reading.
        m.onGear(true, now - 600_000)
        assertEquals("parked" to now + 3_600_000, m.estimate(now + 3_600_000))
        // A non-zero last sample in P is contradictory, stale or not.
        m.onSpeed(5.0, now - 30_000)
        assertEquals("unknown" to now, m.estimate(now))
        // Leaving P without a fresh speed is unknown, never parked.
        m.onSpeed(0.0, now - 30_000); m.onGear(false, now - 1_000)
        assertEquals("unknown" to now, m.estimate(now))
    }

    @Test
    fun `stale or contradictory inputs are unknown`() {
        val m = MotionEstimator(speedMaxAgeMs = 15_000)
        m.onSpeed(3.0, now - 20_000); m.onGear(false, now - 1_000)
        assertEquals("unknown" to now, m.estimate(now)) // stale speed outside park
        m.onGear(true, now - 1_000)
        m.onSpeed(5.0, now - 1_000)
        assertEquals("unknown" to now, m.estimate(now)) // moving while P: contradictory
        m.onGear(false, now - 1_000)
        assertEquals("moving" to now - 1_000, m.estimate(now))
        m.onSpeed(null, now) // sensor failure clears speed
        assertEquals("unknown" to now, m.estimate(now))
    }
}

class AaosReadingsTest {
    private val nowElapsed = 5_000 * SEC
    private val nowWall = 1_700_000_000_000L
    private val out = mutableListOf<List<Any?>>()
    private val readings = AaosReadings({ s, a, v, at, src -> out += listOf(s, a, v, at, src) })

    private fun ev(p: AaosProperty, number: Double? = null, name: String? = null, status: Int = 0, ageSec: Long = 1) =
        AaosEvent(p, number, name, status, nowElapsed - ageSec * SEC)

    private fun handle(e: AaosEvent) = readings.handle(e, nowElapsed, nowWall)

    @Test
    fun `every reading carries the aaos source and the property timestamp age`() {
        handle(ev(AaosProperty.IGNITION_STATE, name = "ON", ageSec = 40))
        assertEquals(listOf(Signal.IGNITION_STATE, Availability.AVAILABLE, Value.Text("on"), nowWall - 40_000, Source.AAOS_CAR_PROPERTY), out.single())
    }

    @Test
    fun `a cached initial value is not relabelled as current`() {
        handle(ev(AaosProperty.EV_CHARGE_STATE, name = "STATE_CHARGING", ageSec = 3_600))
        assertEquals(nowWall - 3_600_000, out.single()[3])
    }

    @Test
    fun `unavailable and error statuses never carry values`() {
        handle(ev(AaosProperty.RANGE_REMAINING, number = 100_000.0, status = AaosMapping.STATUS_UNAVAILABLE))
        handle(ev(AaosProperty.IGNITION_STATE, name = "ON", status = AaosMapping.STATUS_ERROR))
        assertEquals(listOf(Signal.RANGE_KM, Availability.UNAVAILABLE, null, nowWall, Source.AAOS_CAR_PROPERTY), out[0])
        assertEquals(listOf(Signal.IGNITION_STATE, Availability.ERROR, null, nowWall, Source.AAOS_CAR_PROPERTY), out[1])
    }

    @Test
    fun `future timestamp is rejected as unavailable`() {
        handle(AaosEvent(AaosProperty.RANGE_REMAINING, 1000.0, null, 0, nowElapsed + 60 * SEC))
        assertEquals(Availability.UNAVAILABLE, out.single()[1])
    }

    @Test
    fun `range is converted from metres`() {
        handle(ev(AaosProperty.RANGE_REMAINING, number = 250_500.0))
        assertEquals(Value.Num(250.5), out.single()[2])
    }

    @Test
    fun `speed then park gear yields moving then parked and failures degrade to the failure status`() {
        handle(ev(AaosProperty.PERF_VEHICLE_SPEED, number = 10.0))
        assertEquals(Value.Text("moving"), out.last()[2])
        handle(ev(AaosProperty.PERF_VEHICLE_SPEED, number = 0.0))
        assertEquals(Value.Text("unknown"), out.last()[2])
        handle(ev(AaosProperty.GEAR_SELECTION, name = "GEAR_PARK"))
        assertEquals(Value.Text("parked"), out.last()[2])
        handle(ev(AaosProperty.PERF_VEHICLE_SPEED, status = AaosMapping.STATUS_ERROR))
        assertEquals(Availability.ERROR, out.last()[1])
        assertNull(out.last()[2])
        assertTrue(out.all { it[4] == Source.AAOS_CAR_PROPERTY && it[0] == Signal.VEHICLE_MOTION || it[0] != Signal.VEHICLE_MOTION })
    }

    @Test
    fun `forgetting speed after access loss stops moving from being reported`() {
        handle(ev(AaosProperty.PERF_VEHICLE_SPEED, number = 10.0))
        readings.forget(AaosProperty.PERF_VEHICLE_SPEED)
        readings.reevaluateMotion(nowWall)
        assertEquals(Value.Text("unknown"), out.last()[2])
    }

    @Test
    fun `heartbeat re-evaluation turns stale speed into explicit unknown`() {
        handle(ev(AaosProperty.PERF_VEHICLE_SPEED, number = 10.0))
        readings.reevaluateMotion(nowWall + 60_000)
        assertEquals(Value.Text("unknown"), out.last()[2])
        assertEquals(nowWall + 60_000, out.last()[3])
    }
}

class AaosAccessTrackerTest {
    private val p = AaosProperty.PERF_VEHICLE_SPEED

    @Test
    fun `missing permission is permission_denied even when the list omits the property`() {
        val t = AaosAccessTracker()
        // Android omits unreadable properties, so not-listed + not-granted must NOT be reported as unsupported.
        assertEquals(AaosAction.Report(Availability.PERMISSION_DENIED), t.evaluate(p, granted = false, idKnown = true, listed = false))
    }

    @Test
    fun `a later grant with a refreshed list registers instead of staying unsupported forever`() {
        val t = AaosAccessTracker()
        assertEquals(AaosAction.Report(Availability.PERMISSION_DENIED), t.evaluate(p, false, true, false))
        // user grants CAR_SPEED; the re-fetched list now contains the property
        assertEquals(AaosAction.Register, t.evaluate(p, true, true, true))
        t.markRegistered(p)
        assertEquals(AaosAction.None, t.evaluate(p, true, true, true))
    }

    @Test
    fun `granted but absent from the fresh list is unsupported, with an unknown id too`() {
        val t = AaosAccessTracker()
        assertEquals(AaosAction.Report(Availability.UNSUPPORTED), t.evaluate(p, true, true, false))
        assertEquals(AaosAction.Report(Availability.UNSUPPORTED), t.evaluate(p, true, false, true))
    }

    @Test
    fun `revocation of a registered property unregisters it and reports permission_denied once`() {
        val t = AaosAccessTracker()
        t.markRegistered(p)
        assertEquals(AaosAction.Unregister(Availability.PERMISSION_DENIED), t.evaluate(p, false, true, false))
        assertTrue(!t.isRegistered(p))
        assertEquals(AaosAction.Report(Availability.PERMISSION_DENIED), t.evaluate(p, false, true, false))
        // re-grant re-registers
        assertEquals(AaosAction.Register, t.evaluate(p, true, true, true))
    }

    @Test
    fun `a registered property that disappears from the list is unregistered as unsupported`() {
        val t = AaosAccessTracker()
        t.markRegistered(p)
        assertEquals(AaosAction.Unregister(Availability.UNSUPPORTED), t.evaluate(p, true, true, false))
    }
}
