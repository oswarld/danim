package app.danim.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Test-only reader for the shared synthetic vehicle support pack
 * (`src/test/resources/virtual-vehicle-support/pack.json`, exported 2026-10-26 from the
 * vehicle simulator; see README.md next to it for hashes).
 *
 * This file lives in the test source set on purpose: production collection and upload never see it.
 * The pack's readings carry `source: "virtual_vehicle"`, which is **not** a device [Source]; the
 * adapter keeps that label as a plain string and never maps it to `pleos_vehicle_sdk` or
 * `aaos_car_property`. Tests that need an [Observation] bind one explicitly and only in memory.
 * `simulationEpoch` is the fixture clock: compare freshness against it, never against the host clock.
 */
object VirtualVehiclePack {
    const val RESOURCE = "/virtual-vehicle-support/pack.json"
    /** SHA-256 of the exported pack.json consumed here (also recorded in the resource README). */
    const val PACK_SHA256 = "1d387ad91005d2334900b470ab46a2b1b4b6e635229c521a03810aca69c27b48"
    const val READING_SOURCE = "virtual_vehicle"

    data class Profile(
        val id: String, val name: String, val brand: String, val powertrain: String,
        val hasBattery: Boolean, val hasFuel: Boolean, val canPlugIn: Boolean,
    )

    /** One snapshot reading exactly as the pack states it; [source] stays the pack's own label. */
    data class Reading(
        val signalKey: String, val signal: Signal?, val availability: Availability?, val value: Value?,
        val observedAtMs: Long, val source: String, val sampleId: String,
    )

    data class Context(val phase: String, val driverOccupancy: String, val speedKph: Double?, val scope: String)

    data class Snapshot(
        val synthetic: Boolean, val dataMode: String, val warning: String,
        val vehicleId: String, val displayName: String, val brand: String, val powertrain: String,
        val context: Context, val lastSeenAtMs: Long, val readings: Map<String, Reading>,
    ) {
        fun reading(signal: Signal): Reading = readings.getValue(signal.key)
    }

    data class Case(
        val id: String, val profileId: String, val scenarioId: String, val synthetic: Boolean, val dataMode: String,
        val evidenceClass: String, val source: String, val providerVehicleId: String, val configurePatch: JsonObject?,
        val stateAvailability: String, val stateObservedAtMs: Long, val snapshot: Snapshot,
    ) {
        val suffix: String get() = id.substringAfter('/')
        fun reading(signal: Signal): Reading = snapshot.reading(signal)
    }

    data class Frame(val action: JsonObject, val snapshot: Snapshot) {
        val actionType: String get() = action.getValue("type").jsonPrimitive.content
    }

    data class Replay(
        val id: String, val synthetic: Boolean, val source: String, val createRequest: JsonObject,
        val initialSnapshot: Snapshot, val frames: List<Frame>, val expectations: JsonObject,
    ) {
        /**
         * Device sampling view: a collector only observes the state current at each simulation instant, so
         * several zero-duration `configure` frames at one instant collapse to the last one.
         */
        fun sampledAtEachInstant(): List<Snapshot> =
            frames.groupBy { it.snapshot.lastSeenAtMs }.toSortedMap().values.map { it.last().snapshot }
    }

