package app.danim.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Unattended device credential renewal (docs/device-credential-renewal-design.md). The client generates the
 * replacement token and persists it durably; the renewal request body carries only its SHA-256, and the server never
 * mints, stores or returns the replacement plaintext. After promotion the token is sent like any credential, as the
 * Bearer of later requests.
 */
object DeviceTokens {
    private val SHAPE = Regex("dnm_[A-Za-z0-9_-]{43}")

    /** Same format as server-issued tokens: `dnm_` + unpadded base64url of 32 random bytes. */
    fun generate(random: SecureRandom): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return "dnm_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Lowercase hex SHA-256 of the whole token string, as the server hashes bearer tokens. */
    fun sha256Hex(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isWellFormed(token: String) = SHAPE.matches(token)
}

/** One immutable renewal intent. Its request id and token never change until it is promoted or proved uncommitted. */
data class PendingRenewal(val requestId: String, val replacementToken: String) {
    val replacementTokenHash: String get() = DeviceTokens.sha256Hex(replacementToken)
    override fun toString() = "PendingRenewal(requestId=$requestId)"
}

/** Active credential and at most one pending renewal, always persisted together as one record. */
data class CredentialState(val active: DeviceCredential, val pending: PendingRenewal? = null) {
    override fun toString() = "CredentialState(active=$active, pending=$pending)"
}

sealed interface StoredCredential {
    data object Absent : StoredCredential

    /** [durable] is false when memory may differ from disk (a failed write in this process, or an unmigrated legacy record). */
    data class Present(val state: CredentialState, val durable: Boolean) : StoredCredential

    /** Present but undecryptable or invalid. Nothing may be sent; local storage loss is a re-pair boundary. */
    data object Unreadable : StoredCredential
}

/** Durable whole-record credential storage. [write] returns true only when the record positively reached disk. */
interface CredentialStateStore {
    fun read(): StoredCredential
    fun write(state: CredentialState): Boolean
}

/** App-private key/value storage with a synchronous commit that reports whether the write reached disk. */
interface RecordBackend {
    fun read(name: String): String?
    fun commit(puts: Map<String, String>, removes: Set<String>): Boolean
}

enum class CommitOutcome {
    /** Committed and positively verified. */
    COMMITTED,

    /** The write failed or could not be verified; the touched records were put back in memory and on disk as they were. */
    RESTORED,

    /** As [RESTORED], but putting the previous values back did not reach disk; memory holds them again. */
    UNCERTAIN,
}

/**
 * Commit that never leaves an unconfirmed value behind. A failed SharedPreferences commit may already have advanced
 * its memory cache, and any later commit to the same file would then flush that value to disk. The exact previous
 * values of the touched records are kept until [verify] positively confirms the write; a failed commit, a failed or
 * throwing verification all write those previous values (and absences) back.
 */
fun RecordBackend.commitOrRestore(
    puts: Map<String, String>,
    removes: Set<String>,
    verify: () -> Boolean = { true },
): CommitOutcome {
    val before = (puts.keys + removes).associateWith { read(it) }
    if (commit(puts, removes) && runCatching(verify).getOrDefault(false)) return CommitOutcome.COMMITTED
    val restore = before.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    val restored = commit(restore, before.filterValues { it == null }.keys)
    return if (restored) CommitOutcome.RESTORED else CommitOutcome.UNCERTAIN
}

/** Authenticated encryption bound to the record name; failures throw. */
interface RecordCipher {
    fun seal(name: String, plain: String): String
    fun open(name: String, sealed: String): String
}

/**
 * Encrypted credential state over [RecordBackend]. A write is reported durable only after a positive commit and a
 * read-back that decrypts to the same state. A failed or unverified write is rolled back ([commitOrRestore]), so an unpublished
 * value (a new pairing candidate, a staged renewal, a removal) never becomes visible or reaches disk later; if the
 * rollback itself did not reach disk, reads report `durable = false` until a write succeeds. Encryption failure never
 * touches storage, and nothing here deletes a record because it could not be read.
 */
class VaultStateStore(private val backend: RecordBackend, private val cipher: RecordCipher) : CredentialStateStore {
    @Volatile private var durable = true

