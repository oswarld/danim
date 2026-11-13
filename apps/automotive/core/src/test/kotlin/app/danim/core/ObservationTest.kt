package app.danim.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservationTest {
    private val ids = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator()
    private val f = ObservationFactory { ids.next() }

    @Test
    fun `out of range or non finite values become unavailable with null value`() {
        for ((s, v) in listOf(
            Signal.BATTERY_SOC_PERCENT to Value.Num(101.0),
            Signal.BATTERY_SOC_PERCENT to Value.Num(-0.1),
            Signal.ODOMETER_KM to Value.Num(-1.0),
            Signal.ODOMETER_KM to Value.Num(Double.NaN),
            Signal.RANGE_KM to Value.Num(Double.POSITIVE_INFINITY),
            Signal.VEHICLE_MOTION to Value.Text("flying"),
            Signal.TIRE_LOW_PRESSURE to Value.Num(1.0),
            Signal.VEHICLE_WARNING to Value.Text("x".repeat(201)),
        )) {
            val o = f.value(s, v, 1)
            assertEquals(Availability.UNAVAILABLE, o.availability, "$s $v")
            assertNull(o.value)
        }
    }

    @Test
    fun `boundary values are accepted`() {
        assertEquals(Availability.AVAILABLE, f.value(Signal.BATTERY_SOC_PERCENT, Value.Num(0.0), 1).availability)
        assertEquals(Availability.AVAILABLE, f.value(Signal.BATTERY_SOC_PERCENT, Value.Num(100.0), 1).availability)
        assertEquals(Availability.AVAILABLE, f.value(Signal.VEHICLE_WARNING, Value.Text("x".repeat(200)), 1).availability)
    }

    @Test
    fun `non available observation cannot carry a value`() {
        assertFailsWith<IllegalArgumentException> {
            Observation("x", 1, Signal.ODOMETER_KM, Availability.UNSUPPORTED, Value.Num(0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            Observation("x", 1, Signal.ODOMETER_KM, Availability.AVAILABLE, null)
        }
    }

    @Test
    fun `every observation gets a distinct event id`() {
        val a = f.missing(Signal.IGNITION_STATE, Availability.UNSUPPORTED, 1)
        val b = f.missing(Signal.IGNITION_STATE, Availability.UNSUPPORTED, 1)
        assertNotEquals(a.eventId, b.eventId)
    }

    @Test
    fun `wire json matches the contract and keeps unknown as null never zero`() {
        val events = listOf(
            f.value(Signal.ODOMETER_KM, Value.Num(1234.5), 1_700_000_000_000),
            f.missing(Signal.BATTERY_SOC_PERCENT, Availability.PERMISSION_DENIED, 1_700_000_001_000),
            f.value(Signal.TIRE_LOW_PRESSURE, Value.Bool(false), 1_700_000_002_000),
            f.value(Signal.VEHICLE_MOTION, Value.Text("parked"), 1_700_000_003_000),
        )
        val root = TelemetryWire.parseObject(TelemetryWire.encode("dev-1", events))!!
        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("dev-1", root["deviceId"]!!.jsonPrimitive.content)
        val arr = root["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("eventId", "observedAt", "signal", "status", "source", "value"), arr[0].keys)
        assertEquals("2023-11-14T22:13:20Z", arr[0]["observedAt"]!!.jsonPrimitive.content)
        assertEquals("odometer_km", arr[0]["signal"]!!.jsonPrimitive.content)
        assertEquals(1234.5, arr[0]["value"]!!.jsonPrimitive.double)
        assertEquals("permission_denied", arr[1]["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, arr[1]["value"])
        assertFalse(arr[2]["value"]!!.jsonPrimitive.boolean)
        assertEquals("parked", arr[3]["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `source defaults to pleos and is preserved on the wire and across retries`() {
        val pleos = f.value(Signal.ODOMETER_KM, Value.Num(1.0), 1)
        val aaos = f.value(Signal.RANGE_KM, Value.Num(2.0), 2, Source.AAOS_CAR_PROPERTY)
        val missing = f.missing(Signal.IGNITION_STATE, Availability.UNSUPPORTED, 3, Source.AAOS_CAR_PROPERTY)
        val fromReading = f.fromReading(Signal.CHARGING_STATE, Availability.AVAILABLE, Value.Text("idle"), 4, Source.AAOS_CAR_PROPERTY)
        assertEquals(Source.PLEOS_VEHICLE_SDK, pleos.source)
        assertEquals(Source.PLEOS_VEHICLE_SDK, Observation("x", 1, Signal.ODOMETER_KM, Availability.UNSUPPORTED, null).source)
        val events = listOf(pleos, aaos, missing, fromReading)
        fun sources(body: String) = TelemetryWire.parseObject(body)!!["events"]!!.jsonArray.map { it.jsonObject["source"]!!.jsonPrimitive.content }
        val first = TelemetryWire.encode("d", events)
        assertEquals(listOf("pleos_vehicle_sdk", "aaos_car_property", "aaos_car_property", "aaos_car_property"), sources(first))
        // a retry re-encodes the stored observations: identical ids and sources
        assertEquals(first, TelemetryWire.encode("d", events))
        assertEquals(Source.AAOS_CAR_PROPERTY, Source.fromWire("aaos_car_property"))
        assertNull(Source.fromWire("other"))
    }

    @Test
    fun `invalid reading is downgraded but keeps its source`() {
        val o = f.fromReading(Signal.RANGE_KM, Availability.AVAILABLE, Value.Num(-5.0), 1, Source.AAOS_CAR_PROPERTY)
        assertEquals(Availability.UNAVAILABLE, o.availability)
        assertEquals(Source.AAOS_CAR_PROPERTY, o.source)
    }

    @Test
    fun `encode rejects empty and oversized requests`() {
        assertFailsWith<IllegalArgumentException> { TelemetryWire.encode("d", emptyList()) }
        val many = (1..201).map { obs(it.toLong()) }
        assertFailsWith<IllegalArgumentException> { TelemetryWire.encode("d", many) }
        TelemetryWire.encode("d", many.take(200))
    }

    @Test
    fun `ack and error parsing`() {
        val ack = TelemetryWire.parseAck("""{"acceptedEventIds":["a","b"],"receivedAt":"2026-01-01T00:00:00Z"}""")!!
        assertEquals(setOf("a", "b"), ack.acceptedEventIds)
        assertNull(TelemetryWire.parseAck("not json"))
        assertNull(TelemetryWire.parseAck("""{"ok":true}"""))
        assertEquals("rate_limited", TelemetryWire.parseErrorCode("""{"error":{"code":"rate_limited","message":"m"}}"""))
    }

    @Test
    fun `http classification follows the contract`() {
        assertTrue(HttpClassifier.classify(200, """{"acceptedEventIds":[]}""", null) is UploadResponse.Success)
        assertTrue(HttpClassifier.classify(401, "", null) is UploadResponse.AuthRejected)
        assertTrue(HttpClassifier.classify(403, "", null) is UploadResponse.AuthRejected)
        assertEquals(UploadResponse.Transient(5000, "http_429"), HttpClassifier.classify(429, "", "5"))
        assertEquals(UploadResponse.Transient(null, "http_503"), HttpClassifier.classify(503, "", "Wed, 21 Oct"))
        assertEquals(UploadResponse.Rejected(409, "event_conflict"), HttpClassifier.classify(409, """{"error":{"code":"event_conflict"}}""", null))
        assertEquals(UploadResponse.EndpointError(400), HttpClassifier.classify(400, """{"error":{"code":"bad"}}""", null))
        assertEquals(UploadResponse.EndpointError(400), HttpClassifier.classify(400, "<html>", null))
        assertEquals(UploadResponse.EndpointError(404), HttpClassifier.classify(404, """{"error":{"code":"not_found"}}""", null))
        assertEquals(UploadResponse.EndpointError(405), HttpClassifier.classify(405, "", null))
        assertEquals(UploadResponse.EndpointError(302), HttpClassifier.classify(302, "", null))
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val b = BackoffPolicy(baseMs = 30_000, maxMs = 900_000)
        assertEquals(30_000, b.delayMs(1))
        assertEquals(60_000, b.delayMs(2))
        assertEquals(900_000, b.delayMs(10))
        assertEquals(900_000, b.delayMs(500))
        assertEquals(120_000, b.delayMs(1, retryAfterMs = 120_000))
        assertEquals(900_000, b.delayMs(1, retryAfterMs = 3_600_000))
    }
}
