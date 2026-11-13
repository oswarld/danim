package app.danim.core.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copilot speaks only from its inputs: missing readings are said to be missing, estimates follow the stated formula. */
class CopilotTest {
    private val plan = DayPlan(Say("판교 미팅", "Pangyo meeting"), "09:30", 62.0)
    private val tariff = Tariff(offPeakFromHour = 23, offPeakToHour = 9, offPeakKrwPerKwh = 120, standardKrwPerKwh = 250, batteryKwh = 77.4)
    private val week = Week(trips = 3, distanceKm = 89.5, sessions = 2, costKrw = 17_000)
    private val service = NextService(Say("캐빈 필터", "Cabin filter"), 7, 10_910.5)
    private fun ctx(soc: Double? = 74.0, range: Double? = 296.0, hour: Int = 8, parked: Boolean? = true, trip: TripRecap? = null, svc: NextService? = service) =
        CopilotContext(CarNow(soc, range, 12_089.5, parked, Origin.FIXED), week, svc, plan, tariff, trip, hour)

    @Test
    fun `briefing states the readings, the plan margin and a near service`() {
        val b = Copilot.briefing(ctx())
        assertTrue(b.ko, b.ko.startsWith("배터리는 74%, 주행 가능 거리는 296 km입니다."))
        // 74 - 62 / 296 * 74 = 58.5 → about 59 % (round half up on .5 is not relied on: 58.5 rounds to 59 with roundToInt)
        assertEquals(58.5, Copilot.chargeAfterPlan(ctx().now, plan)!!, 1e-9)
        assertTrue(b.ko, "충전 없이 다녀올 수 있고" in b.ko && "약 59%" in b.ko)
        assertTrue(b.ko, b.ko.endsWith("캐빈 필터까지 7일 남았습니다."))
        assertTrue(b.en, "fits without charging" in b.en && b.en.endsWith("Cabin filter is due in 7 days."))
    }

    @Test
    fun `a missing reading is said to be missing and never becomes a number`() {
        val none = Copilot.briefing(ctx(soc = null, range = null, svc = null))
        assertTrue(none.ko, none.ko.startsWith("배터리와 주행 가능 거리를 아직 받지 못했습니다."))
        assertFalse(none.ko, "0%" in none.ko || "0 km" in none.ko)
        assertNull(Copilot.chargeAfterPlan(ctx(soc = null).now, plan))
        assertNull(Copilot.chargeAfterPlan(ctx(range = 0.0).now, plan))
        assertTrue(Copilot.briefing(ctx(range = null)).ko.contains("주행 가능 거리는 아직 받지 못했습니다"))
        assertNull(Copilot.chargeAdvice(ctx(soc = null)).costNowKrw)
    }

    @Test
    fun `a plan that leaves too little charge asks for a charge`() {
        val b = Copilot.briefing(ctx(soc = 20.0, range = 80.0)) // 20 - 62/80*20 = 4.5 %
        assertTrue(b.ko, "충전이 필요합니다" in b.ko)
        assertTrue(Copilot.chargeAfterPlan(ctx(soc = 20.0, range = 80.0).now, plan)!! < Copilot.RETURN_MARGIN_PERCENT)
    }

    @Test
    fun `low battery speaks at or below the threshold only`() {
        assertNull(Copilot.lowBattery(ctx(soc = 21.0).now))
        assertNull(Copilot.lowBattery(ctx(soc = null).now))
        val low = Copilot.lowBattery(ctx(soc = 18.0, range = 72.0).now)!!
        assertEquals("배터리가 18%, 주행 가능 거리가 72 km 남았습니다. 주변 충전소를 내비게이션에 띄울까요?", low.ko)
        assertNotNull(Copilot.lowBattery(ctx(soc = 20.0, range = null).now))
    }

