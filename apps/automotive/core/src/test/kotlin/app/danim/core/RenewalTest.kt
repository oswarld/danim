package app.danim.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEVICE = PairingFixtures.DEVICE
private const val VEHICLE = PairingFixtures.VEHICLE
private const val OLD = PairingFixtures.TOKEN
private const val REQ_1 = "11111111-2222-4333-8444-555555555555"
private const val REQ_2 = "66666666-7777-4888-9999-aaaaaaaaaaaa"
private const val OTHER_DEVICE = "99999999-8888-4777-a666-555555555555"
private const val NOW = 1_800_000_000_000L
private const val DAY = 86_400_000L

/** Synthetic durable storage: [disk] survives a "process restart"; [ram] is the in-process cache that may drift. */
private class FakeBackend : RecordBackend {
    val disk = HashMap<String, String>()
    var ram = HashMap<String, String>()
    /** Next commits fail. [ramChanges] reproduces SharedPreferences updating its memory cache before a failed disk write. */
    var failCommits = 0
    var ramChanges = true
    var commits = 0
    /** 1-based commit numbers that fail (e.g. only the rollback after a successful write). */
    val failCommitNumbers = mutableSetOf<Int>()

    override fun read(name: String): String? = ram[name]
    override fun commit(puts: Map<String, String>, removes: Set<String>): Boolean {
        commits++
        if (failCommits > 0 || commits in failCommitNumbers) {
            if (failCommits > 0) failCommits--
            if (ramChanges) { removes.forEach { ram.remove(it) }; ram.putAll(puts) }
            return false
        }
        removes.forEach { ram.remove(it); disk.remove(it) }
        ram.putAll(puts); disk.putAll(puts)
        return true
    }

    /** A new process sees only what reached disk. */
    fun restart() { ram = HashMap(disk) }
}

/** Reversible stand-in for the Keystore cipher; [failSeal]/[failOpen] simulate key or encryption failures. */
private class FakeCipher : RecordCipher {
    var failSeal = false
    var failOpen = false
    /** The next N decrypts fail, then decryption works again (a transient Keystore/read-back failure). */
    var failOpens = 0
    override fun seal(name: String, plain: String): String {
        if (failSeal) throw IllegalStateException("seal_failed")
        return "sealed:$name:" + plain.reversed()
    }
    override fun open(name: String, sealed: String): String {
        if (failOpen) throw IllegalStateException("open_failed")
        if (failOpens > 0) { failOpens--; throw IllegalStateException("open_failed") }
        val prefix = "sealed:$name:"
        require(sealed.startsWith(prefix)) { "aad_mismatch" }
        return sealed.removePrefix(prefix).reversed()
    }
}

private data class ApiCall(val route: String, val token: String, val body: Map<String, String>)

/** Records every request; replies come from per-token scripts so a test states which credential gets which answer. */
private class ScriptedCredentialApi : CredentialApi {
    val calls = mutableListOf<ApiCall>()
    val metadataReplies = HashMap<String, ArrayDeque<MetadataReply>>()
    val renewReplies = ArrayDeque<RenewReply>()
    /** State that was durable on "disk" at the moment of each renew dispatch. */
    val durableAtRenew = mutableListOf<CredentialState?>()
    var backendForDurability: FakeBackend? = null
    var beforeReply: suspend () -> Unit = {}

    fun meta(token: String, vararg replies: MetadataReply) {
        metadataReplies.getOrPut(token) { ArrayDeque() }.addAll(replies)
    }

    override suspend fun metadata(token: String, deviceId: String): MetadataReply {
        calls += ApiCall("devices/credential", token, mapOf("deviceId" to deviceId))
        beforeReply()
        return metadataReplies[token]?.removeFirstOrNull() ?: MetadataReply.Unknown("unscripted")
    }

    override suspend fun renew(token: String, deviceId: String, requestId: String, replacementTokenHash: String): RenewReply {
        calls += ApiCall("devices/renew", token, mapOf("deviceId" to deviceId, "requestId" to requestId, "replacementTokenHash" to replacementTokenHash))
        backendForDurability?.let { b ->
            val probe = FakeBackend().apply { disk.putAll(b.disk); restart() }
            durableAtRenew += (VaultStateStore(probe, FakeCipher()).read() as? StoredCredential.Present)?.state
        }
        beforeReply()
        return renewReplies.removeFirstOrNull() ?: RenewReply.Unknown("unscripted")
    }

    fun renews() = calls.filter { it.route == "devices/renew" }
    fun routes() = calls.map { it.route + "@" + if (it.token == OLD) "old" else "other" }
}

/** Deterministic SecureRandom so the generated replacement is predictable in tests. */
private class FixedRandom(private val fill: Byte) : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) { bytes.fill(fill) }
}

