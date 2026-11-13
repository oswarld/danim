package app.danim.core.showcase

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fixed competition records shared with Danim.app's public showcase and the public demo MCP
 * (`packages/contracts/src/competition-service.json`, version [VERSION]), bundled byte-identically
 * as a Java resource and parsed once.
 *
 * This is a **fixed, synthetic, public record set**: one observation timestamp, one management week,
 * owner-entered maintenance examples and three natural questions. It is not a simulator (no stages,
 * presets, minutes or vehicles exist in it), not a vehicle reading and not a live connection; the
 * real read-only collector and pairing path is entirely separate and never reads this object.
 * Summary arithmetic and the maintenance outlook are computed exactly as the contracts accessor
 * (`competition-service.ts`) computes them, and `CompetitionReportTest` pins both to its numbers.
 * Unknown stays unknown: a missing baseline, odometer, date or interval yields null / `unknown`,
 * never zero or an estimate.
 */
object CompetitionReport {
    const val VERSION = "2026-10-29.1"
    const val RESOURCE = "/app/danim/core/showcase/competition-service.json"
    /** SHA-256 of the fixture bytes as delivered by the contracts owner (2026-10-29). */
    const val SHA256 = "b616ce47bd1efe640a16460a5995bfe9c2936ad81404ce3ad8e50eb29554abc5"
    const val DEMO_ORIGIN = "https://demo.danim.app"
    const val SHOWCASE_URL = "$DEMO_ORIGIN/showcase"
    const val MCP_URL = "$DEMO_ORIGIN/api/demo/mcp"
    /** Public read-only tool that answers the maintenance question; the others read the same report. */
    const val MCP_MAINTENANCE_TOOL = "demo_get_maintenance"

