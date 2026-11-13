package app.danim.automotive.ui

import app.danim.core.showcase.CompetitionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-car presentation model over the fixed public records (0.4.1): a glanceable dashboard, structured
 * maintenance rows (state, remaining, baseline and interval kept apart; an unknown stated once with its
 * reason) and the mobile/AI handoff links. Every number is the fixed record or its contracts-identical
 * outlook; nothing is invented, nothing is live.
 */
class ShowcaseModelTest {
    private val rows = ShowcaseModel.maintenanceRows()

    @Test
    fun `the car navigation is five stable targets of one fixed height`() {
        assertEquals(5, ShowcaseTab.entries.size)
        assertEquals(listOf("STATUS", "REPORT", "MAINTENANCE", "CONNECT", "ROADMAP"), ShowcaseTab.entries.map { it.name })
        assertTrue(ShowcaseModel.MIN_TARGET_DP >= 64)
        assertTrue(ShowcaseModel.TAB_HEIGHT_DP >= ShowcaseModel.MIN_TARGET_DP)
    }

    @Test
    fun `maintenance rows keep state, remaining, baseline and interval apart`() {
        assertEquals(listOf("demo-tire-rotation", "demo-cabin-filter", "demo-brake-fluid"), rows.map { it.id })
        val tire = rows[0]
        assertEquals(CompetitionReport.OutlookState.OK, tire.state)
        assertEquals(81L, tire.daysRemaining); assertEquals(7910.5, tire.kmRemaining!!, 0.0)
        assertEquals("2025-10-01", tire.lastServiceDate); assertEquals(10000.0, tire.lastServiceOdometerKm!!, 0.0)
        assertEquals(180, tire.intervalDays); assertEquals(10000.0, tire.intervalKm!!, 0.0)
        assertNull(tire.unknownReason)

        val filter = rows[1]
        assertEquals(CompetitionReport.OutlookState.DUE_SOON, filter.state)
        assertEquals(7L, filter.daysRemaining); assertEquals(10910.5, filter.kmRemaining!!, 0.0)
        assertNull(filter.unknownReason)
    }

    @Test
    fun `an unknown schedule is unknown once, with its reason, and never gets remaining numbers`() {
        val brake = rows[2]
        assertEquals(CompetitionReport.OutlookState.UNKNOWN, brake.state)
        assertNull(brake.daysRemaining); assertNull(brake.kmRemaining)
        assertNull(brake.lastServiceDate); assertNull(brake.lastServiceOdometerKm)
        assertEquals(730, brake.intervalDays); assertNull(brake.intervalKm)
        assertEquals(ShowcaseModel.UnknownReason.NO_BASELINE, brake.unknownReason)

        val noInterval = CompetitionReport.Schedule("x", CompetitionReport.Label("x", "x"), lastServiceDate = "2025-10-01", lastServiceOdometerKm = 10000.0)
        assertEquals(ShowcaseModel.UnknownReason.NO_INTERVAL, ShowcaseModel.unknownReason(noInterval, outlook(noInterval)))
        val badDate = noInterval.copy(lastServiceDate = "2025-02-30", intervalDays = 180)
        assertEquals(ShowcaseModel.UnknownReason.UNUSABLE_BASIS, ShowcaseModel.unknownReason(badDate, outlook(badDate)))
        val future = noInterval.copy(lastServiceDate = "2026-02-01", intervalDays = 180)
        assertEquals(ShowcaseModel.UnknownReason.UNUSABLE_BASIS, ShowcaseModel.unknownReason(future, outlook(future)))
        // A known state carries no reason.
        val fine = noInterval.copy(intervalDays = 180, intervalKm = 10000.0)
        assertNull(ShowcaseModel.unknownReason(fine, outlook(fine)))
    }

