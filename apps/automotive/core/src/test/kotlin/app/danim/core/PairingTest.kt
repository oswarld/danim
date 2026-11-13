package app.danim.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedPairingApi(private val results: ArrayDeque<PollResult>) : PairingApi {
    var polls = 0
    override suspend fun start() = Result.failure<PairingStart>(UnsupportedOperationException())
    override suspend fun poll(pairingId: String, pairingSecret: String): PollResult {
        polls++
        return results.removeFirstOrNull() ?: PollResult.Pending
    }
}

internal object PairingFixtures {
    const val PAIRING = "7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6b"
    const val DEVICE = "0d9c8b7a-6e5f-4d3c-8b2a-1f0e9d8c7b6a"
    const val VEHICLE = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    const val CODE = "ABCD2345"
    const val SECRET = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U-_"
    const val TOKEN = "dnm_" + SECRET
    const val EXPIRES = "2026-01-01T00:10:00Z"

    fun start(
        id: String? = PAIRING, code: String? = CODE, secret: String? = SECRET, expires: String? = EXPIRES,
        extra: String = "",
    ) = body("pairingId" to id, "code" to code, "pairingSecret" to secret, "expiresAt" to expires, extra = extra)

    fun claimed(
        device: String? = DEVICE, vehicle: String? = VEHICLE, token: String? = TOKEN, extra: String = "",
    ) = body("status" to "claimed", "deviceId" to device, "vehicleId" to vehicle, "deviceToken" to token, extra = extra)

    /** A null value omits the key; a value starting with `#` is emitted as a raw JSON literal. */
    fun body(vararg fields: Pair<String, String?>, extra: String = ""): String =
        fields.filter { it.second != null }.joinToString(",", "{", extra + "}") { (k, v) ->
            if (v!!.startsWith("#")) "\"$k\":${v.drop(1)}" else "\"$k\":\"$v\""
        }
}

class PairingPollerTest {
    private val cred = DeviceCredential(PairingFixtures.DEVICE, PairingFixtures.VEHICLE, PairingFixtures.TOKEN)
    private val start = PairingStart(PairingFixtures.PAIRING, PairingFixtures.CODE, PairingFixtures.SECRET, expiresAtMs = 600_000)

    @Test
    fun `polls every five seconds until claimed`() = runTest {
        var now = 0L
        val delays = mutableListOf<Long>()
        val api = ScriptedPairingApi(ArrayDeque(listOf(PollResult.Pending, PollResult.Pending, PollResult.Claimed(cred))))
        val end = PairingPoller(api, { now }, { now += it; delays += it }).run(start)
        assertEquals(PairingEnd.Claimed(cred), end)
        assertEquals(listOf(5_000L, 5_000L), delays)
        assertEquals(3, api.polls)
    }

    @Test
    fun `stops polling at expiry without extra requests`() = runTest {
        var now = 0L
        val api = ScriptedPairingApi(ArrayDeque())
        val end = PairingPoller(api, { now }, { now += it }).run(start)
        assertEquals(PairingEnd.Expired, end)
        assertEquals(120, api.polls) // 600s / 5s, never past expiresAt
        assertTrue(now <= start.expiresAtMs)
    }

    @Test
    fun `server reported expiry ends the flow`() = runTest {
        val end = PairingPoller(ScriptedPairingApi(ArrayDeque(listOf(PollResult.Expired))), { 0 }, {}).run(start)
        assertEquals(PairingEnd.Expired, end)
    }

    @Test
    fun `transient failures are tolerated then give up`() = runTest {
        var now = 0L
        val api = ScriptedPairingApi(ArrayDeque(List(5) { PollResult.TransientFailure("io") }))
        val end = PairingPoller(api, { now }, { now += it }, maxTransientFailures = 3).run(start)
        assertEquals(PairingEnd.Failed("io"), end)
        assertEquals(4, api.polls)
    }

    @Test
    fun `a pending reply resets the failure counter`() = runTest {
        var now = 0L
        val results = ArrayDeque<PollResult>(
            listOf(PollResult.TransientFailure("io"), PollResult.TransientFailure("io"), PollResult.Pending,
                PollResult.TransientFailure("io"), PollResult.Claimed(cred)),
        )
        val end = PairingPoller(ScriptedPairingApi(results), { now }, { now += it }, maxTransientFailures = 2).run(start)
        assertEquals(PairingEnd.Claimed(cred), end)
    }

    @Test
    fun `contract poll interval cannot be lowered`() {
        assertFailsWith<IllegalArgumentException> { PairingPoller(ScriptedPairingApi(ArrayDeque()), minIntervalMs = 1_000) }
    }
}

class PairingWireTest {
    private val F = PairingFixtures

    @Test
    fun `parses RFC3339 database expiry offsets with submillisecond precision`() {
        for (expiry in listOf(
            "2026-01-01T00:10:00.76002Z",
            "2026-01-01T00:10:00.76002+00:00",
            "2026-01-01T09:10:00.76002+09:00",
            "2025-12-31T17:10:00.76002-07:00",
        )) {
            val parsed = PairingWire.parseStart(F.start(expires = expiry))
            assertNotNull(parsed)
            assertEquals(1767226200760L, parsed.expiresAtMs)
        }
    }