private fun metadata(
    due: Boolean,
    requestId: String? = null,
    device: String = DEVICE,
    vehicle: String = VEHICLE,
    expiresAtMs: Long = NOW + 3 * DAY,
    serverTimeMs: Long = NOW,
) = MetadataReply.Ok(CredentialMetadata(device, vehicle, expiresAtMs, serverTimeMs, due, requestId))

private fun receipt(requestId: String, device: String = DEVICE, vehicle: String = VEHICLE, expiresAtMs: Long = NOW + 90 * DAY) =
    RenewReply.Ok(RenewalReceipt(device, vehicle, requestId, expiresAtMs))

private class Harness(requestIds: List<String> = listOf(REQ_1, REQ_2), fill: Byte = 7) {
    val backend = FakeBackend()
    val cipher = FakeCipher()
    val store = VaultStateStore(backend, cipher)
    val api = ScriptedCredentialApi().also { it.backendForDurability = backend }
    private val ids = ArrayDeque(requestIds)
    val renewer = CredentialRenewer(store, api, FixedRandom(fill), newRequestId = { ids.removeFirst() })
    val replacement = DeviceTokens.generate(FixedRandom(fill))
    val replacement2 = DeviceTokens.generate(FixedRandom((fill + 1).toByte()))

    fun paired(pending: PendingRenewal? = null) {
        assertTrue(store.write(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD), pending)))
    }

    fun state() = (store.read() as StoredCredential.Present).state

    /** Fresh process: a new store instance over what reached disk. */
    fun restartedState(): CredentialState? {
        val b = FakeBackend().apply { disk.putAll(backend.disk); restart() }
        return (VaultStateStore(b, cipher).read() as? StoredCredential.Present)?.state
    }
}

class DeviceTokensTest {
    @Test
    fun `replacement is a fresh 32-byte dnm_ base64url token matching the pairing format`() {
        val t = DeviceTokens.generate(SecureRandom())
        assertTrue(Regex("dnm_[A-Za-z0-9_-]{43}").matches(t), "shape")
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(t.removePrefix("dnm_")).size)
        assertNotEquals(t, DeviceTokens.generate(SecureRandom()))
    }

    @Test
    fun `hash is lowercase SHA-256 hex of the whole token string like the server`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DeviceTokens.sha256Hex("abc"))
        assertTrue(Regex("[0-9a-f]{64}").matches(DeviceTokens.sha256Hex(OLD)))
    }

    @Test
    fun `secret-bearing values never print their token or hash`() {
        val pending = PendingRenewal(REQ_1, OLD)
        val cred = DeviceCredential(DEVICE, VEHICLE, OLD)
        val state = CredentialState(cred, pending)
        for (text in listOf(cred.toString(), pending.toString(), state.toString())) {
            assertFalse(OLD in text, text)
            assertFalse(OLD.removePrefix("dnm_") in text, text)
            assertFalse(pending.replacementTokenHash in text, text)
        }
    }

    @Test
    fun `existing three-argument credential callers still compile with unknown expiry`() {
        val c = DeviceCredential(DEVICE, null, OLD)
        assertNull(c.expiresAtMs)
        assertEquals(c, DeviceCredential(DEVICE, null, OLD))
    }
}

class RenewalWireTest {
    private fun envelope(inner: String) = """{"data":$inner}"""
    private val metaJson =
        """{"deviceId":"$DEVICE","vehicleId":"$VEHICLE","expiresAt":"2027-01-01T00:00:00Z","serverTime":"2026-12-26T00:00:00Z","renewalDue":true,"renewalRequestId":null}"""
    private val receiptJson = """{"deviceId":"$DEVICE","vehicleId":"$VEHICLE","requestId":"$REQ_1","expiresAt":"2027-03-31T00:00:00+00:00"}"""

    @Test
    fun `request bodies carry exactly the frozen fields`() {
        assertEquals(mapOf("deviceId" to DEVICE), TelemetryWire.parseObject(RenewalWire.metadataBody(DEVICE))!!.mapValues { it.value.toString().trim('"') })
        val hash = DeviceTokens.sha256Hex(OLD)
        val renew = TelemetryWire.parseObject(RenewalWire.renewBody(DEVICE, REQ_1, hash))!!
        assertEquals(setOf("deviceId", "requestId", "replacementTokenHash"), renew.keys)
        assertFalse(OLD in RenewalWire.renewBody(DEVICE, REQ_1, hash), "plaintext is never sent")
    }

    @Test
    fun `metadata is read from the data envelope`() {
        val ok = assertIs<MetadataReply.Ok>(RenewalWire.classifyMetadata(200, envelope(metaJson)))
        assertEquals(DEVICE, ok.metadata.deviceId)
        assertTrue(ok.metadata.renewalDue)
        assertNull(ok.metadata.renewalRequestId)
        assertEquals(Iso.parse("2026-12-26T00:00:00Z"), ok.metadata.serverTimeMs)
        val withId = metaJson.replace("\"renewalRequestId\":null", "\"renewalRequestId\":\"$REQ_1\"")
        assertEquals(REQ_1, assertIs<MetadataReply.Ok>(RenewalWire.classifyMetadata(200, envelope(withId))).metadata.renewalRequestId)
    }

