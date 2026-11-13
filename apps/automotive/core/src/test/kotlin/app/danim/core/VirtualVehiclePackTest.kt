package app.danim.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the production core (factory, gate, pipeline, session tracker, wire, retention) with the shared
 * synthetic support pack. Everything here is `synthetic_fixture` evidence: it proves how the device code
 * treats these values, not Pleos/AAOS compatibility, real-car behaviour or any OEM path.
 */
class VirtualVehiclePackTest {
    private val pack = VirtualVehiclePack.load()
    private val epoch = pack.simulationEpochMs
    private var n = 0
    private val factory = ObservationFactory { "virtual-${n++}" }
    private val binding = Source.PLEOS_VEHICLE_SDK // in-memory test binding only, see VirtualVehiclePack

    private fun observations(s: VirtualVehiclePack.Snapshot) = VirtualVehiclePack.observations(s, factory, binding)
    private fun num(o: Observation) = (o.value as Value.Num).v
    private fun text(o: Observation) = (o.value as Value.Text).v
    private fun <T> transitions(values: List<T>): List<T> = values.filterIndexed { i, v -> i == 0 || values[i - 1] != v }

    @Test
    fun `pack is the labelled synthetic bundle and nothing else`() {
        assertEquals(VirtualVehiclePack.PACK_SHA256, pack.sha256)
        assertEquals(1, pack.schemaVersion)
        assertTrue(pack.synthetic)
        assertEquals("demo", pack.dataMode)
        assertEquals("synthetic_fixture", pack.evidenceClass)
        assertEquals(VirtualVehiclePack.READING_SOURCE, pack.source)
        assertEquals(Iso.parse("2026-10-26T00:00:00.000Z"), epoch)
        assertEquals(6, pack.profiles.size)
        assertEquals(52, pack.cases.size)
        assertEquals(2, pack.replays.size)
        assertEquals(52, pack.cases.map { it.id }.toSet().size)
        assertEquals(52, pack.cases.map { it.snapshot.vehicleId }.toSet().size, "virtual ids are distinct")
        for (c in pack.cases) {
            assertTrue(c.synthetic && c.snapshot.synthetic, c.id)
            assertEquals("demo", c.dataMode, c.id)
            assertEquals("demo", c.snapshot.dataMode, c.id)
            assertEquals("synthetic_fixture", c.evidenceClass, c.id)
            assertTrue(c.providerVehicleId.startsWith("synthetic-"), c.id)
            assertTrue(c.snapshot.displayName.startsWith("[VIRTUAL] "), c.id)
            assertTrue(c.snapshot.warning.contains("Never upload to production"), c.id)
        }
        assertEquals(setOf("bev", "ice", "hev", "phev"), pack.profiles.map { it.powertrain }.toSet())
    }

    @Test
    fun `virtual provenance is not a device source and every reading key is a v1 signal`() {
        assertNull(Source.fromWire(VirtualVehiclePack.READING_SOURCE), "virtual data cannot ride the device wire as Pleos/AAOS")
        for (c in pack.cases) {
            assertEquals(Signal.entries.map { it.key }.toSet(), c.snapshot.readings.keys, c.id)
            for (r in c.snapshot.readings.values) {
                assertEquals(VirtualVehiclePack.READING_SOURCE, r.source, c.id)
                assertNotNull(r.signal, r.signalKey)
                assertNotNull(r.availability, "${c.id} ${r.signalKey}")
                assertEquals(c.snapshot.lastSeenAtMs, r.observedAtMs, c.id)
            }
        }
        // occupancy and speed are simulation context, never v1 telemetry
        assertNull(Signal.fromKey("driver_occupancy"))
        assertNull(Signal.fromKey("speed_kph"))
    }

    @Test
    fun `every pack reading passes the production factory unchanged - nothing is coerced or zero-filled`() {
        var availableZero = 0
        for (c in pack.cases) {
            for (o in observations(c.snapshot)) {
                val r = c.reading(o.signal)
                assertEquals(r.availability, o.availability, "${c.id} ${o.signal}")
                assertEquals(r.value, o.value, "${c.id} ${o.signal}")
                assertEquals(r.observedAtMs, o.observedAtMs, "${c.id} ${o.signal}")
                assertEquals(binding, o.source)
                if (o.availability != Availability.AVAILABLE) assertNull(o.value, "${c.id} ${o.signal}: unknown is never zero")
                if (o.value == Value.Num(0.0)) availableZero++
            }
        }
        assertTrue(availableZero >= 4, "available-zero cases keep measured zero: $availableZero")
    }

