package app.danim.core

import java.time.Instant
import java.time.OffsetDateTime

/** Signal keys and canonical value domains from docs/api-contract.md (API v1). */
enum class Signal(val key: String) {
    IGNITION_STATE("ignition_state"),
    VEHICLE_MOTION("vehicle_motion"),
    ODOMETER_KM("odometer_km"),
    BATTERY_SOC_PERCENT("battery_soc_percent"),
    CHARGING_STATE("charging_state"),
    FUEL_LEVEL_PERCENT("fuel_level_percent"),
    RANGE_KM("range_km"),
    EV_EFFICIENCY("ev_efficiency_kwh_per_100km"),
    FUEL_EFFICIENCY("fuel_efficiency_l_per_100km"),
    TIRE_LOW_PRESSURE("tire_low_pressure"),
    VEHICLE_WARNING("vehicle_warning");

    companion object {
        fun fromKey(key: String): Signal? = entries.firstOrNull { it.key == key }
    }
}

enum class Availability(val wire: String) {
    AVAILABLE("available"),
    UNSUPPORTED("unsupported"),
    PERMISSION_DENIED("permission_denied"),
    UNAVAILABLE("unavailable"),
    ERROR("error");

    companion object {
        fun fromWire(wire: String): Availability? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where a reading came from. Fixed at creation; retries re-send the same source with the same event id. */
enum class Source(val wire: String) {
    PLEOS_VEHICLE_SDK("pleos_vehicle_sdk"),
    AAOS_CAR_PROPERTY("aaos_car_property");

    companion object {
        fun fromWire(wire: String): Source? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a collector reports for one reading: signal, status, canonical-candidate value, observed time, origin. */
typealias ReadingSink = (Signal, Availability, Value?, Long, Source) -> Unit

sealed interface Value {
    data class Num(val v: Double) : Value
    data class Text(val v: String) : Value
    data class Bool(val v: Boolean) : Value
}

/**
 * One persisted vehicle observation. [eventId] is assigned once at creation and survives
 * every retry, which is what makes server-side deduplication safe. [observedAtMs] is when the
 * vehicle reported the value, never the upload time. A missing reading is [value] == null
 * with a non-available status: unknown is never zero.
 */
data class Observation(
    val eventId: String,
    val observedAtMs: Long,
    val signal: Signal,
    val availability: Availability,
    val value: Value?,
    val source: Source = Source.PLEOS_VEHICLE_SDK,
) {
    init {
        require((availability == Availability.AVAILABLE) == (value != null)) {
            "value must be present exactly when availability is available"
        }
    }
}

object Iso {
    fun format(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
    // Older Android ISO_INSTANT parsers require Z; database timestamps include numeric offsets.
    fun parse(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()
}

/** Builds contract-valid observations; anything outside the canonical domain becomes `unavailable`. */
class ObservationFactory(
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun missing(
        signal: Signal, availability: Availability, observedAtMs: Long,
        source: Source = Source.PLEOS_VEHICLE_SDK,
    ): Observation {
        require(availability != Availability.AVAILABLE) { "use value()" }
        return Observation(newId(), observedAtMs, signal, availability, null, source)
    }

    /** Validates [value] against the signal's domain; invalid input is stored as unavailable, not coerced. */
    fun value(
        signal: Signal, value: Value, observedAtMs: Long,
        source: Source = Source.PLEOS_VEHICLE_SDK,
    ): Observation =
        if (isCanonical(signal, value)) {
            Observation(newId(), observedAtMs, signal, Availability.AVAILABLE, value, source)
        } else {
            Observation(newId(), observedAtMs, signal, Availability.UNAVAILABLE, null, source)
        }

    /** Single entry point for a [ReadingSink] reading: validates, and downgrades impossible input to unavailable. */
    fun fromReading(
        signal: Signal, availability: Availability, value: Value?, observedAtMs: Long, source: Source,
    ): Observation =
        if (availability == Availability.AVAILABLE && value != null) value(signal, value, observedAtMs, source)
        else missing(signal, if (availability == Availability.AVAILABLE) Availability.UNAVAILABLE else availability, observedAtMs, source)

    companion object {
        const val MAX_WARNING_LENGTH = 200
        private val IGNITION = setOf("on", "off", "unknown")
        private val MOTION = setOf("moving", "parked", "unknown")
        private val CHARGING = setOf("charging", "discharging", "idle", "complete", "unknown")

        fun isCanonical(signal: Signal, value: Value): Boolean = when (signal) {
            Signal.IGNITION_STATE -> value is Value.Text && value.v in IGNITION
            Signal.VEHICLE_MOTION -> value is Value.Text && value.v in MOTION
            Signal.CHARGING_STATE -> value is Value.Text && value.v in CHARGING
            Signal.ODOMETER_KM, Signal.RANGE_KM, Signal.EV_EFFICIENCY, Signal.FUEL_EFFICIENCY ->
                value is Value.Num && value.v.isFinite() && value.v >= 0.0
            Signal.BATTERY_SOC_PERCENT, Signal.FUEL_LEVEL_PERCENT ->
                value is Value.Num && value.v.isFinite() && value.v in 0.0..100.0
            Signal.TIRE_LOW_PRESSURE -> value is Value.Bool
            Signal.VEHICLE_WARNING -> value is Value.Text && value.v.length <= MAX_WARNING_LENGTH
        }
    }
}