    @Test
    fun `malformed 2xx metadata is an unknown outcome, never a decision`() {
        for (body in listOf(
            metaJson, // missing envelope
            envelope(metaJson.replace("true", "\"true\"")),
            envelope(metaJson.replace(",\"renewalRequestId\":null", "")),
            envelope(metaJson.replace(DEVICE, "not-a-uuid")),
            envelope(metaJson.replace("2027-01-01T00:00:00Z", "soon")),
            "<html>proxy</html>",
            "",
        )) assertIs<MetadataReply.Unknown>(RenewalWire.classifyMetadata(200, body), body)
    }

    @Test
    fun `renew receipt requires the four exact fields`() {
        val ok = assertIs<RenewReply.Ok>(RenewalWire.classifyRenew(200, envelope(receiptJson)))
        assertEquals(REQ_1, ok.receipt.requestId)
        assertIs<RenewReply.Unknown>(RenewalWire.classifyRenew(200, envelope(receiptJson.replace(",\"requestId\":\"$REQ_1\"", ""))))
        assertIs<RenewReply.Unknown>(RenewalWire.classifyRenew(201, receiptJson))
    }

    @Test
    fun `status classification keeps definite rejections apart from unknown outcomes`() {
        fun err(code: String) = """{"error":{"code":"$code","message":"x"}}"""
        assertEquals(RenewReply.NotDue, RenewalWire.classifyRenew(409, err("device_renewal_not_due")))
        assertEquals(RenewReply.Conflict, RenewalWire.classifyRenew(409, err("device_renewal_conflict")))
        assertEquals(RenewReply.EndpointError(409), RenewalWire.classifyRenew(409, err("conflict")))
        assertEquals(RenewReply.Unauthorized, RenewalWire.classifyRenew(401, err("unauthorized")))
        assertEquals(RenewReply.EndpointError(403), RenewalWire.classifyRenew(403, ""))
        assertEquals(RenewReply.EndpointError(404), RenewalWire.classifyRenew(404, ""))
        for (code in listOf(408, 429, 500, 503)) assertIs<RenewReply.Unknown>(RenewalWire.classifyRenew(code, ""))
        assertEquals(MetadataReply.Unauthorized, RenewalWire.classifyMetadata(401, ""))
        assertEquals(MetadataReply.EndpointError(404), RenewalWire.classifyMetadata(404, ""))
        for (code in listOf(429, 502)) assertIs<MetadataReply.Unknown>(RenewalWire.classifyMetadata(code, ""))
    }

    @Test
    fun `failure reasons are fixed strings without body content`() {
        val r = assertIs<RenewReply.Unknown>(RenewalWire.classifyRenew(200, """{"data":{"secret":"$OLD"}}"""))
        assertFalse(OLD in r.reason)
        assertFalse(OLD in RenewalWire.classifyMetadata(500, OLD).toString())
    }
}

