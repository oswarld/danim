package app.danim.core.showcase

import app.danim.core.showcase.CompetitionReport.Basis
import app.danim.core.showcase.CompetitionReport.OutlookState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixed competition records (`packages/contracts/src/competition-service.json`) as the APK
 * presents them: byte-identical resource, the same summary arithmetic and maintenance outlook as
 * the contracts accessor (`competition-service.test.ts`), unknown values preserved.
 */
class CompetitionReportTest {
    private val r = CompetitionReport.report

    @Test
    fun `the bundled fixture is byte-identical to the contracts file and pinned by hash`() {
        assertEquals(CompetitionReport.SHA256, CompetitionReport.sha256(CompetitionReport.bytes))
        val contracts = File("../../../packages/contracts/src/competition-service.json")
        assertTrue(contracts.isFile, "contracts fixture present at ${contracts.absolutePath}")
        assertTrue(contracts.readBytes().contentEquals(CompetitionReport.bytes), "resource differs from the contracts fixture")
    }

    @Test
    fun `one stable provenance envelope, no simulator or private selectors`() {
        assertEquals(CompetitionReport.VERSION, r.version)
        assertEquals("danim-competition-fixed-records", r.reportId)
        assertEquals(true, r.synthetic); assertEquals("demo", r.dataMode); assertEquals("competition_fixture", r.source)
        assertEquals("2026-01-08", r.asOf); assertEquals("Asia/Seoul", r.timeZone); assertEquals("KRW", r.currency)
        val text = CompetitionReport.bytes.toString(Charsets.UTF_8)
        for (banned in listOf("\"vehicles\"", "\"minute\"", "\"stages\"", "\"preset\"", "deviceId", "device_id", "eventId", "secret", "token", "credential", "pleos_vehicle_sdk")) {
            assertTrue(!text.contains(banned), banned)
        }
        // Only the public report version may be selected; anything else is refused, not defaulted.
        assertEquals(CompetitionReport.VERSION, CompetitionReport.select(null))
        assertEquals(CompetitionReport.VERSION, CompetitionReport.select(CompetitionReport.VERSION))
        assertFailsWith<IllegalArgumentException> { CompetitionReport.select("wrong") }
        assertFailsWith<IllegalArgumentException> { CompetitionReport.select("2026-10-28.1") }
    }

    @Test
    fun `the week summary arithmetic matches the contracts accessor`() {
        val s = CompetitionReport.summary()
        assertEquals(3, s.tripCount); assertEquals(89.5, s.tripDistanceKm)
        assertEquals(2, s.chargingSessionCount)
        assertEquals(61.0, s.gridEnergyKwh); assertEquals(56.0, s.batteryEnergyKwh); assertEquals(17000, s.chargingCostKrw)
        assertEquals("synthetic_grid_energy_tariff", s.costBasis); assertEquals("fixed_records_only", s.coverage)
        assertEquals(listOf(27.0, 44.2, 18.3), r.trips.map { it.distanceKm })
        assertEquals(listOf(6500, 10500), r.chargingSessions.map { it.cost })
        assertEquals(listOf(250, 300), r.chargingSessions.map { it.tariffPerKwh })
    }

    @Test
    fun `the fixed status keeps unknown and unsupported distinct and never invents health`() {
        val st = r.status
        assertEquals("2026-01-07T23:30:00Z", st.observedAt)
        assertEquals(74.0, st.batterySocPercent); assertEquals(296.0, st.rangeKm); assertEquals(12089.5, st.odometerKm)
        assertEquals("idle", st.chargingState)
        assertNull(st.fuelLevelPercent); assertEquals("unsupported", st.fuelAvailability)
        assertNull(st.batteryHealthPercent)
    }

    @Test
    fun `maintenance uses fictional owner schedules with unknown baselines preserved`() {
        val m = r.maintenance
        assertEquals("illustrative_owner_schedule", m.scheduleBasis)
        assertTrue(m.records.all { it.source == "owner_entered" })
        assertNull(m.records.first { it.id == "demo-maintenance-rotation" }.amountKrw)
        assertEquals(30000, m.records.first { it.id == "demo-maintenance-filter" }.amountKrw)
        val outlook = CompetitionReport.outlook().associateBy { it.schedule.id }
        assertEquals(OutlookState.DUE_SOON, outlook.getValue("demo-cabin-filter").outlook.state)
        assertEquals(7L, outlook.getValue("demo-cabin-filter").outlook.daysRemaining)
        assertEquals(OutlookState.OK, outlook.getValue("demo-tire-rotation").outlook.state)
        assertEquals(81L, outlook.getValue("demo-tire-rotation").outlook.daysRemaining)
        assertEquals(7910.5, outlook.getValue("demo-tire-rotation").outlook.kmRemaining)
        assertEquals(OutlookState.UNKNOWN, outlook.getValue("demo-brake-fluid").outlook.state)
        assertNull(outlook.getValue("demo-brake-fluid").outlook.daysRemaining)
        assertNull(outlook.getValue("demo-brake-fluid").outlook.kmRemaining)
        assertEquals(listOf("unknown", "overdue", "due_soon", "ok"), OutlookState.entries.map { it.wire })
    }

    @Test
    fun `maintenance distinguishes known zero, overdue and missing or future odometer`() {
        val base = Basis(asOf = "2026-01-08", odometerKm = 10000.0, lastServiceDate = null, lastServiceOdometerKm = 0.0, intervalDays = null, intervalKm = 10000.0)
        assertEquals(0.0, CompetitionReport.maintenanceOutlook(base).kmRemaining)
        assertEquals(OutlookState.OVERDUE, CompetitionReport.maintenanceOutlook(base).state)
        assertEquals(OutlookState.UNKNOWN, CompetitionReport.maintenanceOutlook(base.copy(odometerKm = null)).state)
        assertNull(CompetitionReport.maintenanceOutlook(base.copy(lastServiceOdometerKm = 11000.0)).kmRemaining)
        assertNull(CompetitionReport.maintenanceOutlook(base.copy(lastServiceDate = "2027-01-01", intervalDays = 365)).daysRemaining)
    }

    @Test
    fun `invalid maintenance dates and intervals cannot produce confident due dates`() {
        val base = Basis(asOf = "2026-01-08", odometerKm = null, lastServiceDate = "2025-02-30", lastServiceOdometerKm = null, intervalDays = 365, intervalKm = null)
        assertEquals(OutlookState.UNKNOWN, CompetitionReport.maintenanceOutlook(base).state)
        assertNull(CompetitionReport.maintenanceOutlook(base.copy(lastServiceDate = "2025-01-08", intervalDays = 0)).daysRemaining)
        assertEquals(OutlookState.UNKNOWN, CompetitionReport.maintenanceOutlook(base.copy(lastServiceDate = "2025-01-08", intervalDays = -1)).state)
    }

    @Test
    fun `web and AI connection points are the public demo origin with three natural questions`() {
        assertEquals("https://demo.danim.app/showcase", CompetitionReport.SHOWCASE_URL)
        assertEquals("https://demo.danim.app/api/demo/mcp", CompetitionReport.MCP_URL)
        assertEquals(listOf("departure", "weekly-cost", "maintenance"), r.questions.map { it.id })
        assertTrue(r.questions.all { it.ko.isNotBlank() && it.en.isNotBlank() })
        assertEquals(2, r.alerts.size); assertTrue(r.alerts.all { it.delivery == "demo_inbox_only" })
        assertEquals(4, r.assumptions.en.size); assertEquals(4, r.assumptions.ko.size)
        assertTrue(r.notice.en.contains("not real vehicle observations"))
    }
}
