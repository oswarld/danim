package app.danim.core

/**
 * Pure mapping/fusion for the standard AAOS `CarPropertyManager` source (properties from
 * android.car.VehiclePropertyIds). The reflective Android layer only extracts raw values; every decision
 * about units, freshness, status and meaning lives here so it is unit-tested without a Car library.
 */
enum class AaosProperty { IGNITION_STATE, PERF_VEHICLE_SPEED, GEAR_SELECTION, EV_CHARGE_STATE, RANGE_REMAINING }

/**
 * One property update. [number] for float properties (speed m/s, range m); [enumName] for enum-valued ones, resolved by
 * the reflective layer from the platform's own constant names (VehicleIgnitionState / EvChargeState / VehicleGear).
 * [timestampNanos] is CarPropertyValue.getTimestamp(): nanoseconds on the `SystemClock.elapsedRealtimeNanos()` base.
 */
data class AaosEvent(
    val property: AaosProperty,
    val number: Double? = null,
    val enumName: String? = null,
    val status: Int,
    val timestampNanos: Long,
)

object AaosMapping {
    const val STATUS_AVAILABLE = 0
    const val STATUS_UNAVAILABLE = 1
    const val STATUS_ERROR = 2

    /** ON/START -> on; OFF/LOCK -> off; ACC, UNDEFINED and anything unrecognised -> unknown. */
    fun ignition(name: String?): String = when (name) {
        "ON", "START" -> "on"
        "OFF", "LOCK" -> "off"
        else -> "unknown"
    }

    /** STATE_ERROR is an error (null value). Discharging is never inferred. Returns status + value. */
    fun charging(name: String?): Pair<Availability, Value?> = when (name) {
        "STATE_CHARGING" -> Availability.AVAILABLE to Value.Text("charging")
        "STATE_FULLY_CHARGED" -> Availability.AVAILABLE to Value.Text("complete") // may be below 100% if a target is set
        "STATE_NOT_CHARGING" -> Availability.AVAILABLE to Value.Text("idle")
        "STATE_UNKNOWN" -> Availability.AVAILABLE to Value.Text("unknown")
        "STATE_ERROR" -> Availability.ERROR to null
        else -> Availability.UNAVAILABLE to null
    }

    fun isPark(gearName: String?): Boolean? = gearName?.let { it == "GEAR_PARK" }

    /** RANGE_REMAINING is metres; canonical range_km. Finite and non-negative only. */
    fun rangeMetresToKm(metres: Double?): Double? = metres?.takeIf { it.isFinite() && it >= 0.0 }?.let { it / 1000.0 }
}

object AaosTime {
    /**
     * Converts a property timestamp to UTC by its age, never by "now": wall = nowWall - (nowElapsed - ts).
     * Rejects non-positive, future (beyond [futureToleranceNanos]) and older-than-[maxAgeMs] stamps (null).
     */
    fun toWallMs(
        timestampNanos: Long, nowElapsedNanos: Long, nowWallMs: Long,
        maxAgeMs: Long = 7L * 24 * 3600 * 1000, futureToleranceNanos: Long = 1_000_000_000L,
    ): Long? {
        if (timestampNanos <= 0) return null
        val ageNanos = nowElapsedNanos - timestampNanos
        if (ageNanos < -futureToleranceNanos) return null
        if (ageNanos / 1_000_000 > maxAgeMs) return null
        return nowWallMs - maxOf(0L, ageNanos) / 1_000_000
    }
}

/**
 * Motion from real observations, never from gear alone:
 * fresh signed non-zero speed -> moving; fresh zero speed AND fresh P gear -> parked; everything else
 * (zero speed alone, stale/missing/denied input, moving speed contradicting P) -> unknown.
 */
class MotionEstimator(
    private val speedMaxAgeMs: Long = 15_000,
    private val movingThresholdMps: Double = 0.0,
) {
    private var speed: Pair<Double, Long>? = null // m/s, observed wall ms
    private var park: Pair<Boolean, Long>? = null

    fun onSpeed(metresPerSecond: Double?, observedAtMs: Long) {
        speed = metresPerSecond?.takeIf { it.isFinite() }?.let { it to observedAtMs }
        if (metresPerSecond == null || !metresPerSecond.isFinite()) speed = null
    }

    fun onGear(isPark: Boolean?, observedAtMs: Long) {
        park = isPark?.let { it to observedAtMs }
    }

    /**
     * Value plus the time it is about: the contributing observation(s), or [nowMs] for "unknown".
     *
     * Moving needs a fresh non-zero speed outside park. Parked needs park gear plus a last speed of zero.
     * The zero may be old: gear is an on-change property and many VHALs (the Pleos emulator included)
     * stop re-publishing an unchanged zero speed, while the car cannot move in P without a new gear event.
     * Park with no speed ever seen, a non-zero last speed in P, a failed speed sensor and a stale speed
     * outside P all stay "unknown".
     */
    fun estimate(nowMs: Long): Pair<String, Long> {
        val last = speed
        val fresh = last?.takeIf { nowMs - it.second in 0..speedMaxAgeMs }
        val pk = park
        val parkGear = pk?.first == true
        if (last == null) return "unknown" to nowMs
        val stillNow = kotlin.math.abs(last.first) <= movingThresholdMps
        return when {
            // Fresh zero speed dates the evidence; a held park (speed no longer re-published) is confirmed now.
            parkGear && stillNow -> "parked" to (if (fresh != null) maxOf(last.second, pk!!.second) else nowMs)
            parkGear -> "unknown" to nowMs // contradictory inputs
            fresh == null -> "unknown" to nowMs
            !stillNow -> "moving" to fresh.second
            else -> "unknown" to nowMs
        }
    }
}

