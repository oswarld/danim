package app.danim.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEV_ID = PairingFixtures.DEVICE
private const val VEH_ID = PairingFixtures.VEHICLE
private const val TOKEN_A = PairingFixtures.TOKEN
private const val REPORT = "3f2c1b0a-9e8d-4c7b-a6f5-e4d3c2b1a090"
private const val AT = 1_800_000_000_000L
private const val RECEIVED = "2027-01-15T08:00:01.250Z"

private fun report(queue: QueueSnapshot? = QueueSnapshot(2, AT - 60_000), state: CollectorState = CollectorState.RUNNING) =
    CollectorReport(DEV_ID, REPORT, AT, collectionWanted = true, collectorState = state, queue = queue)

private fun ack(outcome: String, receivedAt: String? = RECEIVED, id: String = REPORT, extra: String = "") =
    """{"data":{"reportId":"$id","outcome":"$outcome","receivedAt":${receivedAt?.let { "\"$it\"" } ?: "null"}$extra}}"""

class CollectorReportWireTest {
    @Test
    fun `body has exactly the contract fields with strict types`() {
        val o = TelemetryWire.parseObject(CollectorReportWire.encode(report()))!!
        assertEquals(
            setOf("schemaVersion", "deviceId", "reportId", "reportedAt", "collectionWanted", "collectorState", "queuedObservationCount", "oldestQueuedObservedAt"),
            o.keys,
        )
        assertEquals(1, (o["schemaVersion"] as JsonPrimitive).intOrNull)
        assertEquals(DEV_ID, (o["deviceId"] as JsonPrimitive).content)
        assertEquals(REPORT, (o["reportId"] as JsonPrimitive).content)
        assertEquals(AT, Iso.parse((o["reportedAt"] as JsonPrimitive).content))
        assertEquals(true, (o["collectionWanted"] as JsonPrimitive).booleanOrNull)
        assertEquals("running", (o["collectorState"] as JsonPrimitive).content)
        assertFalse((o["queuedObservationCount"] as JsonPrimitive).isString)
        assertEquals(2, (o["queuedObservationCount"] as JsonPrimitive).intOrNull)
        assertEquals(AT - 60_000, Iso.parse((o["oldestQueuedObservedAt"] as JsonPrimitive).content))
        assertTrue(CollectorReportWire.encode(report()).toByteArray().size < 4096)
    }

    @Test
    fun `an unreadable queue is sent as unknown, never as zero`() {
        val o = TelemetryWire.parseObject(CollectorReportWire.encode(report(queue = null, state = CollectorState.UNKNOWN)))!!
        assertEquals(JsonNull, o["queuedObservationCount"])
        assertEquals(JsonNull, o["oldestQueuedObservedAt"])
        assertEquals("unknown", (o["collectorState"] as JsonPrimitive).content)
    }

    @Test
    fun `empty queue has a zero count and no oldest time`() {
        val o = TelemetryWire.parseObject(CollectorReportWire.encode(report(queue = QueueSnapshot(0, null), state = CollectorState.STOPPED)))!!
        assertEquals(0, (o["queuedObservationCount"] as JsonPrimitive).intOrNull)
        assertEquals(JsonNull, o["oldestQueuedObservedAt"])
    }