    override fun read(): StoredCredential {
        backend.read(RECORD)?.let { sealed ->
            val state = runCatching { decode(cipher.open(RECORD, sealed)) }.getOrNull() ?: return StoredCredential.Unreadable
            return StoredCredential.Present(state, durable)
        }
        val legacy = backend.read(LEGACY_RECORD) ?: return StoredCredential.Absent
        val active = runCatching { decodeActive(TelemetryWire.parseObject(cipher.open(LEGACY_RECORD, legacy))!!) }.getOrNull()
            ?: return StoredCredential.Unreadable
        // Not yet migrated: it must be written as the current record before anything is sent with it.
        return StoredCredential.Present(CredentialState(active), durable = false)
    }

    /** Also removes the legacy record in the same commit, so migration is atomic. */
    override fun write(state: CredentialState): Boolean {
        val sealed = runCatching { cipher.seal(RECORD, encode(state)) }.getOrNull() ?: return false
        // Read-back verification happens before the previous record snapshot is released.
        val verify = { backend.read(LEGACY_RECORD) == null && decode(cipher.open(RECORD, backend.read(RECORD)!!)) == state }
        return when (backend.commitOrRestore(mapOf(RECORD to sealed), setOf(LEGACY_RECORD), verify)) {
            CommitOutcome.COMMITTED -> { durable = true; true }
            CommitOutcome.RESTORED -> false
            CommitOutcome.UNCERTAIN -> { durable = false; false }
        }
    }

    /** False keeps the previous record visible: a failed removal is reported as a retained credential. */
    fun clear(): Boolean = when (backend.commitOrRestore(emptyMap(), setOf(RECORD, LEGACY_RECORD)) {
        backend.read(RECORD) == null && backend.read(LEGACY_RECORD) == null
    }) {
        CommitOutcome.COMMITTED -> { durable = true; true }
        CommitOutcome.RESTORED -> false
        CommitOutcome.UNCERTAIN -> { durable = false; false }
    }

    /** The active credential, whatever its durability; for presence checks and fences, never for dispatch. */
    fun current(): DeviceCredential? = (read() as? StoredCredential.Present)?.state?.active

    private fun encode(s: CredentialState): String = TelemetryWire.encodeObject(
        buildMap {
            put("v", "2")
            put("deviceId", s.active.deviceId)
            s.active.vehicleId?.let { put("vehicleId", it) }
            put("deviceToken", s.active.deviceToken)
            s.active.expiresAtMs?.let { put("expiresAtMs", it.toString()) }
            s.pending?.let { put("pendingRequestId", it.requestId); put("pendingToken", it.replacementToken) }
        },
    )

    private fun decode(plain: String): CredentialState {
        val o = TelemetryWire.parseObject(plain)!!
        require(o.text("v") == "2")
        val reqId = o.text("pendingRequestId")
        val token = o.text("pendingToken")
        require((reqId == null) == (token == null))
        val pending = if (reqId != null && token != null) {
            require(RenewalWire.isUuid(reqId) && DeviceTokens.isWellFormed(token))
            PendingRenewal(reqId, token)
        } else null
        return CredentialState(decodeActive(o), pending)
    }

    private fun decodeActive(o: JsonObject) = DeviceCredential(
        deviceId = o.text("deviceId")!!.also { require(it.isNotEmpty()) },
        vehicleId = o.text("vehicleId"),
        deviceToken = o.text("deviceToken")!!.also { require(it.isNotEmpty()) },
        expiresAtMs = o.text("expiresAtMs")?.toLong(),
    )

    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val RECORD = "credential_state_v2"
        const val LEGACY_RECORD = "credential"
    }
}

/** `POST /devices/credential` result for the presented token. Server time decides due-ness, not the device clock. */
data class CredentialMetadata(
    val deviceId: String,
    val vehicleId: String,
    val expiresAtMs: Long,
    val serverTimeMs: Long,
    val renewalDue: Boolean,
    val renewalRequestId: String?,
)

/** `POST /devices/renew` result; an exact retry returns the same four fields. */
data class RenewalReceipt(val deviceId: String, val vehicleId: String, val requestId: String, val expiresAtMs: Long)