    @Test
    fun `charge advice uses the example tariff and the off-peak window across midnight`() {
        assertTrue(tariff.isOffPeak(23)); assertTrue(tariff.isOffPeak(3)); assertTrue(tariff.isOffPeak(8))
        assertFalse(tariff.isOffPeak(9)); assertFalse(tariff.isOffPeak(22))
        val day = Copilot.chargeAdvice(ctx(soc = 40.0, hour = 14)) // (80-40)% of 77.4 = 30.96 kWh
        assertEquals(30.96, day.kwhToTarget!!, 1e-9)
        assertEquals(7_700, day.costNowKrw); assertEquals(3_700, day.costOffPeakKrw); assertEquals(4_000, day.savingKrw)
        assertFalse(day.offPeakNow)
        assertTrue(day.text.ko, "23시 이후" in day.text.ko && "4,000원을 아낄 수 있습니다" in day.text.ko)
        val night = Copilot.chargeAdvice(ctx(soc = 40.0, hour = 2))
        assertTrue(night.offPeakNow); assertTrue(night.text.ko, night.text.ko.startsWith("지금이 경부하 시간입니다."))
        val full = Copilot.chargeAdvice(ctx(soc = 85.0))
        assertEquals(0, full.costNowKrw); assertTrue(full.text.ko, "충전할 필요가 없습니다" in full.text.ko)
    }

    @Test
    fun `questions map to intents and answer from the context`() {
        assertEquals(Intent.BATTERY, Copilot.intentOf("배터리 얼마나 남았어?"))
        assertEquals(Intent.DAY_PLAN, Copilot.intentOf("오늘 일정 충전 없이 갈 수 있어?"))
        assertEquals(Intent.WEEK, Copilot.intentOf("이번 주 얼마나 탔어"))
        assertEquals(Intent.COST, Copilot.intentOf("충전비 얼마 나왔어?"))
        assertEquals(Intent.SERVICE, Copilot.intentOf("정비 뭐 챙겨야 해"))
        assertEquals(Intent.CHARGE_WHEN, Copilot.intentOf("언제 충전하는 게 싸?"))
        assertEquals(Intent.LAST_TRIP, Copilot.intentOf("방금 주행 어땠어"))
        assertEquals(Intent.COST, Copilot.intentOf("How much did I spend on charging?"))
        assertEquals(Intent.CHARGERS, Copilot.intentOf("주변 충전소 보여줘"))
        assertEquals("내비게이션에 주변 충전소를 띄웁니다.", Copilot.answer("근처 충전소", ctx()).text.ko)
        assertEquals(Intent.UNKNOWN, Copilot.intentOf("오늘 날씨 어때"))
        assertEquals(Intent.UNKNOWN, Copilot.intentOf("  "))
        assertEquals("지난 한 주 충전은 2번, 충전비는 17,000원이었습니다.", Copilot.answer("충전비 얼마야", ctx()).text.ko)
        assertEquals("지난 한 주는 3번, 89.5 km를 달렸습니다.", Copilot.answer("이번 주 얼마나 탔어", ctx()).text.ko)
        assertEquals("다음 정비는 캐빈 필터입니다. 7일 또는 10,910.5 km 남았습니다.", Copilot.answer("정비", ctx()).text.ko)
        assertTrue(Copilot.answer("정비", ctx(svc = null)).text.ko.startsWith("기준이 있는 정비 항목이 없습니다."))
        assertEquals("아직 기록된 주행이 없습니다.", Copilot.answer("방금 주행", ctx()).text.ko)
        assertTrue(Copilot.answer("날씨", ctx()).text.ko.startsWith("기록에서 답을 찾지 못했습니다."))
    }

    @Test
    fun `arrival recap reports what was observed and nothing else`() {
        val t = TripRecap(12.0, 18, 3.0, Origin.LIVE)
        assertEquals("도착했습니다. 이번 주행은 12 km, 18분이었습니다. 배터리는 3%를 사용했습니다.", Copilot.arrival(t).ko)
        assertEquals(4.0, Copilot.kmPerPercent(t)!!, 1e-9)
        val noSoc = TripRecap(12.0, 18, null, Origin.LIVE)
        assertTrue(Copilot.arrival(noSoc).ko.endsWith("배터리 사용량은 기록되지 않았습니다."))
        assertNull(Copilot.kmPerPercent(noSoc))
        assertNull(Copilot.kmPerPercent(TripRecap(12.0, 18, 0.0, Origin.LIVE)))
        assertTrue(Copilot.arrival(TripRecap(null, 5, null, Origin.LIVE)).ko.startsWith("도착했습니다. 이번 주행은 5분이었습니다."))
    }