class VaultStateStoreTest {
    @Test
    fun `active and pending persist together and survive a restart`() {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        assertEquals(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD), PendingRenewal(REQ_1, h.replacement)), h.restartedState())
        assertEquals(1, h.backend.disk.size, "one atomic record")
    }

    @Test
    fun `legacy credential migrates without losing its token and the legacy entry goes in the same commit`() {
        val h = Harness()
        val legacy = TelemetryWire.encodeObject(mapOf("deviceId" to DEVICE, "vehicleId" to VEHICLE, "deviceToken" to OLD))
        h.backend.disk[VaultStateStore.LEGACY_RECORD] = h.cipher.seal(VaultStateStore.LEGACY_RECORD, legacy); h.backend.restart()
        val read = assertIs<StoredCredential.Present>(h.store.read())
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), read.state.active)
        assertFalse(read.durable, "legacy must be rewritten before anything is sent")
        assertTrue(h.store.write(read.state))
        assertEquals(setOf(VaultStateStore.RECORD), h.backend.disk.keys)
        assertEquals(read.state, h.restartedState())
    }

    @Test
    fun `failed migration keeps the legacy record`() {
        val h = Harness()
        val legacy = TelemetryWire.encodeObject(mapOf("deviceId" to DEVICE, "deviceToken" to OLD))
        h.backend.disk[VaultStateStore.LEGACY_RECORD] = h.cipher.seal(VaultStateStore.LEGACY_RECORD, legacy); h.backend.restart()
        val read = assertIs<StoredCredential.Present>(h.store.read())
        h.backend.failCommits = 1
        assertFalse(h.store.write(read.state))
        assertEquals(setOf(VaultStateStore.LEGACY_RECORD), h.backend.disk.keys)
        assertEquals(DeviceCredential(DEVICE, null, OLD), h.restartedState()!!.active)
    }

    @Test
    fun `encryption failure leaves the previous good record untouched`() {
        val h = Harness()
        h.paired()
        val before = HashMap(h.backend.disk)
        h.cipher.failSeal = true
        assertFalse(h.store.write(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD), PendingRenewal(REQ_1, h.replacement))))
        assertEquals(before, h.backend.disk)
        assertEquals(0, h.backend.commits - 1, "nothing was committed")
        h.cipher.failSeal = false
        assertEquals(StoredCredential.Present(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD)), durable = true), h.store.read())
    }

    @Test
    fun `a failed commit that advanced the memory cache is rolled back, and an unconfirmed rollback forces a rewrite`() {
        val h = Harness()
        h.paired()
        val old = CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD))
        val staged = CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD), PendingRenewal(REQ_1, h.replacement))
        h.backend.failCommits = 1 // the write fails, its rollback reaches disk
        assertFalse(h.store.write(staged))
        assertEquals(StoredCredential.Present(old, durable = true), h.store.read())
        h.backend.failCommits = 2 // the write and its rollback both fail
        assertFalse(h.store.write(staged))
        assertEquals(StoredCredential.Present(old, durable = false), h.store.read(), "memory never runs ahead of disk")
        assertNull(h.restartedState()!!.pending)
        assertTrue(h.store.write(staged))
        assertEquals(StoredCredential.Present(staged, durable = true), h.store.read())
    }

    @Test
    fun `undecryptable record is unreadable, not absent, and is not deleted`() {
        val h = Harness()
        h.paired()
        h.cipher.failOpen = true
        assertEquals(StoredCredential.Unreadable, h.store.read())
        assertTrue(VaultStateStore.RECORD in h.backend.disk)
    }

    @Test
    fun `clear removes both current and legacy records`() {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.backend.disk[VaultStateStore.LEGACY_RECORD] = "x"; h.backend.restart()
        assertTrue(h.store.clear())
        assertEquals(StoredCredential.Absent, h.store.read())
        assertTrue(h.backend.disk.isEmpty())
    }
}

/** A failed commit that advanced the memory cache must never make an unpublished credential visible or durable. */
class UnpublishedCandidateTest {
    private val candidate = DeviceCredential(OTHER_DEVICE, VEHICLE, "dnm_" + "C".repeat(43))

    @Test
    fun `failed pairing write over an existing credential keeps the old one visible and on disk`() {
        val h = Harness()
        h.paired()
        h.backend.failCommits = 1
        assertFalse(h.store.write(CredentialState(candidate)))
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.store.current())
        assertEquals(DEVICE, (h.store.read() as StoredCredential.Present).state.active.deviceId)
        // A later unrelated commit to the same preferences file must not flush the candidate to disk.
        assertTrue(h.backend.commit(mapOf("pending_pairing" to "x"), emptySet()))
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.restartedState()!!.active)
    }

    @Test
    fun `failed first pairing write leaves the device unpaired`() {
        val h = Harness()
        h.backend.failCommits = 1
        assertFalse(h.store.write(CredentialState(candidate)))
        assertNull(h.store.current())
        assertEquals(StoredCredential.Absent, h.store.read())
        assertTrue(h.backend.commit(mapOf("pending_pairing" to "x"), emptySet()))
        assertNull(h.restartedState())
    }

    @Test
    fun `failed clear keeps the retained credential visible instead of pretending it is gone`() {
        val h = Harness()
        h.paired()
        h.backend.failCommits = 1
        assertFalse(h.store.clear())
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.store.current())
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.restartedState()!!.active)
    }

    @Test
    fun `automatic work never switches to an unpublished pairing candidate`() = runTest {
        val h = Harness()
        h.paired()
        val outbox = InMemoryOutbox().apply { insert(listOf(obs(NOW - 1, id = "old-car")), NOW, DEVICE) }
        h.backend.failCommits = 1
        var published = false
        val commit = commitPairing(LifecycleLock(), outbox, candidate, onPublished = { published = true; true }) { h.store.write(CredentialState(it)) }
        assertEquals(PairingCommit.StorageFailed, commit)
        assertFalse(published)
        assertEquals(DEVICE, outbox.bindingOf("old-car"))
        h.api.meta(OLD, metadata(due = false))
        val sent = mutableListOf<String>()
        renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = { c ->
            sent += c.deviceId
            OutboxUploader(outbox, ScriptedTransport { ackAll(it) }, c.deviceId, clock = { NOW }).drain()
        }, onPausedReauth = { _, _ -> })
        assertEquals(listOf(DEVICE), sent)
        assertTrue(h.api.calls.none { it.token == candidate.deviceToken })
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.restartedState()!!.active)
    }
}

/** Commit reported success but the read-back could not be verified: the write must be undone like a failed commit. */
class ReadBackVerificationTest {
    private val candidate = DeviceCredential(OTHER_DEVICE, VEHICLE, "dnm_" + "C".repeat(43))