sealed interface MetadataReply {
    data class Ok(val metadata: CredentialMetadata) : MetadataReply
    /** HTTP 401: the presented token has no authority. */
    data object Unauthorized : MetadataReply
    /** Transport, 408/429/5xx or malformed 2xx. */
    data class Unknown(val reason: String) : MetadataReply
    data class EndpointError(val httpCode: Int) : MetadataReply
}

sealed interface RenewReply {
    data class Ok(val receipt: RenewalReceipt) : RenewReply
    /** 409 `device_renewal_not_due`: definite rejection, nothing was committed for this request. */
    data object NotDue : RenewReply
    /** 409 `device_renewal_conflict`: the request id is bound to different immutable input. */
    data object Conflict : RenewReply
    data object Unauthorized : RenewReply
    /** Transport, 408/429/5xx or malformed 2xx: the outcome is unknown and the same intent must be retried. */
    data class Unknown(val reason: String) : RenewReply
    data class EndpointError(val httpCode: Int) : RenewReply
}

/** Device-authenticated credential routes. Implementations never log tokens, hashes, headers or bodies. */
interface CredentialApi {
    suspend fun metadata(token: String, deviceId: String): MetadataReply
    suspend fun renew(token: String, deviceId: String, requestId: String, replacementTokenHash: String): RenewReply
}

object RenewalWire {
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private const val NOT_DUE = "device_renewal_not_due"
    private const val CONFLICT = "device_renewal_conflict"

    fun isUuid(s: String) = UUID.matches(s)

    fun metadataBody(deviceId: String) = TelemetryWire.encodeObject(mapOf("deviceId" to deviceId))

    fun renewBody(deviceId: String, requestId: String, replacementTokenHash: String) = TelemetryWire.encodeObject(
        mapOf("deviceId" to deviceId, "requestId" to requestId, "replacementTokenHash" to replacementTokenHash),
    )

    private fun data(body: String): JsonObject? = TelemetryWire.parseObject(body)?.get("data") as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.uuid(k: String): String = str(k)!!.also { require(isUuid(it)) }
    private fun JsonObject.instant(k: String): Long = Iso.parse(str(k)!!)

    fun parseMetadata(body: String): CredentialMetadata? = runCatching {
        val d = data(body)!!
        val due = (d["renewalDue"] as JsonPrimitive).takeIf { !it.isString }!!.booleanOrNull!!
        val requestId = when (val r = d["renewalRequestId"]) {
            JsonNull -> null
            is JsonPrimitive -> r.takeIf { it.isString }!!.content.also { require(isUuid(it)) }
            else -> error("renewalRequestId")
        }
        CredentialMetadata(d.uuid("deviceId"), d.uuid("vehicleId"), d.instant("expiresAt"), d.instant("serverTime"), due, requestId)
    }.getOrNull()

    fun parseReceipt(body: String): RenewalReceipt? = runCatching {
        val d = data(body)!!
        RenewalReceipt(d.uuid("deviceId"), d.uuid("vehicleId"), d.uuid("requestId"), d.instant("expiresAt"))
    }.getOrNull()

    private fun transient(code: Int) = code == 408 || code == 429 || code >= 500

    fun classifyMetadata(code: Int, body: String): MetadataReply = when {
        code in 200..299 -> parseMetadata(body)?.let { MetadataReply.Ok(it) } ?: MetadataReply.Unknown("malformed_metadata")
        code == 401 -> MetadataReply.Unauthorized
        transient(code) -> MetadataReply.Unknown("http_$code")
        else -> MetadataReply.EndpointError(code)
    }

    fun classifyRenew(code: Int, body: String): RenewReply = when {
        code in 200..299 -> parseReceipt(body)?.let { RenewReply.Ok(it) } ?: RenewReply.Unknown("malformed_receipt")
        code == 401 -> RenewReply.Unauthorized
        code == 409 && TelemetryWire.parseErrorCode(body) == NOT_DUE -> RenewReply.NotDue
        code == 409 && TelemetryWire.parseErrorCode(body) == CONFLICT -> RenewReply.Conflict
        transient(code) -> RenewReply.Unknown("http_$code")
        else -> RenewReply.EndpointError(code)
    }
}

sealed interface RenewalStatus {
    /** Telemetry may be sent with [credential]; it is durable and server-current as far as this run knows. */
    data class Ready(val credential: DeviceCredential, val note: String) : RenewalStatus

