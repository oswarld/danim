package app.danim.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutboxUploaderTest {
    private val now = 10_000_000L

    private fun uploader(store: OutboxStore, t: TelemetryTransport, stats: UploadStats = UploadStats.None) =
        OutboxUploader(store, t, DEV, stats = stats, clock = { now })

    @Test
    fun `acked events are deleted and unacked ones are kept`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 3, id = "a"), obs(now - 2, id = "b"), obs(now - 1, id = "c")), now, DEV)
        val stats = CountingStats()
        val t = ScriptedTransport { UploadResponse.Success(TelemetryAck(setOf("a", "c"), null)) } // server omitted b
        val out = uploader(store, t, stats).drain()
        assertEquals(listOf("b"), store.ids())
        // second pass re-sends only "b", gets no ack for it and retries later; "b" is never dropped
        assertEquals(UploadOutcome.RetryLater(2, 0, 30_000, "no_ack"), out)
        assertEquals(2, stats.acked)
    }

    @Test
    fun `transport failure keeps every row and reuses the same event ids on retry`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 2, id = "a"), obs(now - 1, id = "b")), now, DEV)
        var fail = true
        val t = ScriptedTransport { ev -> if (fail) UploadResponse.Transient(null, "io") else ackAll(ev) }
        val u = uploader(store, t)
        assertIs<UploadOutcome.RetryLater>(u.drain())
        assertEquals(listOf("a", "b"), store.ids())
        fail = false
        assertEquals(UploadOutcome.Progress(2, 0, false), u.drain())
        assertEquals(t.requests[0].map { it.eventId }, t.requests[1].map { it.eventId })
        assertTrue(store.ids().isEmpty())
    }

    @Test
    fun `valid events survive any number of transient or missing-ack attempts`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 2, id = "a"), obs(now - 1, id = "b")), now, DEV)
        val stats = CountingStats()
        var mode = 0
        val t = ScriptedTransport { ev ->
            when (mode % 4) {
                0 -> UploadResponse.Transient(null, "http_503")
                1 -> UploadResponse.Transient(5_000, "http_429")
                2 -> UploadResponse.Success(TelemetryAck(emptySet(), null))
                else -> UploadResponse.Success(null)
            }.also { mode++ }
        }
        val u = uploader(store, t, stats)
        repeat(100) { assertIs<UploadOutcome.RetryLater>(u.drain(runAttempt = it + 1)) }
        assertEquals(listOf("a", "b"), store.ids())
        assertTrue(store.quarantined().isEmpty())
        assertEquals(0, stats.expired + stats.quarantined)
        assertEquals(100, store.attempts("a"))
        // and they still upload once the server recovers
        val ok = OutboxUploader(store, ScriptedTransport { ackAll(it) }, DEV, clock = { now })
        assertEquals(UploadOutcome.Progress(2, 0, false), ok.drain())
    }

    @Test
    fun `2xx without a valid ack deletes nothing`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "a")), now, DEV)
        assertIs<UploadOutcome.RetryLater>(uploader(store, ScriptedTransport { UploadResponse.Success(null) }).drain())
        assertEquals(listOf("a"), store.ids())
    }

    @Test
    fun `acks for unknown ids are ignored`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "a")), now, DEV)
        uploader(store, ScriptedTransport { UploadResponse.Success(TelemetryAck(setOf("zzz"), null)) }).drain()
        assertEquals(listOf("a"), store.ids())
    }

    @Test
    fun `401 pauses for re-pairing and retains data`() = runTest {
        val store = InMemoryOutbox()
        store.insert(listOf(obs(now - 1, id = "a")), now, DEV)
        assertEquals(UploadOutcome.PausedReauth(401), uploader(store, ScriptedTransport { UploadResponse.AuthRejected(401) }).drain())
        assertEquals(listOf("a"), store.ids())
    }

    @Test
    fun `endpoint protocol and config errors retain the whole queue`() = runTest {
        for (code in listOf(301, 302, 307, 404, 405, 410, 415, 400, 409, 422)) {
            val store = InMemoryOutbox()
            store.insert((1..6).map { obs(now - 10 + it, id = "e$it") }, now, DEV)
            // body is not a contract error (e.g. HTML from a proxy or wrong path)
            val t = ScriptedTransport { HttpClassifier.classify(code, "<html>nope</html>", null) }
            val out = uploader(store, t).drain()
            assertEquals(UploadOutcome.EndpointError(code), out, "code $code")
            assertEquals(6, store.ids().size, "code $code")
            assertTrue(store.quarantined().isEmpty())
            assertEquals(1, t.requests.size) // no bisecting on endpoint errors
        }
    }

    private fun errorBody(code: String) = """{"error":{"code":"$code","message":"x"}}"""

    @Test
    fun `request-level and unknown contract errors retain rows without bisecting or quarantine`() = runTest {
        val cases = listOf(
            400 to "invalid_request", 400 to "invalid_json", 413 to "body_too_large", 422 to "invalid_request",
            409 to "conflict", 409 to "something_new", 400 to "event_conflict", 422 to "event_conflict",
            413 to "event_conflict", 409 to "invalid_observation", 400 to "invalid_observation",
        )
        for ((status, code) in cases) {
            for (size in listOf(1, 6)) {
                val store = InMemoryOutbox()
                val ids = (1..size).map { "e$it" }
                store.insert(ids.map { obs(now - 100 + it.drop(1).toInt(), id = it) }, now, DEV)
                val stats = CountingStats()
                val t = ScriptedTransport { HttpClassifier.classify(status, errorBody(code), null) }
                val out = uploader(store, t, stats).drain()
                val label = "$status/$code size=$size"
                assertEquals(UploadOutcome.EndpointError(status), out, label)
                assertEquals(ids, store.ids().sorted(), label)
                assertTrue(store.quarantined().isEmpty(), label)
                assertEquals(0, stats.quarantined, label)
                assertEquals(0, stats.acked, label)
                assertEquals(1, t.requests.size, label)
                assertEquals(ids, t.requests[0].map { it.eventId }, label)
            }
        }
    }

    @Test
    fun `only 409 event_conflict is classified event-level`() {
        assertEquals(UploadResponse.Rejected(409, "event_conflict"), HttpClassifier.classify(409, errorBody("event_conflict"), null))
        for (status in listOf(400, 413, 422)) {
            assertEquals(UploadResponse.EndpointError(status), HttpClassifier.classify(status, errorBody("event_conflict"), null))
        }
        for (code in listOf("invalid_request", "invalid_json", "body_too_large", "conflict", "invalid_observation", "Event_Conflict", "event_conflict ")) {
            assertEquals(UploadResponse.EndpointError(409), HttpClassifier.classify(409, errorBody(code), null), code)
        }
    }

    @Test
    fun `only a 409 event_conflict proves an event invalid and it is quarantined not deleted`() = runTest {
        val store = InMemoryOutbox()
        store.insert((1..5).map { obs(now - 100 + it, id = "e$it") }, now, DEV)
        val stats = CountingStats()
        val t = ScriptedTransport { ev ->
            if (ev.any { it.eventId == "e3" }) HttpClassifier.classify(409, """{"error":{"code":"event_conflict","message":"x"}}""", null)
            else ackAll(ev)
        }
        val out = uploader(store, t, stats).drain()
        assertEquals(UploadOutcome.Progress(4, 1, false), out)
        assertEquals(listOf("e3"), store.quarantined())
        assertTrue(store.ids().isEmpty())
        assertEquals(1, stats.quarantined)
        assertEquals(4, stats.acked)
    }

    @Test
    fun `batches never exceed 200 events and a run is bounded`() = runTest {
        val store = InMemoryOutbox()
        store.insert((1..450).map { obs(now - 1000 + it, id = "e$it") }, now, DEV)
        val t = ScriptedTransport { ackAll(it) }
        val out = OutboxUploader(store, t, DEV, maxBatchesPerRun = 2, clock = { now }).drain()
        assertEquals(listOf(200, 200), t.requests.map { it.size })
        assertEquals(UploadOutcome.Progress(400, 0, morePending = true), out)
        assertEquals(50, store.count(DEV))
    }

    @Test
    fun `retention removals are reported even when the run ends early`() = runTest {
        val store = InMemoryOutbox()
        val week = 7L * 24 * 3600 * 1000
        store.insert(listOf(obs(now - week - 5, id = "old"), obs(now - 3, id = "a"), obs(now - 2, id = "b"), obs(now - 1, id = "c")), now, DEV)
        val stats = CountingStats()
        val u = OutboxUploader(
            store, ScriptedTransport { UploadResponse.Transient(null, "io") }, DEV,
            retention = RetentionPolicy(maxRows = 2), stats = stats, clock = { now },
        )
        assertIs<UploadOutcome.RetryLater>(u.drain())
        assertEquals(2, stats.expired) // "old" by age, "a" by the row cap
        assertEquals(listOf("b", "c"), store.ids())
    }

    @Test
    fun `empty queue is idle`() = runTest {
        assertEquals(UploadOutcome.Idle, uploader(InMemoryOutbox(), ScriptedTransport { error("no call") }).drain())
    }
}