    @Test
    fun `unverified pairing over an existing binding restores the old credential and queue`() = runTest {
        val h = Harness()
        h.paired()
        val outbox = InMemoryOutbox().apply { insert(listOf(obs(NOW - 1, id = "old-car")), NOW, DEVICE) }
        h.cipher.failOpens = 1 // the read-back after a successful commit fails once, then decryption recovers
        val commit = commitPairing(LifecycleLock(), outbox, candidate, onPublished = { true }) { h.store.write(CredentialState(it)) }
        assertEquals(PairingCommit.StorageFailed, commit)
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.store.current())
        assertEquals(StoredCredential.Present(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD)), durable = true), h.store.read())
        assertEquals(DEVICE, outbox.bindingOf("old-car"))
        assertTrue(h.backend.commit(mapOf("pending_pairing" to "x"), emptySet()))
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.restartedState()!!.active)

        h.api.meta(OLD, metadata(due = false))
        val sent = mutableListOf<String>()
        renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = { c ->
            sent += c.deviceId
            OutboxUploader(outbox, ScriptedTransport { ackAll(it) }, c.deviceId, clock = { NOW }).drain()
        }, onPausedReauth = { _, _ -> })
        assertEquals(listOf(DEVICE), sent)
        assertTrue(h.api.calls.none { it.token == candidate.deviceToken })
    }

    @Test
    fun `unverified first pairing leaves the device unpaired`() {
        val h = Harness()
        h.cipher.failOpens = 1
        assertFalse(h.store.write(CredentialState(candidate)))
        assertNull(h.store.current())
        assertEquals(StoredCredential.Absent, h.store.read())
        assertTrue(h.backend.commit(mapOf("pending_pairing" to "x"), emptySet()))
        assertNull(h.restartedState())
    }

    @Test
    fun `unconfirmed restoration keeps this process gated until the previous record is re-persisted`() = runTest {
        val h = Harness()
        h.paired()
        h.cipher.failOpens = 1
        h.backend.failCommitNumbers += h.backend.commits + 2 // write commits, its restoration does not reach disk
        assertFalse(h.store.write(CredentialState(candidate)))
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.store.current(), "memory holds the previous record again")
        assertEquals(StoredCredential.Present(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD)), durable = false), h.store.read())
        // Boundary: the unverified candidate is still the on-disk record until a later write succeeds.
        assertEquals(OTHER_DEVICE, h.restartedState()!!.active.deviceId)

        h.backend.failCommits = 1
        assertEquals(RenewalStatus.Held("storage_write_failed", retryable = false), h.renewer.prepare())
        assertTrue(h.api.calls.isEmpty(), "nothing is sent while restoration is unconfirmed")

        h.api.meta(OLD, metadata(due = false))
        assertEquals(OLD, assertIs<RenewalStatus.Ready>(h.renewer.prepare()).credential.deviceToken)
        assertEquals(DeviceCredential(DEVICE, VEHICLE, OLD), h.restartedState()!!.active, "previous record re-persisted first")
        assertTrue(h.api.calls.none { it.token == candidate.deviceToken })
    }

    @Test
    fun `unverified promotion restores the dispatched pending record and recovery reuses it`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += receipt(REQ_1)
        h.api.beforeReply = { if (h.api.renews().isNotEmpty()) h.cipher.failOpens = 1 }
        assertEquals(RenewalStatus.Held("storage_write_failed", retryable = false), h.renewer.prepare())
        val staged = CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD), PendingRenewal(REQ_1, h.replacement))
        assertEquals(staged, h.restartedState(), "the previous whole record, not the unverified promotion")
        assertEquals(StoredCredential.Present(staged, durable = true), h.store.read())

        h.api.beforeReply = {}
        h.api.meta(h.replacement, metadata(due = false, requestId = REQ_1, expiresAtMs = NOW + 90 * DAY))
        assertEquals(h.replacement, assertIs<RenewalStatus.Ready>(h.renewer.prepare()).credential.deviceToken)
        assertEquals(1, h.api.renews().size, "no new intent and no re-sent renewal")
        assertNull(h.restartedState()!!.pending)
    }
}