    @Test
    fun `invalid or missing expiry offsets are rejected`() {
        for (expiry in listOf("invalid", "2026-01-01T00:10:00", "2026-02-30T00:00:00Z", "#1767226200", "#null", "#true")) {
            assertNull(PairingWire.parseStart(F.start(expires = expiry)), expiry)
        }
        assertNull(PairingWire.parseStart(F.start(expires = null)))
    }

    @Test
    fun `parses start response and tolerates additive fields`() {
        val s = PairingWire.parseStart(F.start(extra = ""","pollIntervalSeconds":5,"note":"x" """.trim()))!!
        assertEquals(F.PAIRING, s.pairingId)
        assertEquals(F.CODE, s.code)
        assertEquals(F.SECRET, s.pairingSecret)
        assertEquals(Iso.parse(F.EXPIRES), s.expiresAtMs)
        assertNull(PairingWire.parseStart("""{"pairingId":"${F.PAIRING}"}"""))
    }

    @Test
    fun `start rejects malformed identifiers code and secret`() {
        val badUuids = listOf(
            "p", "", "7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6", "7b1f6a523c4d4e8f9a0b1c2d3e4f5a6b",
            "7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6bb", "7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6g",
            "{7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6b}", " 7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6b",
            "7b1f6a52-3c4d-4e8f-9a0b-1c2d3e4f5a6b\n", "7b1f6a52-3c4d-0e8f-9a0b-1c2d3e4f5a6b",
            "7b1f6a52-3c4d-4e8f-0a0b-1c2d3e4f5a6b", "#123", "#true", "#null", "#[]", "#{}",
        )
        for (v in badUuids) assertNull(PairingWire.parseStart(F.start(id = v)), "id $v")
        assertNull(PairingWire.parseStart(F.start(id = null)))

        val badCodes = listOf("", "ABCD234", "ABCD23456", "ABCD-234", "abcd2345", "ABCDO234", "ABCDI234",
            "ABCD0234", "ABCD1234", "ABCD 345", "#12345678", "#true", "#null")
        for (v in badCodes) assertNull(PairingWire.parseStart(F.start(code = v)), "code $v")
        assertNull(PairingWire.parseStart(F.start(code = null)))

        val badSecrets = listOf("", "s", F.SECRET.dropLast(1), F.SECRET + "A", F.SECRET.dropLast(1) + "+",
            F.SECRET.dropLast(1) + "/", F.SECRET.dropLast(1) + "=", F.SECRET.dropLast(1) + " ", "#true", "#null", "#12")
        for (v in badSecrets) assertNull(PairingWire.parseStart(F.start(secret = v)), "secret")
        assertNull(PairingWire.parseStart(F.start(secret = null)))
    }

    @Test
    fun `start rejects non-object bodies`() {
        for (b in listOf("", "garbage", "[]", "null", "42")) assertNull(PairingWire.parseStart(b))
    }

    @Test
    fun `parses poll responses`() {
        assertEquals(PollResult.Pending, PairingWire.parsePoll("""{"status":"pending"}"""))
        assertEquals(PollResult.Expired, PairingWire.parsePoll("""{"status":"expired"}"""))
        assertEquals(
            PollResult.Claimed(DeviceCredential(F.DEVICE, F.VEHICLE, F.TOKEN)),
            PairingWire.parsePoll(F.claimed(extra = ""","futureField":{"a":1}""")),
        )
        assertTrue(PairingWire.parsePoll("garbage") is PollResult.TransientFailure)
    }

    @Test
    fun `status must be an explicit string`() {
        for (b in listOf("""{"status":1}""", """{"status":true}""", """{"status":null}""", """{"status":["pending"]}""",
            """{}""", """{"status":"Pending"}""", """{"status":"other"}""")) {
            assertTrue(PairingWire.parsePoll(b) is PollResult.TransientFailure, b)
        }
    }

    @Test
    fun `claimed requires strictly shaped device vehicle and token`() {
        val badIds = listOf("d", "", "#1", "#true", "#null", "0d9c8b7a-6e5f-4d3c-8b2a-1f0e9d8c7b6", "not-a-uuid-at-all",
            "0d9c8b7a-6e5f-0d3c-8b2a-1f0e9d8c7b6a")
        for (v in badIds) {
            assertTrue(PairingWire.parsePoll(F.claimed(device = v)) is PollResult.Fatal, "device $v")
            assertTrue(PairingWire.parsePoll(F.claimed(vehicle = v)) is PollResult.Fatal, "vehicle $v")
        }
        assertTrue(PairingWire.parsePoll(F.claimed(device = null)) is PollResult.Fatal)
        assertTrue(PairingWire.parsePoll(F.claimed(vehicle = null)) is PollResult.Fatal)
        assertTrue(PairingWire.parsePoll("""{"status":"claimed"}""") is PollResult.Fatal)

        val body = F.SECRET
        val badTokens = listOf("", "t", body, "dnm-$body", "DNM_$body", "dnm_" + body.dropLast(1), "dnm_" + body + "A",
            "dnm_" + body.dropLast(1) + "+", "dnm_" + body.dropLast(1) + "=", "dnm_" + body.dropLast(1) + " ",
            "dnm_dnm_$body", "#12", "#true", "#null")
        for (v in badTokens) assertTrue(PairingWire.parsePoll(F.claimed(token = v)) is PollResult.Fatal, "token")
        assertTrue(PairingWire.parsePoll(F.claimed(token = null)) is PollResult.Fatal)
    }