    data object NoCredential : RenewalStatus

    /** The server gave [credential] no authority (401) and nothing can recover it: re-pairing is required. */
    data class Rejected(val credential: DeviceCredential) : RenewalStatus

    /** Nothing may be sent this run; all local state is retained. [reason] is a fixed string. */
    data class Held(val reason: String, val retryable: Boolean) : RenewalStatus
}

/**
 * Decides, once per upload run and under the caller's lifecycle lock, which credential may upload. Order:
 * confirm durability; with a pending intent check the pending token first, then retry the exact old-token
 * renewal on 401; otherwise read old-token metadata and stage a fresh intent only when the server says it is due.
 * Every uncertain outcome keeps the same intent and sends no telemetry with the old token.
 */
class CredentialRenewer(
    private val store: CredentialStateStore,
    private val api: CredentialApi,
    private val random: SecureRandom = SecureRandom(),
    private val newRequestId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    suspend fun prepare(): RenewalStatus {
        val state = when (val s = store.read()) {
            StoredCredential.Absent -> return RenewalStatus.NoCredential
            StoredCredential.Unreadable -> return held("credential_unreadable", false)
            is StoredCredential.Present -> {
                // Memory may be ahead of disk: re-persist before any request uses it.
                if (!s.durable && !store.write(s.state)) return STORAGE_FAILED
                s.state
            }
        }
        val pending = state.pending ?: return checkActive(state)
        return when (val m = api.metadata(pending.replacementToken, state.active.deviceId)) {
            is MetadataReply.Ok ->
                if (confirmsPending(state, m.metadata)) promote(state, pending, m.metadata.vehicleId, m.metadata.expiresAtMs)
                else held(MISMATCH, false)
            MetadataReply.Unauthorized -> renew(state, pending)
            is MetadataReply.Unknown -> held(m.reason, true)
            is MetadataReply.EndpointError -> held("http_${m.httpCode}", false)
        }
    }

    private suspend fun checkActive(state: CredentialState): RenewalStatus {
        val active = state.active
        val m = when (val r = api.metadata(active.deviceToken, active.deviceId)) {
            is MetadataReply.Ok -> r.metadata
            MetadataReply.Unauthorized -> return RenewalStatus.Rejected(active)
            // No renewal is in flight, so the unchanged credential may still upload (e.g. route not yet deployed).
            is MetadataReply.Unknown -> return RenewalStatus.Ready(active, "metadata_unavailable")
            is MetadataReply.EndpointError -> return RenewalStatus.Ready(active, "metadata_http_${r.httpCode}")
        }
        if (!sameIdentity(active, m.deviceId, m.vehicleId)) return RenewalStatus.Ready(active, MISMATCH)
        if (!m.renewalDue) return RenewalStatus.Ready(active, "current")
        val staged = CredentialState(active, PendingRenewal(newRequestId(), DeviceTokens.generate(random)))
        // Durable staging before any dispatch; on failure nothing was sent and the old credential is unchanged.
        if (!store.write(staged)) return RenewalStatus.Ready(active, "renewal_staging_failed")
        return renew(staged, staged.pending!!)
    }

    private suspend fun renew(state: CredentialState, pending: PendingRenewal): RenewalStatus {
        val active = state.active
        return when (val r = api.renew(active.deviceToken, active.deviceId, pending.requestId, pending.replacementTokenHash)) {
            is RenewReply.Ok -> {
                val receipt = r.receipt
                if (receipt.requestId == pending.requestId && sameIdentity(active, receipt.deviceId, receipt.vehicleId)) {
                    promote(state, pending, receipt.vehicleId, receipt.expiresAtMs)
                } else held(MISMATCH, false)
            }
            RenewReply.NotDue -> clearIfProvedUncommitted(state, pending)
            RenewReply.Conflict -> held("renewal_conflict", false)
            RenewReply.Unauthorized -> RenewalStatus.Rejected(active)
            is RenewReply.Unknown -> held(r.reason, true)
            is RenewReply.EndpointError -> held("http_${r.httpCode}", false)
        }
    }

    /** Only a not-due rejection plus valid, current, not-due old metadata unrelated to this request proves it never committed. */
    private suspend fun clearIfProvedUncommitted(state: CredentialState, pending: PendingRenewal): RenewalStatus {
        val active = state.active
        val m = (api.metadata(active.deviceToken, active.deviceId) as? MetadataReply.Ok)?.metadata
            ?: return held("renewal_unresolved", true)
        val proved = sameIdentity(active, m.deviceId, m.vehicleId) && m.expiresAtMs > m.serverTimeMs &&
            !m.renewalDue && m.renewalRequestId != pending.requestId
        if (!proved) return held("renewal_unresolved", true)
        return if (store.write(CredentialState(active))) RenewalStatus.Ready(active, "renewal_not_due") else STORAGE_FAILED
    }

    private fun confirmsPending(state: CredentialState, m: CredentialMetadata) =
        sameIdentity(state.active, m.deviceId, m.vehicleId) && m.renewalRequestId == state.pending?.requestId &&
            m.expiresAtMs > m.serverTimeMs

    private fun promote(state: CredentialState, pending: PendingRenewal, vehicleId: String, expiresAtMs: Long): RenewalStatus {
        val promoted = DeviceCredential(state.active.deviceId, state.active.vehicleId ?: vehicleId, pending.replacementToken, expiresAtMs)
        // Durable promotion before telemetry; on failure the pending record on disk still recovers it next run.
        return if (store.write(CredentialState(promoted))) RenewalStatus.Ready(promoted, "renewed") else STORAGE_FAILED
    }

    private fun sameIdentity(active: DeviceCredential, deviceId: String, vehicleId: String) =
        deviceId == active.deviceId && (active.vehicleId == null || active.vehicleId == vehicleId)

    private companion object {
        const val MISMATCH = "renewal_mismatch"
        val STORAGE_FAILED = RenewalStatus.Held("storage_write_failed", retryable = false)
        fun held(reason: String, retryable: Boolean) = RenewalStatus.Held(reason, retryable)
    }
}

