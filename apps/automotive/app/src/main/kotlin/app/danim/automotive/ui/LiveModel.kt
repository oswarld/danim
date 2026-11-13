package app.danim.automotive.ui

import app.danim.automotive.SignalInfo
import app.danim.core.Availability
import app.danim.core.Value
import java.util.Locale

/**
 * Presentation model for the paired car's live dashboard (0.4.2). Every tile is this car's own latest reading
 * from the running collector (`Graph.signalBoard`): the value, or the reason there is none. Nothing is
 * estimated, cached across restarts or read from the fixed showcase records.
 */
internal object LiveModel {
    /** Why a tile shows no number. [VALUE] carries one. */
    enum class Kind { VALUE, NO_DATA, UNSUPPORTED, DENIED, UNAVAILABLE, ERROR }

    data class Tile(val kind: Kind, val text: String?, val observedAtMs: Long?)

    /** Motion and charging are words; they are mapped by the screen to localized labels. */
    enum class Motion { MOVING, PARKED, UNKNOWN }
    enum class Charging { CHARGING, COMPLETE, IDLE, DISCHARGING, UNKNOWN }

    private fun base(info: SignalInfo?): Tile? = when {
        info == null -> Tile(Kind.NO_DATA, null, null)
        info.availability == Availability.UNSUPPORTED -> Tile(Kind.UNSUPPORTED, null, info.observedAtMs)
        info.availability == Availability.PERMISSION_DENIED -> Tile(Kind.DENIED, null, info.observedAtMs)
        info.availability == Availability.ERROR -> Tile(Kind.ERROR, null, info.observedAtMs)
        info.availability != Availability.AVAILABLE || info.value == null -> Tile(Kind.UNAVAILABLE, null, info.observedAtMs)
        else -> null
    }

    /** A number with a unit; a non-number value is unavailable, never zero. */
    fun number(info: SignalInfo?, unit: String): Tile = base(info) ?: when (val v = info!!.value) {
        is Value.Num -> Tile(Kind.VALUE, fmt(v.v) + (if (unit == "%") unit else " $unit"), info.observedAtMs)
        else -> Tile(Kind.UNAVAILABLE, null, info.observedAtMs)
    }

    fun motion(info: SignalInfo?): Pair<Tile, Motion?> = base(info)?.let { it to null } ?: run {
        val word = (info!!.value as? Value.Text)?.v
        val m = when (word) { "moving" -> Motion.MOVING; "parked" -> Motion.PARKED; "unknown" -> Motion.UNKNOWN; else -> null }
        if (m == null) Tile(Kind.UNAVAILABLE, null, info.observedAtMs) to null else Tile(Kind.VALUE, word, info.observedAtMs) to m
    }

    fun charging(info: SignalInfo?): Pair<Tile, Charging?> = base(info)?.let { it to null } ?: run {
        val word = (info!!.value as? Value.Text)?.v
        val c = when (word) {
            "charging" -> Charging.CHARGING; "complete" -> Charging.COMPLETE; "idle" -> Charging.IDLE
            "discharging" -> Charging.DISCHARGING; "unknown" -> Charging.UNKNOWN; else -> null
        }
        if (c == null) Tile(Kind.UNAVAILABLE, null, info.observedAtMs) to null else Tile(Kind.VALUE, word, info.observedAtMs) to c
    }

    /** Same window as the web: a heartbeat (5 min) plus one poll. Older readings are labelled stale. */
    const val FRESH_MS = 6 * 60_000L

    fun isStale(observedAtMs: Long, nowMs: Long): Boolean = nowMs - observedAtMs > FRESH_MS

    /** Whether two instants fall on the same calendar day in [zone]; another day's reading shows its date. */
    fun sameDay(aMs: Long, bMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean {
        val a = java.util.Calendar.getInstance(zone).apply { timeInMillis = aMs }
        val b = java.util.Calendar.getInstance(zone).apply { timeInMillis = bMs }
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Grouped, at most one decimal: 12,003 · 81 · 406.5. */
    fun fmt(v: Double): String = if (v == Math.rint(v)) "%,.0f".format(Locale.ROOT, v) else "%,.1f".format(Locale.ROOT, v)
}