    @Test
    fun `malformed claimed failures are fixed sanitized reasons`() {
        val secretish = "dnm_" + "Z".repeat(43) + "!"
        val r = PairingWire.parsePoll(F.claimed(token = secretish, device = "leaky-device-id"))
        val reason = (r as PollResult.Fatal).reason
        assertEquals(PairingWire.parsePoll(F.claimed(token = "x")).let { (it as PollResult.Fatal).reason }, reason)
        assertTrue(!reason.contains("leaky") && !reason.contains("dnm_") && !reason.contains("Z"))
    }

    @Test
    fun `classifies HTTP replies without treating errors as expiry or claim`() {
        assertEquals(PollResult.Pending, PairingWire.classifyPoll(200, """{"status":"pending"}"""))
        assertEquals(PollResult.Expired, PairingWire.classifyPoll(200, """{"status":"expired"}"""))
        assertEquals(
            PollResult.Claimed(DeviceCredential(F.DEVICE, F.VEHICLE, F.TOKEN)),
            PairingWire.classifyPoll(200, F.claimed()),
        )
        assertTrue(PairingWire.classifyPoll(200, F.claimed(token = "bad")) is PollResult.Fatal)
        for (code in listOf(404, 410)) {
            for (b in listOf("""{"status":"expired"}""", F.claimed(), """{"error":{"code":"not_found"}}""", "")) {
                val r = PairingWire.classifyPoll(code, b)
                assertTrue(r is PollResult.Fatal, "$code $b")
                assertEquals(PairingWire.classifyPoll(code, "x"), r)
            }
        }
        for (code in listOf(301, 302, 307, 308, 400, 401, 403, 409, 422)) {
            assertTrue(PairingWire.classifyPoll(code, F.claimed()) is PollResult.Fatal, "$code")
            assertTrue(PairingWire.classifyPoll(code, """{"status":"expired"}""") is PollResult.Fatal, "$code")
        }
        for (code in listOf(429, 500, 502, 503, 599)) {
            assertTrue(PairingWire.classifyPoll(code, F.claimed()) is PollResult.TransientFailure, "$code")
            assertTrue(PairingWire.classifyPoll(code, """{"status":"expired"}""") is PollResult.TransientFailure, "$code")
        }
    }
}

class ApiConfigTest {
    @Test
    fun `requires https except for loopback aliases`() {
        assertNotNull(ApiConfig.parse("https://x.supabase.co/functions/v1/api/", "https://danim.example"))
        assertNotNull(ApiConfig.parse("http://10.0.2.2:54381/functions/v1/api", "http://localhost:3000", allowLocalHttp = true))
        assertNotNull(ApiConfig.parse("http://127.0.0.1:54381/functions/v1/api", "http://localhost:3000", allowLocalHttp = true))
        // release builds (allowLocalHttp = false) accept https only, even for loopback
        assertNull(ApiConfig.parse("http://10.0.2.2:54381/functions/v1/api", "https://danim.example"))
        assertNull(ApiConfig.parse("https://x.supabase.co/functions/v1/api", "http://localhost:3000"))
        assertNull(ApiConfig.parse("http://evil.example/api", "https://danim.example", allowLocalHttp = true))
        assertNull(ApiConfig.parse("http://10.0.2.20/api", "https://danim.example", allowLocalHttp = true))
        assertNull(ApiConfig.parse("http://localhost.evil.example/api", "https://danim.example", allowLocalHttp = true))
        assertNull(ApiConfig.parse("https://u:p@x.example/api", "https://danim.example"))
        assertNull(ApiConfig.parse("not a url", "https://danim.example"))
    }

    @Test
    fun `builds endpoint and pairing link`() {
        val c = ApiConfig.parse("https://x.supabase.co/functions/v1/api/", "https://danim.example/")!!
        assertEquals("https://x.supabase.co/functions/v1/api/telemetry", c.endpoint("/telemetry"))
        assertEquals("https://danim.example/pair?code=AB+C", c.pairingLink("AB C"))
    }
}

class QrTest {
    @Test
    fun `produces a square matrix with finder patterns`() {
        val m = Qr.matrix("https://danim.example/pair?code=ABCD1234")
        assertTrue(m.size >= 21)
        assertTrue(m.all { it.size == m.size })
        assertTrue(m[0][0] && m[0][6] && m[6][0] && m[6][6]) // top-left finder corners
    }
}