class CredentialRenewerTest {
    @Test
    fun `not due keeps the current credential and sends no renewal`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = false))
        val r = assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(OLD, r.credential.deviceToken)
        assertEquals(listOf("devices/credential@old"), h.api.routes())
        assertNull(h.state().pending)
    }

    @Test
    fun `due credential is staged durably before the hash is dispatched, then promoted durably`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += receipt(REQ_1)
        val r = assertIs<RenewalStatus.Ready>(h.renewer.prepare())

        val renew = h.api.renews().single()
        assertEquals(OLD, renew.token, "the old credential authorises its own renewal")
        assertEquals(mapOf("deviceId" to DEVICE, "requestId" to REQ_1, "replacementTokenHash" to DeviceTokens.sha256Hex(h.replacement)), renew.body)
        assertEquals(PendingRenewal(REQ_1, h.replacement), h.api.durableAtRenew.single()!!.pending, "staged on disk before dispatch")

        assertEquals(DeviceCredential(DEVICE, VEHICLE, h.replacement, NOW + 90 * DAY), r.credential)
        assertEquals(CredentialState(r.credential), h.restartedState(), "promotion reached disk before upload")
    }

    @Test
    fun `lost renew response keeps the same intent and the next run confirms it with the pending token first`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += RenewReply.Unknown("SocketTimeoutException")
        assertEquals(RenewalStatus.Held("SocketTimeoutException", retryable = true), h.renewer.prepare())
        assertEquals(PendingRenewal(REQ_1, h.replacement), h.restartedState()!!.pending)

        h.api.calls.clear()
        h.api.meta(h.replacement, metadata(due = false, requestId = REQ_1, expiresAtMs = NOW + 90 * DAY))
        val r = assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(listOf("devices/credential@other"), h.api.routes(), "no second renewal, no old-token call")
        assertEquals(h.replacement, r.credential.deviceToken)
        assertNull(h.restartedState()!!.pending)
    }

    @Test
    fun `pending token 401 retries the exact old-token renewal, never a fresh nonce`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.api.meta(h.replacement, MetadataReply.Unauthorized, MetadataReply.Unauthorized)
        h.api.renewReplies += RenewReply.Unknown("http_503")
        h.api.renewReplies += receipt(REQ_1)
        assertEquals(RenewalStatus.Held("http_503", retryable = true), h.renewer.prepare())
        assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        val renews = h.api.renews()
        assertEquals(2, renews.size)
        assertTrue(renews.all { it.token == OLD && it.body["requestId"] == REQ_1 && it.body["replacementTokenHash"] == DeviceTokens.sha256Hex(h.replacement) })
        assertEquals(h.replacement, h.restartedState()!!.active.deviceToken)
    }

    @Test
    fun `unknown outcomes never upload with the old token and never replace the pending intent`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.api.meta(h.replacement, MetadataReply.Unknown("http_502"), MetadataReply.Unauthorized, MetadataReply.Unauthorized, MetadataReply.EndpointError(404))
        h.api.renewReplies += RenewReply.Unknown("http_429")
        h.api.renewReplies += RenewReply.Unknown("malformed_receipt")
        repeat(4) { assertIs<RenewalStatus.Held>(h.renewer.prepare()) }
        assertEquals(PendingRenewal(REQ_1, h.replacement), h.restartedState()!!.pending)
        assertTrue(h.api.calls.none { it.route == "devices/credential" && it.token == OLD })
    }

    @Test
    fun `proved not due with valid current old metadata clears the never-committed intent`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.api.meta(h.replacement, MetadataReply.Unauthorized)
        h.api.renewReplies += RenewReply.NotDue
        h.api.meta(OLD, metadata(due = false))
        val r = assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(OLD, r.credential.deviceToken)
        assertEquals(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD)), h.restartedState())
    }

    @Test
    fun `not due without proof from old metadata keeps the intent`() = runTest {
        for (oldMeta in listOf<MetadataReply>(
            MetadataReply.Unknown("http_500"),
            MetadataReply.Unauthorized,
            metadata(due = true),
            metadata(due = false, requestId = REQ_1),
            metadata(due = false, device = OTHER_DEVICE),
        )) {
            val h = Harness()
            h.paired(PendingRenewal(REQ_1, h.replacement))
            h.api.meta(h.replacement, MetadataReply.Unauthorized)
            h.api.renewReplies += RenewReply.NotDue
            h.api.meta(OLD, oldMeta)
            assertIs<RenewalStatus.Held>(h.renewer.prepare(), oldMeta.toString())
            assertEquals(PendingRenewal(REQ_1, h.replacement), h.restartedState()!!.pending, oldMeta.toString())
        }
    }

    @Test
    fun `immutable-input conflict is retained as an explicit non-retrying state`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.api.meta(h.replacement, MetadataReply.Unauthorized)
        h.api.renewReplies += RenewReply.Conflict
        assertEquals(RenewalStatus.Held("renewal_conflict", retryable = false), h.renewer.prepare())
        assertEquals(PendingRenewal(REQ_1, h.replacement), h.restartedState()!!.pending)
    }

    @Test
    fun `both tokens without authority require re-pairing and keep local state`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        h.api.meta(h.replacement, MetadataReply.Unauthorized)
        h.api.renewReplies += RenewReply.Unauthorized
        val r = assertIs<RenewalStatus.Rejected>(h.renewer.prepare())
        assertEquals(OLD, r.credential.deviceToken)
        assertNotNull(h.restartedState()!!.pending)
    }

    @Test
    fun `current credential 401 is a rejection while other metadata failures still let the queue upload`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, MetadataReply.Unauthorized, MetadataReply.EndpointError(404), MetadataReply.Unknown("http_503"))
        assertIs<RenewalStatus.Rejected>(h.renewer.prepare())
        assertEquals(OLD, assertIs<RenewalStatus.Ready>(h.renewer.prepare()).credential.deviceToken)
        assertEquals(OLD, assertIs<RenewalStatus.Ready>(h.renewer.prepare()).credential.deviceToken)
        assertTrue(h.api.renews().isEmpty())
    }

    @Test
    fun `mismatched replies never promote`() = runTest {
        for (reply in listOf(receipt(REQ_2), receipt(REQ_1, device = OTHER_DEVICE), receipt(REQ_1, vehicle = OTHER_DEVICE))) {
            val h = Harness()
            h.paired()
            h.api.meta(OLD, metadata(due = true))
            h.api.renewReplies += reply
            assertEquals(RenewalStatus.Held("renewal_mismatch", retryable = false), h.renewer.prepare())
            assertEquals(OLD, h.restartedState()!!.active.deviceToken)
            assertEquals(PendingRenewal(REQ_1, h.replacement), h.restartedState()!!.pending)
        }
        for (meta in listOf(
            metadata(due = false, requestId = REQ_2),
            metadata(due = false, requestId = null),
            metadata(due = false, requestId = REQ_1, vehicle = OTHER_DEVICE),
            metadata(due = false, requestId = REQ_1, expiresAtMs = NOW - 1),
        )) {
            val h = Harness()
            h.paired(PendingRenewal(REQ_1, h.replacement))
            h.api.meta(h.replacement, meta)
            assertIs<RenewalStatus.Held>(h.renewer.prepare())
            assertEquals(OLD, h.restartedState()!!.active.deviceToken)
        }
    }

    @Test
    fun `failed staging dispatches nothing and an unconfirmed state is re-persisted before any request`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true), metadata(due = true))
        h.backend.failCommits = 2 // staging fails and so does its rollback
        val first = assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(OLD, first.credential.deviceToken, "the unchanged old credential may still upload")
        assertTrue(h.api.renews().isEmpty())
        assertNull((h.store.read() as StoredCredential.Present).state.pending, "the unconfirmed intent is not kept in memory")

        h.api.calls.clear()
        h.backend.failCommits = 2
        assertEquals(RenewalStatus.Held("storage_write_failed", retryable = false), h.renewer.prepare())
        assertTrue(h.api.calls.isEmpty(), "nothing is sent while memory and disk may differ")

        h.api.renewReplies += receipt(REQ_2)
        assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(PendingRenewal(REQ_2, h.replacement), h.api.durableAtRenew.single()!!.pending, "a new intent, staged on disk before dispatch")
        assertEquals(h.replacement, h.restartedState()!!.active.deviceToken)
    }

    @Test
    fun `failed promotion uploads nothing and keeps the recoverable pending state on disk`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += RenewReply.Ok(RenewalReceipt(DEVICE, VEHICLE, REQ_1, NOW + 90 * DAY))
        h.api.beforeReply = { if (h.api.renews().isNotEmpty()) h.backend.failCommits = 1 }
        assertEquals(RenewalStatus.Held("storage_write_failed", retryable = false), h.renewer.prepare())
        val disk = h.restartedState()!!
        assertEquals(OLD, disk.active.deviceToken)
        assertEquals(PendingRenewal(REQ_1, h.replacement), disk.pending)
    }

    @Test
    fun `encryption failure while staging keeps the old credential usable and sends no renewal`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.cipher.failSeal = true
        assertEquals(OLD, assertIs<RenewalStatus.Ready>(h.renewer.prepare()).credential.deviceToken)
        h.cipher.failSeal = false
        assertTrue(h.api.renews().isEmpty())
        assertEquals(CredentialState(DeviceCredential(DEVICE, VEHICLE, OLD)), h.restartedState())
    }

    @Test
    fun `a legacy credential is migrated before any request and a failed migration sends nothing`() = runTest {
        val h = Harness()
        val legacy = TelemetryWire.encodeObject(mapOf("deviceId" to DEVICE, "vehicleId" to VEHICLE, "deviceToken" to OLD))
        h.backend.disk[VaultStateStore.LEGACY_RECORD] = h.cipher.seal(VaultStateStore.LEGACY_RECORD, legacy); h.backend.restart()
        h.backend.failCommits = 1; h.backend.ramChanges = false
        assertEquals(RenewalStatus.Held("storage_write_failed", retryable = false), h.renewer.prepare())
        assertTrue(h.api.calls.isEmpty())

        h.api.meta(OLD, metadata(due = false))
        assertIs<RenewalStatus.Ready>(h.renewer.prepare())
        assertEquals(setOf(VaultStateStore.RECORD), h.backend.disk.keys)
    }

    @Test
    fun `unreadable storage and no credential send nothing`() = runTest {
        val h = Harness()
        assertEquals(RenewalStatus.NoCredential, h.renewer.prepare())
        h.paired()
        h.cipher.failOpen = true
        assertEquals(RenewalStatus.Held("credential_unreadable", retryable = false), h.renewer.prepare())
        assertTrue(h.api.calls.isEmpty())
    }
}