    @Test
    fun `energy levels 80 30 5 cover BEV ICE HEV PHEV with unsupported never reported as zero`() {
        for (p in pack.profiles) {
            for ((scenario, level) in listOf("drive-sufficient" to 80.0, "drive-30-percent" to 30.0, "drive-critical" to 5.0)) {
                val obs = observations(pack.case("${p.id}/$scenario").snapshot).associateBy { it.signal }
                val soc = obs.getValue(Signal.BATTERY_SOC_PERCENT)
                val fuel = obs.getValue(Signal.FUEL_LEVEL_PERCENT)
                val charging = obs.getValue(Signal.CHARGING_STATE)
                val evEff = obs.getValue(Signal.EV_EFFICIENCY)
                val fuelEff = obs.getValue(Signal.FUEL_EFFICIENCY)
                if (p.hasBattery) assertEquals(level, num(soc), "${p.id}/$scenario battery")
                else assertEquals(Availability.UNSUPPORTED to null, soc.availability to soc.value, "${p.id} ICE battery")
                when {
                    !p.hasFuel -> assertEquals(Availability.UNSUPPORTED to null, fuel.availability to fuel.value, "${p.id} BEV fuel")
                    p.hasBattery -> assertEquals(75.0, num(fuel), "${p.id}/$scenario HEV/PHEV fuel is independent of its battery")
                    else -> assertEquals(level, num(fuel), "${p.id}/$scenario ICE fuel")
                }
                if (p.canPlugIn) assertEquals("discharging", text(charging), "${p.id} plug-in charging state")
                else assertEquals(Availability.UNSUPPORTED to null, charging.availability to charging.value, "${p.id} external charging unsupported")
                assertEquals(if (p.hasBattery) Availability.AVAILABLE else Availability.UNSUPPORTED, evEff.availability, p.id)
                assertEquals(if (p.hasFuel) Availability.AVAILABLE else Availability.UNSUPPORTED, fuelEff.availability, p.id)
                assertTrue(num(obs.getValue(Signal.RANGE_KM)) > 0.0)
                assertEquals("on", text(obs.getValue(Signal.IGNITION_STATE)))
                assertEquals("moving", text(obs.getValue(Signal.VEHICLE_MOTION)))
            }
            // low fuel moves only the fuel reading on fuelled profiles
            val low = observations(pack.case("${p.id}/drive-low-fuel").snapshot).associateBy { it.signal }
            if (p.hasFuel) assertEquals(5.0, num(low.getValue(Signal.FUEL_LEVEL_PERCENT)), p.id)
            if (p.hasBattery) assertEquals(80.0, num(low.getValue(Signal.BATTERY_SOC_PERCENT)), "${p.id} battery untouched by low fuel")
        }
        val hev = pack.profile("kia-sportage-hev")
        assertTrue(hev.hasBattery && hev.hasFuel && !hev.canPlugIn)
        val phev = pack.profile("generic-phev")
        assertTrue(phev.hasBattery && phev.hasFuel && phev.canPlugIn)
    }