    @Test
    fun `next maintenance is the most urgent assessable schedule, never an unknown one`() {
        assertEquals("demo-cabin-filter", ShowcaseModel.nextMaintenance(rows)?.id)
        assertNull(ShowcaseModel.nextMaintenance(rows.filter { it.state == CompetitionReport.OutlookState.UNKNOWN }))
        assertNull(ShowcaseModel.nextMaintenance(emptyList()))
        val overdue = rows[0].copy(id = "late", state = CompetitionReport.OutlookState.OVERDUE, daysRemaining = -400, kmRemaining = -5.0)
        assertEquals("late", ShowcaseModel.nextMaintenance(rows + overdue)?.id)
        // Same state: the smaller remaining share of its interval first (50 of 15,000 km beats 7 of 365 days).
        val soonerKm = rows[1].copy(id = "km", daysRemaining = null, kmRemaining = 50.0)
        assertEquals("km", ShowcaseModel.nextMaintenance(listOf(rows[1], soonerKm))?.id)
        assertEquals("demo-cabin-filter", ShowcaseModel.nextMaintenance(listOf(rows[1], rows[1].copy(id = "later", daysRemaining = 20)))?.id)
    }

    @Test
    fun `the dashboard carries the fixed status and never invents a value`() {
        val d = ShowcaseModel.dashboard()
        assertEquals(74.0, d.socPercent!!, 0.0); assertEquals(296.0, d.rangeKm!!, 0.0)
        assertEquals("idle", d.chargingState); assertEquals("2026-01-07T23:30:00Z", d.observedAt)
        assertEquals(12089.5, d.odometerKm!!, 0.0)
        assertTrue(d.fuelUnsupported); assertNull(d.fuelLevelPercent); assertNull(d.batteryHealthPercent)
        assertEquals("demo-cabin-filter", d.next?.id)
        assertEquals(1, d.unknownScheduleCount); assertEquals(2, d.alertCount)
        assertEquals(CompetitionReport.VERSION, d.version); assertEquals("2026-01-08", d.asOf); assertEquals("competition_fixture", d.source)
    }

    @Test
    fun `handoff links are public https pages and the QR carries the public record, never a credential`() {
        val h = ShowcaseModel.handoff()
        assertEquals("https://demo.danim.app/showcase", h.qrUrl)
        assertEquals(h.showcaseUrl, h.qrUrl)
        assertEquals("https://danim.app/", h.ownerSiteUrl)
        assertEquals("https://danim.app/integrations", h.integrationsUrl)
        assertEquals("https://demo.danim.app/api/demo/mcp", h.mcpUrl)
        assertEquals(CompetitionReport.VERSION, h.version); assertEquals("danim-competition-fixed-records", h.reportId)
        listOf(h.qrUrl, h.showcaseUrl, h.ownerSiteUrl, h.integrationsUrl, h.mcpUrl).forEach { u ->
            assertTrue(u, u.startsWith("https://"))
            listOf("token", "secret", "code=", "device", "key=", "pair").forEach { assertFalse("$u contains $it", u.contains(it, ignoreCase = true)) }
        }
    }

    @Test
    fun `numbers are grouped for glanceable reading and unknown is never zero`() {
        assertEquals("74", ShowcaseModel.fmtNum(74.0))
        assertEquals("12,089.5", ShowcaseModel.fmtNum(12089.5))
        assertEquals("7,910.5", ShowcaseModel.fmtNum(7910.5))
        assertEquals("17,000 KRW", ShowcaseModel.fmtKrw(17000))
        assertEquals("2026-01-07 23:30 UTC", ShowcaseModel.fmtWhen("2026-01-07T23:30:00Z"))
        assertNull(ShowcaseModel.dashboard().batteryHealthPercent) // rendered as "unknown", never 0
    }

    private fun outlook(s: CompetitionReport.Schedule) = CompetitionReport.maintenanceOutlook(
        CompetitionReport.Basis("2026-01-08", 12089.5, s.lastServiceDate, s.lastServiceOdometerKm, s.intervalDays, s.intervalKm),
    )
}