/** [report] is the collector diagnostic result, null when none was sent; it never affects [upload]. */
data class UploadCycle(val renewal: RenewalStatus, val upload: UploadOutcome?, val report: CollectorReportReply? = null)

/**
 * One lifecycle-lock hold covers metadata, staging, renewal, promotion, upload and applying a pause, so disconnect and
 * re-pair cannot interleave. A rejection pauses only if the credential that produced it is still current, so an old
 * worker can never pause a newly paired session. Returns null when skipped or not paired.
 */
suspend fun renewAndUpload(
    lock: LifecycleLock,
    renewer: CredentialRenewer,
    current: () -> DeviceCredential?,
    skipIf: () -> Boolean = { false },
    run: suspend (DeviceCredential) -> UploadOutcome,
    onPausedReauth: (DeviceCredential, UploadOutcome.PausedReauth) -> Unit,
    /**
     * Collector diagnostics, after renewal and telemetry, only with a Ready credential that is still current and was
     * not rejected by telemetry. Its outcome is recorded but changes nothing else; a throw becomes a fixed failure.
     */
    report: suspend (DeviceCredential) -> CollectorReportReply = { CollectorReportReply.Failed("not_configured") },
): UploadCycle? = lock.exclusive {
    if (skipIf()) return@exclusive null
    fun stillCurrent(c: DeviceCredential) = current()?.let { it.deviceId == c.deviceId && it.deviceToken == c.deviceToken } == true
    when (val status = renewer.prepare()) {
        RenewalStatus.NoCredential -> null
        is RenewalStatus.Held -> UploadCycle(status, null)
        is RenewalStatus.Rejected -> {
            val paused = UploadOutcome.PausedReauth(401)
            if (stillCurrent(status.credential)) onPausedReauth(status.credential, paused)
            UploadCycle(status, paused)
        }
        is RenewalStatus.Ready -> {
            val outcome = run(status.credential)
            if (outcome is UploadOutcome.PausedReauth && stillCurrent(status.credential)) onPausedReauth(status.credential, outcome)
            val reply = if (outcome !is UploadOutcome.PausedReauth && stillCurrent(status.credential)) {
                try {
                    report(status.credential)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CollectorReportReply.Failed(e.javaClass.simpleName)
                }
            } else null
            UploadCycle(status, outcome, reply)
        }
    }
}
