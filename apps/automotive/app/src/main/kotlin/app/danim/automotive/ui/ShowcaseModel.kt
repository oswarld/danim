package app.danim.automotive.ui

import app.danim.core.showcase.CompetitionReport
import java.util.Locale

/**
 * Presentation model for the in-car showcase (0.4.1): a glanceable dashboard over the fixed public
 * records, structured maintenance rows and the mobile/AI handoff links. Pure and unit-tested
 * ([ShowcaseModelTest]); every number is the bundled record or its contracts-identical outlook.
 * Nothing here is a vehicle reading, nothing is estimated and nothing reads the SDK or the network.
 */
internal object ShowcaseModel {
    /** Every car control is at least this tall; the five tabs are exactly [TAB_HEIGHT_DP] whether selected or not. */
    const val MIN_TARGET_DP = 64
    const val TAB_HEIGHT_DP = 72

    /** Public pages only. The QR carries the public record URL and never a device credential or pairing code. */
    const val QR_URL = CompetitionReport.SHOWCASE_URL
    const val OWNER_SITE_URL = "https://danim.app/"
    const val INTEGRATIONS_URL = "https://danim.app/integrations"

    /** Why an outlook is unknown, stated once per schedule instead of repeating "unknown" per field. */
    enum class UnknownReason { NO_BASELINE, NO_INTERVAL, UNUSABLE_BASIS }

    data class MaintenanceRow(
        val id: String, val name: CompetitionReport.Label, val state: CompetitionReport.OutlookState,
        val daysRemaining: Long?, val kmRemaining: Double?,
        val lastServiceDate: String?, val lastServiceOdometerKm: Double?,
        val intervalDays: Int?, val intervalKm: Double?,
        val unknownReason: UnknownReason?,
    )

    fun unknownReason(s: CompetitionReport.Schedule, o: CompetitionReport.Outlook): UnknownReason? = when {
        o.state != CompetitionReport.OutlookState.UNKNOWN -> null
        s.lastServiceDate == null && s.lastServiceOdometerKm == null -> UnknownReason.NO_BASELINE
        s.intervalDays == null && s.intervalKm == null -> UnknownReason.NO_INTERVAL
        else -> UnknownReason.UNUSABLE_BASIS
    }

    fun maintenanceRows(outlooks: List<CompetitionReport.ScheduleOutlook> = CompetitionReport.outlook()): List<MaintenanceRow> =
        outlooks.map { (s, o) ->
            MaintenanceRow(
                s.id, s.name, o.state, o.daysRemaining, o.kmRemaining,
                s.lastServiceDate, s.lastServiceOdometerKm, s.intervalDays, s.intervalKm, unknownReason(s, o),
            )
        }

    private val severity = mapOf(
        CompetitionReport.OutlookState.OVERDUE to 0, CompetitionReport.OutlookState.DUE_SOON to 1, CompetitionReport.OutlookState.OK to 2,
    )

    /** Smallest remaining share of an interval (days/intervalDays or km/intervalKm), the comparable urgency across schedules. */
    private fun margin(r: MaintenanceRow): Double = listOfNotNull(
        r.daysRemaining?.let { d -> r.intervalDays?.let { d.toDouble() / it } },
        r.kmRemaining?.let { k -> r.intervalKm?.let { k / it } },
    ).minOrNull() ?: Double.MAX_VALUE

    /** The most urgent assessable schedule: by state, then by the smallest remaining share of its interval. Unknown is never promoted. */
    fun nextMaintenance(rows: List<MaintenanceRow>): MaintenanceRow? = rows
        .filter { it.state != CompetitionReport.OutlookState.UNKNOWN }
        .minWithOrNull(compareBy({ severity.getValue(it.state) }, { margin(it) }, { it.daysRemaining ?: Long.MAX_VALUE }, { it.kmRemaining ?: Double.MAX_VALUE }))

    data class Dashboard(
        val version: String, val asOf: String, val source: String, val observedAt: String,
        val socPercent: Double?, val rangeKm: Double?, val chargingState: String?, val odometerKm: Double?,
        val fuelUnsupported: Boolean, val fuelLevelPercent: Double?, val batteryHealthPercent: Double?,
        val next: MaintenanceRow?, val unknownScheduleCount: Int, val alertCount: Int,
    )

    fun dashboard(): Dashboard {
        val r = CompetitionReport.report
        val rows = maintenanceRows()
        return Dashboard(
            version = r.version, asOf = r.asOf, source = r.source, observedAt = r.status.observedAt,
            socPercent = r.status.batterySocPercent, rangeKm = r.status.rangeKm, chargingState = r.status.chargingState,
            odometerKm = r.status.odometerKm, fuelUnsupported = r.status.fuelAvailability == "unsupported",
            fuelLevelPercent = r.status.fuelLevelPercent, batteryHealthPercent = r.status.batteryHealthPercent,
            next = nextMaintenance(rows), unknownScheduleCount = rows.count { it.state == CompetitionReport.OutlookState.UNKNOWN },
            alertCount = r.alerts.size,
        )
    }

    data class Handoff(
        val qrUrl: String, val showcaseUrl: String, val ownerSiteUrl: String, val integrationsUrl: String,
        val mcpUrl: String, val version: String, val reportId: String,
    )

    fun handoff(): Handoff = Handoff(
        QR_URL, CompetitionReport.SHOWCASE_URL, OWNER_SITE_URL, INTEGRATIONS_URL, CompetitionReport.MCP_URL,
        CompetitionReport.report.version, CompetitionReport.report.reportId,
    )

    fun fmtNum(v: Double): String = if (v == Math.rint(v)) "%,.0f".format(Locale.ROOT, v) else "%,.1f".format(Locale.ROOT, v)
    fun fmtKrw(v: Int): String = "%,d KRW".format(Locale.ROOT, v)
    /** `2026-01-07T23:30:00Z` → `2026-01-07 23:30 UTC`: the recorded instant, minutes are enough on a dashboard. */
    fun fmtWhen(iso: String): String = iso.replace("T", " ").removeSuffix("Z").let { if (it.length >= 16) it.take(16) else it } + " UTC"
}