    @Test
    fun `measured zero stays available while unavailable denied and error cases carry null with their status`() {
        for (profile in listOf("hyundai-ioniq5", "kia-ev6")) {
            val zero = observations(pack.case("$profile/available-zero").snapshot).associateBy { it.signal }
            assertEquals(Availability.AVAILABLE, zero.getValue(Signal.BATTERY_SOC_PERCENT).availability)
            assertEquals(0.0, num(zero.getValue(Signal.BATTERY_SOC_PERCENT)))
            assertEquals(0.0, num(zero.getValue(Signal.RANGE_KM)))
            assertEquals("unknown", text(zero.getValue(Signal.VEHICLE_MOTION)), "zero speed alone is not parked")
            assertEquals(0.0, pack.case("$profile/available-zero").snapshot.context.speedKph)

            for ((suffix, expected) in listOf(
                "unavailable" to Availability.UNAVAILABLE,
                "permission-denied" to Availability.PERMISSION_DENIED,
                "source-error" to Availability.ERROR,
            )) {
                val c = pack.case("$profile/$suffix")
                assertEquals(expected.wire, c.stateAvailability)
                for (o in observations(c.snapshot)) {
                    assertNull(o.value, "${c.id} ${o.signal}")
                    val baseline = pack.case("$profile/drive-sufficient").reading(o.signal).availability
                    when (baseline) {
                        Availability.UNSUPPORTED -> assertEquals(Availability.UNSUPPORTED, o.availability, "${c.id} ${o.signal} stays unsupported")
                        Availability.PERMISSION_DENIED -> assertEquals(Availability.PERMISSION_DENIED, o.availability, "${c.id} ${o.signal} stays denied")
                        else -> assertEquals(expected, o.availability, "${c.id} ${o.signal}")
                    }
                }
            }
            // asleep: the supported readings are unavailable, never a frozen or zero value
            for (o in observations(pack.case("$profile/parked-asleep").snapshot)) {
                assertNull(o.value)
                assertTrue(o.availability in setOf(Availability.UNAVAILABLE, Availability.UNSUPPORTED, Availability.PERMISSION_DENIED), "${o.signal}")
            }
        }
        // on the wire a measured zero is 0 and an unavailable reading is null
        val zero = observations(pack.case("kia-ev6/available-zero").snapshot).first { it.signal == Signal.BATTERY_SOC_PERCENT }
        val missing = observations(pack.case("kia-ev6/unavailable").snapshot).first { it.signal == Signal.BATTERY_SOC_PERCENT }
        val events = TelemetryWire.parseObject(TelemetryWire.encode("virtual-test-device", listOf(zero, missing)))!!["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(0.0, events[0]["value"]!!.jsonPrimitive.double)
        assertEquals("available", events[0]["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, events[1]["value"])
        assertEquals("unavailable", events[1]["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `occupancy before and after entry is simulation context and changes no v1 reading`() {
        for (p in pack.profiles) {
            val before = pack.case("${p.id}/parked-before-entry")
            val after = pack.case("${p.id}/parked-after-entry")
            assertEquals("empty", before.snapshot.context.driverOccupancy)
            assertEquals("occupied", after.snapshot.context.driverOccupancy)
            assertEquals(0.0, before.snapshot.context.speedKph)
            assertEquals(0.0, after.snapshot.context.speedKph)
            val strip = { s: VirtualVehiclePack.Snapshot -> s.readings.mapValues { (_, r) -> Triple(r.availability, r.value, r.observedAtMs) } }
            assertEquals(strip(before.snapshot), strip(after.snapshot), "${p.id}: entering the car is not a telemetry change")
            val obs = observations(after.snapshot).associateBy { it.signal }
            assertEquals("off", text(obs.getValue(Signal.IGNITION_STATE)))
            assertEquals("parked", text(obs.getValue(Signal.VEHICLE_MOTION)))
            if (p.canPlugIn) assertEquals("idle", text(obs.getValue(Signal.CHARGING_STATE)))
            val asleep = pack.case("${p.id}/parked-asleep").snapshot.context
            assertEquals("unknown", asleep.driverOccupancy)
            assertNull(asleep.speedKph)
        }
    }

    @Test
    fun `stale snapshot keeps its own observation time and never replaces a fresh reading`() {
        for (profile in listOf("hyundai-ioniq5", "kia-ev6")) {
            val stale = pack.case("$profile/stale")
            val fresh = pack.case("$profile/drive-sufficient")
            assertEquals(86_400_000L, epoch - stale.snapshot.lastSeenAtMs, "one day before the simulation epoch")
            assertEquals(stale.stateObservedAtMs, stale.snapshot.lastSeenAtMs)
            val staleObs = observations(stale.snapshot)
            val freshObs = observations(fresh.snapshot)
            staleObs.forEach { assertEquals(epoch - 86_400_000L, it.observedAtMs, "observedAt is the vehicle's time, not now") }

            val freshFirst = SignalGate()
            freshObs.forEach { assertTrue(freshFirst.shouldEmit(it)) }
            staleObs.forEach { assertFalse(freshFirst.shouldEmit(it), "${it.signal}: a late snapshot never replaces newer state") }

            val staleFirst = SignalGate()
            staleObs.forEach { assertTrue(staleFirst.shouldEmit(it)) }
            freshObs.forEach { assertTrue(staleFirst.shouldEmit(it), "${it.signal}: the fresh reading supersedes the stale one") }
        }
    }

    @Test
    fun `replaying fixed snapshots against a later wall clock only ages them out of local retention`() = runTest {
        val store = InMemoryOutbox()
        val stale = observations(pack.case("hyundai-ioniq5/stale").snapshot)
        store.insert(stale, epoch, DEV)
        assertEquals(0, RetentionPolicy().apply(store, epoch), "one day old is inside the seven-day local retention")
        assertEquals(stale.size, store.count(DEV))
        val eightDaysLater = epoch + 8L * 24 * 3600 * 1000
        assertEquals(stale.size, RetentionPolicy().apply(store, eightDaysLater), "discarded rows are counted, never re-dated")
        assertEquals(0, store.count(DEV))
    }

    @Test
    fun `drive and charge replay persists a 4_5 km drive and SOC 20 to 25 without invented observations`() = runTest {
        val replay = pack.replay("observed-drive-and-charge")
        assertTrue(replay.synthetic)
        assertEquals(VirtualVehiclePack.READING_SOURCE, replay.source)
        val samples = replay.sampledAtEachInstant()
        assertEquals(listOf(0L, 300_000L, 600_000L), samples.map { it.lastSeenAtMs - epoch })
        assertEquals(listOf("driving", "charging", "parked_empty"), samples.map { it.context.phase })

        val store = InMemoryOutbox()
        val channel = Channel<Observation>(Channel.UNLIMITED)
        val pipeline = ObservationPipeline(store, SignalGate(), DEV, clock = { epoch })
        val consumer = launch { pipeline.consume(channel) }
        for (s in samples) observations(s).forEach { channel.send(it) }
        channel.close()
        consumer.join()

        val rows = store.nextBatch(Int.MAX_VALUE, DEV).map { it.observation }
        fun series(signal: Signal) = rows.filter { it.signal == signal }.sortedBy { it.observedAtMs }
        val odometer = series(Signal.ODOMETER_KM).map(::num)
        // the unchanged odometer at +600 s is re-emitted as the five-minute heartbeat, not as new distance
        assertEquals(listOf(12000.0, 12004.5, 12004.5), odometer)
        assertEquals(listOf(12000.0, 12004.5), odometer.distinct())
        assertEquals(replay.expectations["distanceKm"]!!.jsonPrimitive.double, odometer.last() - odometer.first(), 1e-9)
        val soc = series(Signal.BATTERY_SOC_PERCENT).map(::num)
        assertEquals(listOf(80.0, 20.0, 25.0), soc)
        assertEquals(replay.expectations["startSocPercent"]!!.jsonPrimitive.double, soc[1])
        assertEquals(replay.expectations["endSocPercent"]!!.jsonPrimitive.double, soc.last())
        assertEquals(listOf("discharging", "charging", "idle"), series(Signal.CHARGING_STATE).map(::text))
        // enum readings repeat on the heartbeat too; the transitions are what the replay asserts
        assertEquals(listOf("on", "off"), transitions(series(Signal.IGNITION_STATE).map(::text)))
        assertEquals(listOf("moving", "parked"), transitions(series(Signal.VEHICLE_MOTION).map(::text)))
        // nothing between the sampled instants: the pipeline never interpolates a trip or a charge
        assertTrue(rows.all { (it.observedAtMs - epoch) in setOf(0L, 300_000L, 600_000L) })
        // unmeasured energy stays unmeasured: there is no energy or fuel-volume signal to invent
        assertEquals(JsonNull, replay.expectations["energyKwh"])
        assertEquals(JsonNull, replay.expectations["fuelLiters"])
        assertNull(Signal.fromKey("energy_kwh"))
        series(Signal.FUEL_LEVEL_PERCENT).forEach { assertEquals(Availability.UNSUPPORTED to null, it.availability to it.value) }
        // every persisted row keeps its event id and the in-memory binding, nothing from the pack's vehicle identity
        assertTrue(rows.none { it.eventId.contains(samples[0].vehicleId) })
    }

    @Test
    fun `sleep gap stays an explicit unobserved interval and recovery resumes without bridging it`() = runTest {
        val replay = pack.replay("sleep-gap-recovery")
        val frames = replay.frames
        assertEquals(listOf("configure", "configure", "advance", "configure"), frames.map { it.actionType })
        val awake = frames[0].snapshot // driving at the epoch, before the head unit sleeps
        val asleep = listOf(frames[1].snapshot, frames[2].snapshot)
        val recovered = frames[3].snapshot // parked, occupied, ten minutes later
        val gapMs = replay.expectations["gapSeconds"]!!.jsonPrimitive.double.toLong() * 1000
        assertEquals(600_000L, gapMs)
        assertEquals(gapMs, recovered.lastSeenAtMs - awake.lastSeenAtMs)
        assertEquals("partial", replay.expectations["historyCompleteness"]!!.jsonPrimitive.content)

        // while asleep the simulator reports unavailable, never a frozen or zero value
        for (s in asleep) for (o in observations(s)) {
            assertNull(o.value, "${o.signal}")
            assertTrue(o.availability != Availability.AVAILABLE)
        }

        // the collector heartbeat: last beat at the epoch, next beat after the sleep -> the delayed interval is recorded
        val sessions = FakeSessionStore()
        val tracker = SessionTracker(sessions)
        assertNull(tracker.begin(awake.lastSeenAtMs))
        val gap = tracker.beat(recovered.lastSeenAtMs)
        assertEquals(UnobservedGap(awake.lastSeenAtMs, recovered.lastSeenAtMs, "heartbeat_delayed"), gap)
        assertEquals(gapMs, gap!!.durationMs)
        assertEquals(listOf(gap), sessions.gaps)

        // observations: the pre-sleep sample and the recovery sample only; the gate re-emits the unchanged odometer as a heartbeat
        val store = InMemoryOutbox()
        val channel = Channel<Observation>(Channel.UNLIMITED)
        val consumer = launch { ObservationPipeline(store, SignalGate(), DEV, clock = { epoch }).consume(channel) }
        observations(awake).forEach { channel.send(it) }
        observations(recovered).forEach { channel.send(it) }
        channel.close()
        consumer.join()
        val rows = store.nextBatch(Int.MAX_VALUE, DEV).map { it.observation }
        val odometer = rows.filter { it.signal == Signal.ODOMETER_KM }.sortedBy { it.observedAtMs }
        assertEquals(listOf(12000.0, 12000.0), odometer.map(::num))
        assertEquals(replay.expectations["finalOdometerKm"]!!.jsonPrimitive.double, num(odometer.last()))
        assertEquals(listOf("on", "off"), rows.filter { it.signal == Signal.IGNITION_STATE }.sortedBy { it.observedAtMs }.map(::text))
        assertEquals(listOf("moving", "parked"), rows.filter { it.signal == Signal.VEHICLE_MOTION }.sortedBy { it.observedAtMs }.map(::text))
        assertTrue(rows.none { it.observedAtMs > awake.lastSeenAtMs && it.observedAtMs < recovered.lastSeenAtMs }, "no observation is invented inside the gap")
        assertEquals(setOf(awake.lastSeenAtMs, recovered.lastSeenAtMs), rows.map { it.observedAtMs }.toSet())
    }

    @Test
    fun `source error and permission loss are status transitions that emit and recover`() {
        val gate = SignalGate()
        val fresh = observations(pack.case("kia-ev6/drive-sufficient").snapshot)
        // one minute apart: beyond every per-signal change rate limit, so only the status decides what emits
        val error = observations(pack.case("kia-ev6/source-error").snapshot).map { it.copy(observedAtMs = it.observedAtMs + 60_000) }
        val denied = observations(pack.case("kia-ev6/permission-denied").snapshot).map { it.copy(observedAtMs = it.observedAtMs + 120_000) }
        val back = observations(pack.case("kia-ev6/drive-sufficient").snapshot).map { it.copy(observedAtMs = it.observedAtMs + 180_000) }
        fresh.forEach { assertTrue(gate.shouldEmit(it)) }
        for (o in error) assertEquals(o.availability == Availability.ERROR, gate.shouldEmit(o), "${o.signal}: only the readings that changed status emit")
        for (o in denied) assertEquals(o.availability == Availability.PERMISSION_DENIED && pack.case("kia-ev6/source-error").reading(o.signal).availability != Availability.PERMISSION_DENIED, gate.shouldEmit(o), "${o.signal}")
        for (o in back) {
            val changed = pack.case("kia-ev6/permission-denied").reading(o.signal).availability != o.availability
            assertEquals(changed, gate.shouldEmit(o), "${o.signal}: recovery emits exactly where the status changed")
        }
    }
}