    @Test
    fun `destination line estimates the arrival charge from the reported range`() {
        val now = ctx(soc = 50.0, range = 200.0).now
        assertEquals(37.5, Copilot.chargeAtDestination(now, 50.0)!!, 1e-9)
        assertTrue(Copilot.destinationLine(now, "판교", 50.0).ko.contains("도착하면 약 38%가 남습니다"))
        assertTrue(Copilot.destinationLine(now, "부산", 180.0).ko.contains("여유가 적습니다"))
        assertTrue(Copilot.destinationLine(now, "강릉", 260.0).ko.contains("현재 배터리로 갈 수 없습니다"))
        assertTrue(Copilot.destinationLine(ctx(soc = null).now, "판교", 50.0).ko.contains("배터리 값을 받은 뒤"))
    }

    @Test
    fun `speech spells out units`() {
        assertEquals("배터리는 74퍼센트, 주행 가능 거리는 296 킬로미터입니다.", Copilot.spoken("배터리는 74%, 주행 가능 거리는 296 km입니다.", true))
        assertEquals("74 percent, 296 kilometres, 30 kilowatt hours", Copilot.spoken("74%, 296 km, 30 kWh", false))
    }

    @Test
    fun `trip tracker starts on moving, ends on parked and ignores unknown`() {
        val t = TripTracker()
        val min = 60_000L
        assertNull(t.onReading(0, "parked", 12_014.0, 80.0))
        assertNull(t.onReading(1 * min, "unknown", 12_014.0, 80.0)) // shifting out of park
        assertNull(t.onReading(2 * min, "moving", 12_014.4, 80.0))
        assertTrue(t.inTrip)
        assertNull(t.onReading(8 * min, "unknown", 12_020.0, 79.0)) // stopped at a light in Drive
        assertNull(t.onReading(9 * min, "moving", 12_021.0, 78.0))
        val recap = t.onReading(14 * min, "parked", 12_026.0, 77.0)!!
        assertEquals(TripRecap(12.0, 12, 3.0, Origin.LIVE), recap) // distance and charge from the last parked reading
        assertFalse(t.inTrip)
        assertNull(t.onReading(15 * min, "parked", 12_026.0, 77.0))
    }

    @Test
    fun `trip tracker never reports zero movement or a charge gain as use`() {
        val t = TripTracker()
        t.onReading(0, "parked", 100.0, 50.0)
        t.onReading(60_000, "moving", 100.0, 50.0)
        assertNull(t.onReading(120_000, "parked", 100.0, 50.0)) // no distance gain: not a trip
        t.onReading(180_000, "moving", 100.0, 50.0)
        val gained = t.onReading(600_000, "parked", 103.0, 52.0)!!
        assertEquals(3.0, gained.distanceKm!!, 1e-9); assertNull(gained.socUsed)
        val blind = TripTracker()
        blind.onReading(0, "moving", null, null)
        assertEquals(TripRecap(null, 5, null, Origin.LIVE), blind.onReading(300_000, "parked", null, null))
    }

    @Test
    fun `automations only describe what would happen and say nothing was sent`() {
        assertEquals(5, Automations.rules.size)
        assertEquals(listOf(Needs.RECORDS, Needs.RECORDS, Needs.VEHICLE_CONTROL, Needs.VEHICLE_CONTROL, Needs.LOCATION), Automations.rules.map { it.needs })
        val byId = Automations.rules.associateBy { it.id }
        val met = Automations.simulate(byId.getValue("low_battery_parked"), ctx(soc = 18.0, parked = true))
        assertTrue(met.ko, "조건을 충족합니다" in met.ko && met.ko.endsWith("시연이며 실제로 보내지 않았습니다."))
        assertTrue(Automations.simulate(byId.getValue("low_battery_parked"), ctx(soc = 74.0)).ko.contains("충족하지 않습니다"))
        assertTrue(Automations.simulate(byId.getValue("low_battery_parked"), ctx(soc = null)).ko.contains("판단할 수 없습니다"))
        assertTrue(Automations.simulate(byId.getValue("precondition"), ctx()).ko.endsWith("차량에 명령을 보내지 않았습니다."))
        assertTrue(Automations.simulate(byId.getValue("home_arrival"), ctx()).ko.endsWith("위치를 읽지 않았습니다."))
        assertTrue(Automations.simulate(byId.getValue("service_due"), ctx()).ko.contains("예약 초안이 만들어집니다"))
    }
}