    data class Pack(
        val schemaVersion: Int, val synthetic: Boolean, val dataMode: String, val source: String,
        val evidenceClass: String, val simulationEpochMs: Long, val warning: String,
        val profiles: List<Profile>, val cases: List<Case>, val replays: List<Replay>, val sha256: String,
    ) {
        fun case(id: String): Case = cases.first { it.id == id }
        fun replay(id: String): Replay = replays.first { it.id == id }
        fun profile(id: String): Profile = profiles.first { it.id == id }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Pack {
        val bytes = requireNotNull(VirtualVehiclePack::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return Pack(
            schemaVersion = root.int("schemaVersion"),
            synthetic = root.bool("synthetic"),
            dataMode = root.str("dataMode"),
            source = root.str("source"),
            evidenceClass = root.str("evidenceClass"),
            simulationEpochMs = Iso.parse(root.str("simulationEpoch")),
            warning = root.str("warning"),
            profiles = root.getValue("profiles").jsonArray.map { profile(it.jsonObject) },
            cases = root.getValue("cases").jsonArray.map { case(it.jsonObject) },
            replays = root.getValue("replays").jsonArray.map { replay(it.jsonObject) },
            sha256 = sha,
        )
    }

    /** The pack value as the collector would hand it to a [ReadingSink]; the contract domain is checked by the factory. */
    fun value(element: JsonElement?): Value? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return Value.Text(p.content)
        p.booleanOrNull?.let { return Value.Bool(it) }
        return p.doubleOrNull?.let { Value.Num(it) }
    }

    /**
     * Hands every reading of [snapshot] to the production [ObservationFactory] exactly as a collector does.
     * [source] is the in-memory test binding; the pack's `virtual_vehicle` label is not representable on the device
     * wire and must not be reported as Pleos/AAOS evidence.
     */
    fun observations(snapshot: Snapshot, factory: ObservationFactory, source: Source): List<Observation> =
        Signal.entries.map { signal ->
            val r = snapshot.reading(signal)
            factory.fromReading(signal, requireNotNull(r.availability), r.value, r.observedAtMs, source)
        }

    private fun profile(o: JsonObject) = Profile(
        o.str("id"), o.str("name"), o.str("brand"), o.str("powertrain"),
        o.bool("hasBattery"), o.bool("hasFuel"), o.bool("canPlugIn"),
    )

    private fun reading(key: String, o: JsonObject) = Reading(
        signalKey = key,
        signal = Signal.fromKey(key),
        availability = Availability.fromWire(o.str("status")),
        value = value(o["value"]),
        observedAtMs = Iso.parse(o.str("observedAt")),
        source = o.str("source"),
        sampleId = o.str("sampleId"),
    )

    private fun snapshot(o: JsonObject): Snapshot {
        val vehicle = o.getValue("vehicle").jsonObject
        val context = o.getValue("context").jsonObject
        val status = o.getValue("status").jsonObject
        return Snapshot(
            synthetic = o.bool("synthetic"),
            dataMode = o.str("dataMode"),
            warning = o.str("warning"),
            vehicleId = vehicle.str("id"),
            displayName = vehicle.str("displayName"),
            brand = vehicle.str("brand"),
            powertrain = vehicle.str("powertrain"),
            context = Context(
                context.str("phase"), context.str("driverOccupancy"),
                (context["speedKph"] as? JsonPrimitive)?.doubleOrNull, context.str("scope"),
            ),
            lastSeenAtMs = Iso.parse(status.str("lastSeenAt")),
            readings = status.getValue("readings").jsonObject.mapValues { (key, value) -> reading(key, value.jsonObject) },
        )
    }

    private fun case(o: JsonObject): Case {
        val request = o.getValue("createRequest").jsonObject
        val state = o.getValue("state").jsonObject
        return Case(
            id = o.str("id"),
            profileId = request.str("profileId"),
            scenarioId = request.str("scenarioId"),
            synthetic = o.bool("synthetic"),
            dataMode = o.str("dataMode"),
            evidenceClass = o.str("evidenceClass"),
            source = o.str("source"),
            providerVehicleId = o.str("providerVehicleId"),
            configurePatch = o["configurePatch"]?.takeIf { it !is JsonNull }?.jsonObject,
            stateAvailability = state.str("availability"),
            stateObservedAtMs = Iso.parse(state.str("observedAt")),
            snapshot = snapshot(o.getValue("snapshot").jsonObject),
        )
    }

    private fun replay(o: JsonObject) = Replay(
        id = o.str("id"),
        synthetic = o.bool("synthetic"),
        source = o.str("source"),
        createRequest = o.getValue("createRequest").jsonObject,
        initialSnapshot = snapshot(o.getValue("initialSnapshot").jsonObject),
        frames = o.getValue("frames").jsonArray.map { f ->
            Frame(f.jsonObject.getValue("action").jsonObject, snapshot(f.jsonObject.getValue("snapshot").jsonObject))
        },
        expectations = o.getValue("expectations").jsonObject,
    )

    private fun JsonObject.str(key: String): String = requireNotNull(getValue(key).jsonPrimitive.contentOrNull) { key }
    private fun JsonObject.bool(key: String): Boolean = requireNotNull(getValue(key).jsonPrimitive.booleanOrNull) { key }
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()
}