class RenewAndUploadTest {
    private fun uploaderRun(store: OutboxStore, sent: MutableList<String>): suspend (DeviceCredential) -> UploadOutcome = { cred ->
        OutboxUploader(store, ScriptedTransport { sent += cred.deviceToken; ackAll(it) }, cred.deviceId, clock = { NOW }).drain()
    }

    @Test
    fun `an empty outbox still checks metadata and renews when the server says it is due`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += receipt(REQ_1)
        val cycle = renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = uploaderRun(InMemoryOutbox(), mutableListOf()), onPausedReauth = { _, _ -> error("no pause") })!!
        assertEquals(UploadOutcome.Idle, cycle.upload)
        assertEquals(h.replacement, h.restartedState()!!.active.deviceToken)
    }

    @Test
    fun `telemetry after a renewal uses only the durably promoted token`() = runTest {
        val h = Harness()
        h.paired()
        val outbox = InMemoryOutbox().apply { insert(listOf(obs(NOW - 1)), NOW, DEVICE) }
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += receipt(REQ_1)
        val sent = mutableListOf<String>()
        renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = uploaderRun(outbox, sent), onPausedReauth = { _, _ -> })
        assertEquals(listOf(h.replacement), sent)
    }

    @Test
    fun `held renewal uploads nothing and keeps the queue`() = runTest {
        val h = Harness()
        h.paired(PendingRenewal(REQ_1, h.replacement))
        val outbox = InMemoryOutbox().apply { insert(listOf(obs(NOW - 1, id = "keep")), NOW, DEVICE) }
        h.api.meta(h.replacement, MetadataReply.Unknown("IOException"))
        val sent = mutableListOf<String>()
        val cycle = renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = uploaderRun(outbox, sent), onPausedReauth = { _, _ -> })!!
        assertNull(cycle.upload)
        assertTrue(sent.isEmpty())
        assertEquals(listOf("keep"), outbox.ids())
    }

    @Test
    fun `rejected credential pauses only while it is still current`() = runTest {
        val h = Harness()
        h.paired()
        h.api.meta(OLD, MetadataReply.Unauthorized)
        var paused = 0
        renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = { error("no upload") }, onPausedReauth = { _, _ -> paused++ })
        assertEquals(1, paused)

        // An old worker's rejection must not pause a session that became current under it.
        h.api.meta(OLD, metadata(due = false))
        val newSession = DeviceCredential(OTHER_DEVICE, VEHICLE, h.replacement2)
        renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, run = {
            assertTrue(h.store.write(CredentialState(newSession)))
            UploadOutcome.PausedReauth(401)
        }, onPausedReauth = { _, _ -> paused++ })
        assertEquals(1, paused)
    }

    @Test
    fun `skip-if is checked before any request`() = runTest {
        val h = Harness()
        h.paired()
        assertNull(renewAndUpload(LifecycleLock(), h.renewer, { h.store.current() }, skipIf = { true }, run = { error("no") }, onPausedReauth = { _, _ -> }))
        assertTrue(h.api.calls.isEmpty())
    }

    @Test
    fun `disconnect and re-pair wait for renewal, promotion and upload under one lock hold, and clear pending`() = runTest {
        val h = Harness()
        h.paired()
        val lock = LifecycleLock()
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        h.api.meta(OLD, metadata(due = true))
        h.api.renewReplies += receipt(REQ_1)
        h.api.beforeReply = { if (h.api.renews().isNotEmpty()) gate.await() }
        val cycle = launch {
            renewAndUpload(lock, h.renewer, { h.store.current() }, run = { log += "upload:" + (if (it.deviceToken == h.replacement) "new" else "old"); UploadOutcome.Idle }, onPausedReauth = { _, _ -> })
            log += "cycle-done"
        }
        val repair = launch {
            commitPairing(lock, InMemoryOutbox(), DeviceCredential(OTHER_DEVICE, VEHICLE, h.replacement2)) {
                log += "repair"
                h.store.write(CredentialState(it))
            }
        }
        testScheduler.advanceUntilIdle()
        assertTrue(log.isEmpty(), "re-pair waits while the renewal is in flight")
        gate.complete(Unit)
        cycle.join(); repair.join()
        assertEquals(listOf("upload:new", "cycle-done", "repair"), log)
        assertEquals(CredentialState(DeviceCredential(OTHER_DEVICE, VEHICLE, h.replacement2)), h.restartedState(), "re-pair leaves no pending intent")

        h.paired(PendingRenewal(REQ_1, h.replacement))
        assertTrue(DisconnectCoordinator(lock, {}, {}, { h.store.clear() }).disconnect().complete)
        assertNull(h.restartedState())
    }
}
