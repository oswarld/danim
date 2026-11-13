package app.danim.core.copilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gleo callbacks carry the guide's fields, say where the values come from, and never invent a reading. */
class GleoTest {
    private val plan = DayPlan(Say("판교 미팅", "Pangyo meeting"), "09:30", 62.0)
    private val tariff = Tariff(23, 9, 120, 250, 77.4)
    private fun ctx(origin: Origin, soc: Double? = 62.0) = CopilotContext(
        CarNow(soc, 310.0, 12_046.0, true, origin), Week(3, 89.5, 2, 17_000), NextService(Say("캐빈 필터", "Cabin filter"), 7, null), plan, tariff, null, 8,
    )

    @Test
    fun `action names resolve with or without the package prefix`() {
        assertEquals(Gleo.BRIEFING, Gleo.functionOf("app.danim.automotive.GET_VEHICLE_BRIEFING"))
        assertEquals(Gleo.CHARGERS, Gleo.functionOf("SHOW_NEARBY_CHARGERS"))
        assertNull(Gleo.functionOf("app.danim.automotive.START_CHARGING")); assertNull(Gleo.functionOf(null))
    }

    @Test
    fun `briefing answers from live readings and labels the fixed record`() {
        val live = Gleo.handle(Gleo.BRIEFING, null, ctx(Origin.LIVE))
        assertEquals("success", live.status)
        assertTrue(live.message, live.message.startsWith("The battery is at 62% with 310 km of range."))
        val row = Json.parseToJsonElement(live.data).jsonArray.single().jsonObject
        assertEquals("live_vehicle_reading", row.getValue("source").jsonPrimitive.content)
        assertEquals(62.0, row.getValue("batteryPercent").jsonPrimitive.content.toDouble(), 0.0)
        val fixed = Gleo.handle(Gleo.BRIEFING, null, ctx(Origin.FIXED))
        assertTrue(fixed.message, fixed.message.startsWith("From DANIM's fixed demo record, not this car:"))
        val unknown = Gleo.handle(Gleo.BRIEFING, null, ctx(Origin.LIVE, soc = null))
        assertEquals("unknown", Json.parseToJsonElement(unknown.data).jsonArray.single().jsonObject.getValue("batteryPercent").jsonPrimitive.content)
        assertFalse(unknown.message, "0%" in unknown.message)
    }

    @Test
    fun `a question needs a known topic and otherwise asks for one`() {
        val cost = Gleo.handle(Gleo.QUESTION, """{"topic":"charging_cost"}""", ctx(Origin.LIVE))
        assertEquals("Last week you charged 2 times for 17,000 KRW.", cost.message)
        for (bad in listOf(null, "{}", "not json", """{"topic":"weather"}""")) {
            val r = Gleo.handle(Gleo.QUESTION, bad, ctx(Origin.LIVE))
            assertEquals("asking", r.status); assertTrue(r.message, "charging_cost" in r.message)
        }
        assertEquals(7, Gleo.topics.size)
    }

    @Test
    fun `chargers hand off to navigation and unknown functions fail`() {
        val c = Gleo.handle(Gleo.CHARGERS, null, ctx(Origin.LIVE))
        assertTrue(c.showChargers); assertEquals("success", c.status)
        assertEquals("fail", Gleo.handle("START_CHARGING", null, ctx(Origin.LIVE)).status)
        val json = Json.parseToJsonElement(c.json()).jsonObject
        assertEquals(setOf("functionName", "status", "message", "mode", "data"), json.keys)
        assertEquals("voice", json.getValue("mode").jsonPrimitive.content)
    }
}