/**
 * Turns [AaosEvent]s into [ReadingSink] readings (source = aaos_car_property). Cached/initial values keep their
 * own age through [AaosTime]; a value is never relabelled "now".
 */
class AaosReadings(
    private val sink: ReadingSink,
    private val motion: MotionEstimator = MotionEstimator(),
) {
    private fun emit(s: Signal, a: Availability, v: Value?, at: Long) = sink(s, a, v, at, Source.AAOS_CAR_PROPERTY)

    fun handle(e: AaosEvent, nowElapsedNanos: Long, nowWallMs: Long) {
        val signal = when (e.property) {
            AaosProperty.IGNITION_STATE -> Signal.IGNITION_STATE
            AaosProperty.EV_CHARGE_STATE -> Signal.CHARGING_STATE
            AaosProperty.RANGE_REMAINING -> Signal.RANGE_KM
            AaosProperty.PERF_VEHICLE_SPEED, AaosProperty.GEAR_SELECTION -> Signal.VEHICLE_MOTION
        }
        val at = AaosTime.toWallMs(e.timestampNanos, nowElapsedNanos, nowWallMs)
        val unusable = when (e.status) {
            AaosMapping.STATUS_AVAILABLE -> if (at == null) Availability.UNAVAILABLE else null
            AaosMapping.STATUS_ERROR -> Availability.ERROR
            else -> Availability.UNAVAILABLE
        }
        if (unusable != null) {
            when (e.property) {
                AaosProperty.PERF_VEHICLE_SPEED -> motion.onSpeed(null, nowWallMs)
                AaosProperty.GEAR_SELECTION -> motion.onGear(null, nowWallMs)
                else -> Unit
            }
            if (signal == Signal.VEHICLE_MOTION) reevaluateMotion(nowWallMs, unusable) else emit(signal, unusable, null, nowWallMs)
            return
        }
        val t = at!!
        when (e.property) {
            AaosProperty.IGNITION_STATE -> emit(signal, Availability.AVAILABLE, Value.Text(AaosMapping.ignition(e.enumName)), t)
            AaosProperty.EV_CHARGE_STATE -> AaosMapping.charging(e.enumName).let { (a, v) -> emit(signal, a, v, if (a == Availability.AVAILABLE) t else nowWallMs) }
            AaosProperty.RANGE_REMAINING -> {
                val km = AaosMapping.rangeMetresToKm(e.number)
                if (km == null) emit(signal, Availability.UNAVAILABLE, null, nowWallMs) else emit(signal, Availability.AVAILABLE, Value.Num(km), t)
            }
            AaosProperty.PERF_VEHICLE_SPEED -> { motion.onSpeed(e.number, t); reevaluateMotion(nowWallMs, null) }
            AaosProperty.GEAR_SELECTION -> { motion.onGear(AaosMapping.isPark(e.enumName), t); reevaluateMotion(nowWallMs, null) }
        }
    }

    /** Access to [p] was lost: drop its motion input so stale values cannot keep producing `moving`/`parked`. */
    fun forget(p: AaosProperty) {
        when (p) {
            AaosProperty.PERF_VEHICLE_SPEED -> motion.onSpeed(null, 0)
            AaosProperty.GEAR_SELECTION -> motion.onGear(null, 0)
            else -> Unit
        }
    }

    /** Re-run the fusion (e.g. on the heartbeat) so staleness turns into an explicit "unknown". */
    fun reevaluateMotion(nowWallMs: Long, failure: Availability? = null) {
        if (failure != null && failure != Availability.AVAILABLE) {
            // Input failed: still report what the remaining fresh inputs allow, but never claim more than "unknown".
            val (v, at) = motion.estimate(nowWallMs)
            if (v == "unknown") emit(Signal.VEHICLE_MOTION, failure, null, nowWallMs)
            else emit(Signal.VEHICLE_MOTION, Availability.AVAILABLE, Value.Text(v), at)
            return
        }
        val (v, at) = motion.estimate(nowWallMs)
        emit(Signal.VEHICLE_MOTION, Availability.AVAILABLE, Value.Text(v), at)
    }
}

sealed interface AaosAction {
    /** Subscribe and read; call [AaosAccessTracker.markRegistered] once the subscription succeeded. */
    data object Register : AaosAction

    /** A registered property lost access (permission revoked or no longer listed): unsubscribe and report [reason]. */
    data class Unregister(val reason: Availability) : AaosAction

    /** Not subscribable right now: report this explicit status. */
    data class Report(val availability: Availability) : AaosAction

    data object None : AaosAction
}

/**
 * Re-evaluated on every heartbeat with the *current* permission grant and the *freshly fetched* property list,
 * because Android omits properties the app may not read: a list fetched before a runtime grant says nothing
 * after it. Permission is checked first, so a missing grant is `permission_denied`, never `unsupported`.
 */
class AaosAccessTracker {
    private val registered = linkedSetOf<AaosProperty>()

    fun registeredProperties(): List<AaosProperty> = registered.toList()
    fun isRegistered(p: AaosProperty) = p in registered
    fun markRegistered(p: AaosProperty) { registered += p }
    fun reset() = registered.clear()

    fun evaluate(p: AaosProperty, granted: Boolean, idKnown: Boolean, listed: Boolean): AaosAction {
        val blocked = when {
            !granted -> Availability.PERMISSION_DENIED
            !idKnown || !listed -> Availability.UNSUPPORTED
            else -> null
        }
        val isRegistered = p in registered
        return when {
            blocked == null -> if (isRegistered) AaosAction.None else AaosAction.Register
            isRegistered -> { registered -= p; AaosAction.Unregister(blocked) }
            else -> AaosAction.Report(blocked)
        }
    }
}
