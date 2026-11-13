package app.danim.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalGateTest {
    private fun o(at: Long, v: Double, s: Signal = Signal.ODOMETER_KM) = obs(at, s, v)

    @Test
    fun `motion re-reports within four minutes so a five-minute continuity check holds`() {
        val g = SignalGate()
        fun motion(at: Long) = Observation("m$at", at, Signal.VEHICLE_MOTION, Availability.AVAILABLE, Value.Text("parked"), Source.AAOS_CAR_PROPERTY)
        assertTrue(g.shouldEmit(motion(0)))
        assertFalse(g.shouldEmit(motion(180_000)))
        assertTrue(g.shouldEmit(motion(240_000)))
        // Other signals keep the five-minute heartbeat.
        fun soc(at: Long) = Observation("s$at", at, Signal.BATTERY_SOC_PERCENT, Availability.AVAILABLE, Value.Num(80.0))
        assertTrue(g.shouldEmit(soc(0)))
        assertFalse(g.shouldEmit(soc(240_000)))
        assertTrue(g.shouldEmit(soc(300_000)))
    }

    @Test
    fun `first reading emits and unchanged readings only emit on heartbeat`() {
        val g = SignalGate(heartbeatMs = 300_000)
        assertTrue(g.shouldEmit(o(0, 10.0)))
        assertFalse(g.shouldEmit(o(100_000, 10.0)))
        assertFalse(g.shouldEmit(o(299_999, 10.0)))
        assertTrue(g.shouldEmit(o(300_000, 10.0)))
    }

    @Test
    fun `changes are rate limited per signal`() {
        val g = SignalGate()
        assertTrue(g.shouldEmit(o(0, 10.0)))
        assertFalse(g.shouldEmit(o(10_000, 10.1)))
        assertTrue(g.shouldEmit(o(30_000, 10.2)))
        // other signals are independent
        assertTrue(g.shouldEmit(o(10_000, 50.0, Signal.BATTERY_SOC_PERCENT)))
    }

    @Test
    fun `late or duplicate delivery never replaces newer state`() {
        val g = SignalGate()
        assertTrue(g.shouldEmit(o(100_000, 10.0)))
        assertFalse(g.shouldEmit(o(50_000, 99.0)))
        assertFalse(g.shouldEmit(o(100_000, 99.0)))
    }

    @Test
    fun `status transitions emit even when the value stays null`() {
        val g = SignalGate()
        val unsupported = Observation("a", 0, Signal.CHARGING_STATE, Availability.UNSUPPORTED, null)
        val denied = Observation("b", 1, Signal.CHARGING_STATE, Availability.PERMISSION_DENIED, null)
        assertTrue(g.shouldEmit(unsupported))
        assertTrue(g.shouldEmit(denied))
    }
}

class ConversionsTest {
    @Test
    fun `km per kWh converts to kWh per 100 km only for positive finite values`() {
        assertEquals(20.0, Conversions.kmPerKwhToKwhPer100km(5.0f)!!, 1e-9)
        assertEquals(12.5, Conversions.kmPerKwhToKwhPer100km(8.0f)!!, 1e-9)
        assertNull(Conversions.kmPerKwhToKwhPer100km(0.0f)) // never infinity
        assertNull(Conversions.kmPerKwhToKwhPer100km(-3.0f))
        assertNull(Conversions.kmPerKwhToKwhPer100km(Float.NaN))
        assertNull(Conversions.kmPerKwhToKwhPer100km(Float.POSITIVE_INFINITY))
        assertNull(Conversions.kmPerKwhToKwhPer100km(null))
        // result must satisfy the contract domain
        assertTrue(ObservationFactory.isCanonical(Signal.EV_EFFICIENCY, Value.Num(Conversions.kmPerKwhToKwhPer100km(6.5f)!!)))
    }

    @Test
    fun `fuel economy is passed through unconverted`() {
        assertEquals(6.5, Conversions.fuelEconomyLPer100km(6.5f)!!, 1e-6)
        assertEquals(0.0, Conversions.fuelEconomyLPer100km(0.0f)!!, 0.0)
        assertNull(Conversions.fuelEconomyLPer100km(-0.1f))
        assertNull(Conversions.fuelEconomyLPer100km(Float.NaN))
        assertNull(Conversions.fuelEconomyLPer100km(null))
    }
}

class FakeSessionStore : SessionStore {
    val rows = mutableListOf<SessionRecord>()
    val gaps = mutableListOf<UnobservedGap>()
    override suspend fun latest() = rows.lastOrNull()
    override suspend fun insert(startedAtMs: Long): Long {
        rows += SessionRecord(rows.size + 1L, startedAtMs, startedAtMs, null, null)
        return rows.size.toLong()
    }
    override suspend fun heartbeat(id: Long, atMs: Long) {
        val i = rows.indexOfFirst { it.id == id }
        rows[i] = rows[i].copy(lastHeartbeatMs = atMs)
    }
    override suspend fun end(id: Long, atMs: Long, reason: String) {
        val i = rows.indexOfFirst { it.id == id }
        rows[i] = rows[i].copy(endedAtMs = atMs, endReason = reason)
    }
    override suspend fun recentGaps(limit: Int) = gaps.takeLast(limit)
    override suspend fun saveGap(gap: UnobservedGap) { gaps += gap }
}