    @Serializable data class Label(val ko: String, val en: String)
    @Serializable data class Period(val from: String, val to: String, val label: Label)
    @Serializable data class Status(
        val observedAt: String,
        val batterySocPercent: Double? = null, val rangeKm: Double? = null, val odometerKm: Double? = null,
        val chargingState: String? = null, val fuelLevelPercent: Double? = null, val fuelAvailability: String,
        val batteryHealthPercent: Double? = null,
    )
    @Serializable data class Trip(val id: String, val date: String, val distanceKm: Double, val averageSpeedKph: Double, val maximumSpeedKph: Double, val assistedDistanceKm: Double)
    @Serializable data class ChargingSession(
        val id: String, val startedAt: String, val endedAt: String, val energyKwh: Double, val gridEnergyKwh: Double,
        val tariffPerKwh: Int, val cost: Int, val fromSoc: Double, val toSoc: Double,
    )
    @Serializable data class MaintenanceRecord(val id: String, val serviceName: Label, val occurredOn: String, val odometerKm: Double? = null, val amountKrw: Int? = null, val source: String)
    @Serializable data class Schedule(
        val id: String, val name: Label, val lastServiceDate: String? = null, val lastServiceOdometerKm: Double? = null,
        val intervalDays: Int? = null, val intervalKm: Double? = null,
    )
    @Serializable data class Maintenance(val scheduleBasis: String, val records: List<MaintenanceRecord>, val schedules: List<Schedule>, val assumptions: Label)
    @Serializable data class Alert(val id: String, val recordedAt: String, val kind: String, val label: Label, val delivery: String)
    @Serializable data class Question(val id: String, val ko: String, val en: String)
    @Serializable data class Assumptions(val ko: List<String>, val en: List<String>)
    @Serializable data class Report(
        val version: String, val reportId: String, val synthetic: Boolean, val dataMode: String, val source: String,
        val title: Label, val notice: Label, val asOf: String, val timeZone: String, val currency: String, val period: Period,
        val status: Status, val trips: List<Trip>, val chargingSessions: List<ChargingSession>, val maintenance: Maintenance,
        val alerts: List<Alert>, val questions: List<Question>, val assumptions: Assumptions,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The exact resource bytes, for hashing against the contracts file. */
    val bytes: ByteArray by lazy {
        CompetitionReport::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: error("competition-service resource missing")
    }

    val report: Report by lazy { json.decodeFromString(Report.serializer(), bytes.toString(Charsets.UTF_8)) }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Only the public report version may be selected (null = current); anything else is refused, as in contracts. */
    fun select(version: String?): String {
        require(version == null || version == VERSION) { "Unknown competition report version." }
        return VERSION
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    data class Summary(
        val tripCount: Int, val tripDistanceKm: Double, val chargingSessionCount: Int,
        val gridEnergyKwh: Double, val batteryEnergyKwh: Double, val chargingCostKrw: Int,
        val costBasis: String = "synthetic_grid_energy_tariff", val coverage: String = "fixed_records_only",
    )

    /** Same sums as `getCompetitionReport().summary`: grid energy × per-session tariff is the cost basis, distinct from battery energy. */
    fun summary(): Summary = Summary(
        tripCount = report.trips.size,
        tripDistanceKm = round2(report.trips.sumOf { it.distanceKm }),
        chargingSessionCount = report.chargingSessions.size,
        gridEnergyKwh = round2(report.chargingSessions.sumOf { it.gridEnergyKwh }),
        batteryEnergyKwh = round2(report.chargingSessions.sumOf { it.energyKwh }),
        chargingCostKrw = report.chargingSessions.sumOf { it.cost },
    )

    enum class OutlookState(val wire: String) { UNKNOWN("unknown"), OVERDUE("overdue"), DUE_SOON("due_soon"), OK("ok") }

    data class Basis(
        val asOf: String, val odometerKm: Double?, val lastServiceDate: String?, val lastServiceOdometerKm: Double?,
        val intervalDays: Int?, val intervalKm: Double?,
    )

    data class Outlook(val state: OutlookState, val daysRemaining: Long?, val kmRemaining: Double?)

    private val dayPattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Epoch day of a strict `YYYY-MM-DD` calendar date; null for anything else (e.g. 2025-02-30). */
    private fun dateDay(value: String?): Long? {
        if (value == null || !dayPattern.matches(value)) return null
        return try { LocalDate.parse(value).toEpochDay() } catch (e: DateTimeParseException) { null }
    }

    private fun nonNegative(v: Double?): Boolean = v != null && v.isFinite() && v >= 0.0

    /** Port of `maintenanceOutlook` in contracts: owner-supplied example intervals, never an OEM recommendation. */
    fun maintenanceOutlook(b: Basis): Outlook {
        val now = dateDay(b.asOf)
        val last = dateDay(b.lastServiceDate)
        val daysValid = now != null && last != null && last <= now && b.intervalDays != null && b.intervalDays > 0 && b.intervalDays <= 3650
        val kmValid = nonNegative(b.odometerKm) && nonNegative(b.lastServiceOdometerKm) && b.lastServiceOdometerKm!! <= b.odometerKm!! &&
            b.intervalKm != null && b.intervalKm.isFinite() && b.intervalKm > 0.0 && b.intervalKm <= 500_000.0
        val daysRemaining = if (daysValid) b.intervalDays!! - (now!! - last!!) else null
        val kmRemaining = if (kmValid) round2(b.intervalKm!! - (b.odometerKm!! - b.lastServiceOdometerKm!!)) else null
        val unknown = (b.intervalDays != null && !daysValid) || (b.intervalKm != null && !kmValid) || (!daysValid && !kmValid)
        val overdue = (daysRemaining != null && daysRemaining <= 0) || (kmRemaining != null && kmRemaining <= 0.0)
        val soon = (daysRemaining != null && daysRemaining <= 30) || (kmRemaining != null && kmRemaining <= b.intervalKm!! * 0.1)
        val state = when {
            unknown -> OutlookState.UNKNOWN
            overdue -> OutlookState.OVERDUE
            soon -> OutlookState.DUE_SOON
            else -> OutlookState.OK
        }
        return Outlook(state, daysRemaining, kmRemaining)
    }

    data class ScheduleOutlook(val schedule: Schedule, val outlook: Outlook)

    /** Every schedule assessed at the report's `asOf` date and fixed odometer, like `getCompetitionMaintenance().outlook`. */
    fun outlook(): List<ScheduleOutlook> = report.maintenance.schedules.map { s ->
        ScheduleOutlook(s, maintenanceOutlook(Basis(report.asOf, report.status.odometerKm, s.lastServiceDate, s.lastServiceOdometerKm, s.intervalDays, s.intervalKm)))
    }
}