    @Test
    fun `an inconsistent or out-of-range snapshot is unknown rather than invented`() {
        for (q in listOf(QueueSnapshot(3, null), QueueSnapshot(0, AT), QueueSnapshot(-1, null), QueueSnapshot(1_000_001, AT))) {
            val o = TelemetryWire.parseObject(CollectorReportWire.encode(report(queue = q)))!!
            assertEquals(JsonNull, o["queuedObservationCount"], q.toString())
            assertEquals(JsonNull, o["oldestQueuedObservedAt"], q.toString())
        }
        val max = TelemetryWire.parseObject(CollectorReportWire.encode(report(queue = QueueSnapshot(1_000_000, AT))))!!
        assertEquals(1_000_000, (max["queuedObservationCount"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun `ack is parsed strictly against the sent report id`() {
        assertEquals(CollectorReportAck(REPORT, CollectorReportOutcome.STORED, Iso.parse(RECEIVED)), CollectorReportWire.parseAck(ack("stored"), REPORT))
        assertEquals(CollectorReportOutcome.DUPLICATE, CollectorReportWire.parseAck(ack("duplicate"), REPORT)!!.outcome)
        assertEquals(CollectorReportAck(REPORT, CollectorReportOutcome.SUPERSEDED, null), CollectorReportWire.parseAck(ack("superseded", null), REPORT))
        for (bad in listOf(
            ack("stored", id = "4f2c1b0a-9e8d-4c7b-a6f5-e4d3c2b1a090"), // another report's ack
            ack("stored", null),
            ack("duplicate", null),
            ack("superseded"),
            ack("accepted"),
            ack("stored", "yesterday"),
            ack("stored", extra = ",\"acceptedEventIds\":[]"),
            """{"reportId":"$REPORT","outcome":"stored","receivedAt":"$RECEIVED"}""", // no envelope
            """{"data":{"reportId":"$REPORT","outcome":"stored"}}""",
            "<html>proxy</html>",
            "",
        )) assertNull(CollectorReportWire.parseAck(bad, REPORT), bad)
    }

    @Test
    fun `ack envelope must contain only data`() {
        val valid = ack("stored")
        assertTrue(CollectorReportWire.parseAck(valid, REPORT) != null)
        assertNull(CollectorReportWire.parseAck(valid.dropLast(1) + ",\"extra\":true}", REPORT))
        assertNull(CollectorReportWire.parseAck(valid.dropLast(1) + ",\"error\":null}", REPORT))
        assertEquals(CollectorReportReply.Failed("malformed_ack"), CollectorReportWire.classify(200, valid.dropLast(1) + ",\"extra\":true}", REPORT))
    }

    @Test
    fun `status classification uses fixed reasons and never body text`() {
        assertIs<CollectorReportReply.Acked>(CollectorReportWire.classify(200, ack("stored"), REPORT))
        assertEquals(CollectorReportReply.Failed("malformed_ack"), CollectorReportWire.classify(200, ack("stored", id = REPORT.replace('3', '4')), REPORT))
        assertEquals(CollectorReportReply.Unauthorized, CollectorReportWire.classify(401, "", REPORT))
        assertEquals(CollectorReportReply.Conflict, CollectorReportWire.classify(409, """{"error":{"code":"collector_report_conflict","message":"x"}}""", REPORT))
        assertEquals(CollectorReportReply.Failed("http_409"), CollectorReportWire.classify(409, """{"error":{"code":"conflict","message":"x"}}""", REPORT))
        assertEquals(CollectorReportReply.Failed("http_503"), CollectorReportWire.classify(503, TOKEN_A, REPORT))
        assertEquals(CollectorReportReply.Failed("http_403"), CollectorReportWire.classify(403, "", REPORT))
    }
}

private class ScriptedReportApi(var reply: (CollectorReport) -> CollectorReportReply = { CollectorReportReply.Acked(CollectorReportAck(it.reportId, CollectorReportOutcome.STORED, AT)) }) : CollectorReportApi {
    val sent = mutableListOf<Pair<String, CollectorReport>>()
    override suspend fun report(token: String, report: CollectorReport): CollectorReportReply {
        sent += token to report
        return reply(report)
    }
}

class CollectorReporterTest {
    private val cred = DeviceCredential(DEV_ID, VEH_ID, TOKEN_A)

    @Test
    fun `report carries a fresh id, the sample time, this binding and its atomic queue snapshot`() = runTest {
        val api = ScriptedReportApi()
        val bindings = mutableListOf<String>()
        var now = AT
        val reporter = CollectorReporter(api, queue = { bindings += it; QueueSnapshot(4, AT - 5) }, collectionWanted = { false }, collectorState = { CollectorState.STOPPED }, clock = { now })
        reporter.send(cred)
        now += 1_000
        reporter.send(cred)
        assertEquals(listOf(DEV_ID, DEV_ID), bindings)
        val (first, second) = api.sent.map { it.second }
        assertEquals(TOKEN_A, api.sent.first().first)
        assertNotEquals(first.reportId, second.reportId)
        assertTrue(RenewalWire.isUuid(first.reportId))
        assertEquals(listOf(AT, AT + 1_000), api.sent.map { it.second.reportedAtMs })
        assertEquals(CollectorReport(DEV_ID, first.reportId, AT, false, CollectorState.STOPPED, QueueSnapshot(4, AT - 5)), first)
    }

    @Test
    fun `a failed queue read is reported as unknown, not as an empty queue`() = runTest {
        val api = ScriptedReportApi()
        CollectorReporter(api, queue = { throw IllegalStateException("db") }, collectionWanted = { true }, collectorState = { CollectorState.UNKNOWN }, clock = { AT }).send(cred)
        assertNull(api.sent.single().second.queue)
    }

    @Test
    fun `transport failure becomes a fixed failure and never escapes`() = runTest {
        val api = ScriptedReportApi { throw java.io.IOException("secret body") }
        val r = CollectorReporter(api, queue = { QueueSnapshot(0, null) }, collectionWanted = { true }, collectorState = { CollectorState.RUNNING }, clock = { AT }).send(cred)
        assertEquals(CollectorReportReply.Failed("IOException"), r)
    }
}

/** Ordering and failure isolation of the report inside the lifecycle-serialized upload cycle. */
class CollectorReportCycleTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private class Rig {
        val backend = object : RecordBackend {
            val m = HashMap<String, String>()
            override fun read(name: String) = m[name]
            override fun commit(puts: Map<String, String>, removes: Set<String>): Boolean { removes.forEach { m.remove(it) }; m.putAll(puts); return true }
        }
        val cipher = object : RecordCipher {
            override fun seal(name: String, plain: String) = plain
            override fun open(name: String, sealed: String) = sealed
        }
        val store = VaultStateStore(backend, cipher)
        val log = mutableListOf<String>()
        val metadata = ArrayDeque<MetadataReply>()
        val renewals = ArrayDeque<RenewReply>()
        val api = object : CredentialApi {
            override suspend fun metadata(token: String, deviceId: String): MetadataReply { log += "metadata"; return metadata.removeFirstOrNull() ?: MetadataReply.Unknown("unscripted") }
            override suspend fun renew(token: String, deviceId: String, requestId: String, replacementTokenHash: String): RenewReply { log += "renew"; return renewals.removeFirstOrNull() ?: RenewReply.Unknown("unscripted") }
        }
        val replacement = DeviceTokens.generate(object : SecureRandom() { override fun nextBytes(b: ByteArray) = b.fill(9) })
        val renewer = CredentialRenewer(store, api, object : SecureRandom() { override fun nextBytes(b: ByteArray) = b.fill(9) }, newRequestId = { "11111111-2222-4333-8444-555555555555" })
        val outbox = InMemoryOutbox()
        val reports = ScriptedReportApi()
        val reporter = CollectorReporter(reports, queue = { b -> QueueSnapshot(outbox.count(b), outbox.oldestObservedAtMs(b)) }, collectionWanted = { true }, collectorState = { CollectorState.RUNNING }, clock = { 1_800_000_000_000L })

        init { check(store.write(CredentialState(DeviceCredential(DEV_ID, VEH_ID, TOKEN_A)))) }

        suspend fun cycle(
            transport: (List<Observation>) -> UploadResponse = { ackAll(it) },
            beforeUpload: () -> Unit = {},
            onPaused: (DeviceCredential, UploadOutcome.PausedReauth) -> Unit = { _, _ -> log += "paused" },
        ) = renewAndUpload(LifecycleLock(), renewer, { store.current() }, run = { c ->
            beforeUpload()
            log += "telemetry"
            OutboxUploader(outbox, ScriptedTransport(transport), c.deviceId, clock = { 1_800_000_000_000L }).drain()
        }, onPausedReauth = onPaused, report = { c -> log += "report"; reporter.send(c) })
    }

    private fun meta(due: Boolean, requestId: String? = null) =
        MetadataReply.Ok(CredentialMetadata(DEV_ID, VEH_ID, now + 3 * day, now, due, requestId))

    @Test
    fun `an empty outbox still reports after renewal and telemetry, using the renewed credential`() = runTest {
        val r = Rig()
        r.metadata += meta(due = true)
        r.renewals += RenewReply.Ok(RenewalReceipt(DEV_ID, VEH_ID, "11111111-2222-4333-8444-555555555555", now + 90 * day))
        val cycle = r.cycle()!!
        assertEquals(listOf("metadata", "renew", "telemetry", "report"), r.log)
        assertEquals(UploadOutcome.Idle, cycle.upload)
        assertIs<CollectorReportReply.Acked>(cycle.report)
        assertEquals(r.replacement, r.reports.sent.single().first)
        assertEquals(QueueSnapshot(0, null), r.reports.sent.single().second.queue)
    }

    @Test
    fun `the snapshot is taken after telemetry so acknowledged rows are not reported as queued`() = runTest {
        val r = Rig()
        r.metadata += meta(due = false)
        r.outbox.insert(listOf(obs(now - 10, id = "a"), obs(now - 5, id = "b")), now, DEV_ID)
        // Only "a" is ever acknowledged; "b" stays queued (the uploader retries it once and then defers).
        r.cycle(transport = { events -> UploadResponse.Success(TelemetryAck(setOf("a").intersect(events.map { it.eventId }.toSet()), null)) })
        assertEquals(QueueSnapshot(1, now - 5), r.reports.sent.single().second.queue)
    }

    @Test
    fun `held or rejected renewal and paused telemetry send no report with the old token`() = runTest {
        val held = Rig().apply { check(store.write(CredentialState(store.current()!!, PendingRenewal("11111111-2222-4333-8444-555555555555", replacement)))) }
        held.metadata += MetadataReply.Unknown("IOException")
        assertNull(held.cycle()!!.report)
        assertTrue(held.reports.sent.isEmpty())

        val rejected = Rig()
        rejected.metadata += MetadataReply.Unauthorized
        assertNull(rejected.cycle()!!.report)
        assertTrue(rejected.reports.sent.isEmpty())

        val paused = Rig()
        paused.metadata += meta(due = false)
        paused.outbox.insert(listOf(obs(now - 1)), now, DEV_ID)
        assertNull(paused.cycle(transport = { UploadResponse.AuthRejected(401) })!!.report)
        assertTrue(paused.reports.sent.isEmpty())

        val none = Rig().apply { check(store.clear()) }
        assertNull(none.cycle())
        assertTrue(none.reports.sent.isEmpty())
    }

    @Test
    fun `report failures leave telemetry results, queue, renewal state and pause untouched`() = runTest {
        for (failure in listOf<(CollectorReport) -> CollectorReportReply>(
            { CollectorReportReply.Unauthorized },
            { CollectorReportReply.Conflict },
            { CollectorReportReply.Failed("http_503") },
            { throw IllegalStateException("boom") },
        )) {
            val r = Rig()
            r.reports.reply = failure
            r.metadata += meta(due = false)
            r.outbox.insert(listOf(obs(now - 2, id = "keep"), obs(now - 1, id = "acked")), now, DEV_ID)
            val before = r.store.read()
            val cycle = r.cycle(transport = { events -> UploadResponse.Success(TelemetryAck(setOf("acked").intersect(events.map { it.eventId }.toSet()), null)) })!!
            // Exactly the telemetry outcome the uploader alone produces for this script.
            assertEquals(UploadOutcome.RetryLater(acked = 1, quarantined = 0, delayMs = 30_000, reason = "no_ack"), cycle.upload)
            assertIs<CollectorReportReply>(cycle.report)
            assertFalse(cycle.report is CollectorReportReply.Acked)
            assertEquals(listOf("keep"), r.outbox.ids())
            assertTrue(r.outbox.quarantined().isEmpty())
            assertEquals(before, r.store.read())
            assertFalse("paused" in r.log)
        }
    }

    @Test
    fun `a throwing report hook cannot fail the cycle or hide its telemetry outcome`() = runTest {
        val r = Rig()
        r.metadata += meta(due = false)
        r.outbox.insert(listOf(obs(now - 1, id = "x")), now, DEV_ID)
        val cycle = renewAndUpload(LifecycleLock(), r.renewer, { r.store.current() }, run = { c ->
            OutboxUploader(r.outbox, ScriptedTransport { ackAll(it) }, c.deviceId, clock = { now }).drain()
        }, onPausedReauth = { _, _ -> error("no pause") }, report = { throw IllegalStateException("hook") })!!
        assertEquals(UploadOutcome.Progress(acked = 1, quarantined = 0, morePending = false), cycle.upload)
        assertEquals(CollectorReportReply.Failed("IllegalStateException"), cycle.report)
    }

    @Test
    fun `a binding replaced during the cycle is not reported with the old credential`() = runTest {
        val r = Rig()
        r.metadata += meta(due = false)
        val newer = DeviceCredential("7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d", VEH_ID, "dnm_" + "N".repeat(43))
        val cycle = r.cycle(beforeUpload = { check(r.store.write(CredentialState(newer))) })!!
        assertNull(cycle.report)
        assertTrue(r.reports.sent.isEmpty())
    }
}