class SessionTrackerTest {
    @Test
    fun `first start has no gap`() = runTest {
        assertNull(SessionTracker(FakeSessionStore()).begin(1_000))
    }

    @Test
    fun `unclean end is reported as a gap from the last heartbeat`() = runTest {
        val store = FakeSessionStore()
        val t = SessionTracker(store)
        t.begin(0)
        t.beat(120_000)
        // process killed / force-stopped / rebooted: no end() call
        val gap = SessionTracker(store).begin(600_000)
        assertNotNull(gap)
        assertEquals(UnobservedGap(120_000, 600_000, "previous_session_not_closed"), gap)
        assertEquals(1, store.gaps.size)
    }

    @Test
    fun `explicit stop is recorded with its reason and a later restart shows the stopped interval`() = runTest {
        val store = FakeSessionStore()
        val t = SessionTracker(store)
        t.begin(0)
        t.end(10_000, EndReason.USER_STOP)
        val gap = SessionTracker(store).begin(400_000)
        assertEquals(UnobservedGap(10_000, 400_000, "stopped:user_stop"), gap)
    }

    @Test
    fun `quick restart under the threshold is not a gap`() = runTest {
        val store = FakeSessionStore()
        val t = SessionTracker(store, minGapMs = 60_000)
        t.begin(0)
        t.beat(30_000)
        assertNull(SessionTracker(store, 60_000).begin(60_000))
    }
}

class SessionTrackerSuspendGapTest {
    @Test
    fun `delayed heartbeat in the same process records the gap before overwriting`() = runTest {
        val store = FakeSessionStore()
        val t = SessionTracker(store)
        t.begin(0)
        assertNull(t.beat(60_000))
        assertNull(t.beat(120_000))
        // device suspended: next beat arrives 2 hours later
        val gap = t.beat(120_000 + 7_200_000)
        assertEquals(UnobservedGap(120_000, 7_320_000, "heartbeat_delayed"), gap)
        assertEquals(listOf(gap), store.gaps)
        assertEquals(7_320_000, store.rows.single().lastHeartbeatMs) // overwritten only after the gap was saved
        assertNull(t.beat(7_380_000))
        assertEquals(1, store.gaps.size)
    }

    @Test
    fun `normal jitter below the threshold is not a gap`() = runTest {
        val t = SessionTracker(FakeSessionStore())
        t.begin(0)
        assertNull(t.beat(100_000))
        assertNull(t.beat(100_000 + 179_999))
    }

    @Test
    fun `beat before begin is a no-op`() = runTest {
        assertNull(SessionTracker(FakeSessionStore()).beat(1_000_000))
    }
}

class ObservationPipelineTest {
    @Test
    fun `each arriving observation is persisted before the next one is awaited`() = runTest {
        val store = InMemoryOutbox()
        val channel = kotlinx.coroutines.channels.Channel<Observation>(100)
        val job = launch { ObservationPipeline(store, SignalGate(), DEV, { 5L }).consume(channel) }
        channel.send(obs(1, id = "a"))
        testScheduler.runCurrent()
        assertEquals(listOf("a"), store.ids()) // durable without waiting for any later callback or timer
        channel.send(obs(2, Signal.RANGE_KM, id = "b"))
        testScheduler.runCurrent()
        assertEquals(listOf("a", "b"), store.ids())
        channel.close()
        job.join()
    }

    @Test
    fun `closing drains everything already queued before returning`() = runTest {
        val store = InMemoryOutbox()
        val channel = kotlinx.coroutines.channels.Channel<Observation>(100)
        val signals = Signal.entries
        signals.forEachIndexed { i, s -> channel.trySend(obs(1L + i, s, id = "id$i")) }
        channel.close()
        ObservationPipeline(store, SignalGate(), DEV, { 5L }).consume(channel)
        assertEquals(signals.indices.map { "id$it" }.toSet(), store.ids().toSet())
    }

    @Test
    fun `gated duplicates are not stored and persisted batches are reported`() = runTest {
        val store = InMemoryOutbox()
        val persisted = mutableListOf<String>()
        val channel = kotlinx.coroutines.channels.Channel<Observation>(100)
        channel.trySend(obs(1_000, id = "a"))
        channel.trySend(obs(2_000, id = "dup")) // same value 1s later: below heartbeat, filtered
        channel.close()
        ObservationPipeline(store, SignalGate(), DEV, { 5L }) { persisted += it.map { o -> o.eventId } }.consume(channel)
        assertEquals(listOf("a"), store.ids())
        assertEquals(listOf("a"), persisted)
    }

    @Test
    fun `cancellation while inserting does not lose the batch`() = runTest {
        val inserted = mutableListOf<String>()
        val slow = object : OutboxStore by InMemoryOutbox() {
            override suspend fun insert(observations: List<Observation>, nowMs: Long, binding: String) {
                kotlinx.coroutines.delay(1_000)
                inserted += observations.map { it.eventId }
            }
        }
        val channel = kotlinx.coroutines.channels.Channel<Observation>(10)
        val job = launch { ObservationPipeline(slow, SignalGate(), DEV).consume(channel) }
        channel.send(obs(1, id = "a"))
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf("a"), inserted)
    }
}
