package app.danim.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Device self-reported collection diagnostics (`POST /devices/collector-report`). A latest-state snapshot, never a vehicle observation,
 * capability or proof that the Vehicle SDK loop is running. It is not queued durably: a failed report is simply
 * replaced by the next fresh snapshot.
 */
enum class CollectorState(val wire: String) { RUNNING("running"), STOPPED("stopped"), UNKNOWN("unknown") }

/** One atomic read of the unquarantined rows bound to a device. */
data class QueueSnapshot(val count: Int, val oldestObservedAtMs: Long?)

/** [queue] null means the local queue could not be read: unknown, never zero. */
data class CollectorReport(
    val deviceId: String,
    val reportId: String,
    val reportedAtMs: Long,
    val collectionWanted: Boolean,
    val collectorState: CollectorState,
    val queue: QueueSnapshot?,
)

enum class CollectorReportOutcome(val wire: String) { STORED("stored"), DUPLICATE("duplicate"), SUPERSEDED("superseded") }

/** [receivedAtMs] is null exactly for [CollectorReportOutcome.SUPERSEDED]. */
data class CollectorReportAck(val reportId: String, val outcome: CollectorReportOutcome, val receivedAtMs: Long?)

sealed interface CollectorReportReply {
    data class Acked(val ack: CollectorReportAck) : CollectorReportReply
    /** 401: the credential has no current authority. Diagnostic only; telemetry decides pausing. */
    data object Unauthorized : CollectorReportReply
    /** 409 `collector_report_conflict`: the latest report id was reused with different content. */
    data object Conflict : CollectorReportReply
    /** Transport, malformed, configuration or server failure. [reason] is a fixed string. */
    data class Failed(val reason: String) : CollectorReportReply
}

/** Implementations never log the token, headers or bodies. */
interface CollectorReportApi {
    suspend fun report(token: String, report: CollectorReport): CollectorReportReply
}

object CollectorReportWire {
    const val MAX_QUEUED = 1_000_000
    private const val CONFLICT = "collector_report_conflict"
    private val ACK_KEYS = setOf("reportId", "outcome", "receivedAt")
    private val json = Json

    /** Inconsistent or out-of-range snapshots are sent as unknown (both null), never repaired into a number. */
    fun encode(r: CollectorReport): String {
        val q = r.queue?.takeIf {
            it.count in 0..MAX_QUEUED && ((it.count == 0) == (it.oldestObservedAtMs == null))
        }
        val body = JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "deviceId" to JsonPrimitive(r.deviceId),
                "reportId" to JsonPrimitive(r.reportId),
                "reportedAt" to JsonPrimitive(Iso.format(r.reportedAtMs)),
                "collectionWanted" to JsonPrimitive(r.collectionWanted),
                "collectorState" to JsonPrimitive(r.collectorState.wire),
                "queuedObservationCount" to (q?.let { JsonPrimitive(it.count) } ?: JsonNull),
                "oldestQueuedObservedAt" to (q?.oldestObservedAtMs?.let { JsonPrimitive(Iso.format(it)) } ?: JsonNull),
            ),
        )
        return json.encodeToString(JsonObject.serializer(), body)
    }

    /** Exactly `{data:{reportId,outcome,receivedAt}}` for the report that was sent; anything else is no ACK. */
    fun parseAck(body: String, sentReportId: String): CollectorReportAck? = runCatching {
        val envelope = TelemetryWire.parseObject(body)!!
        require(envelope.keys == setOf("data"))
        val d = envelope["data"] as JsonObject
        require(d.keys == ACK_KEYS)
        val id = (d["reportId"] as JsonPrimitive).takeIf { it.isString }!!.content
        require(id == sentReportId)
        val wire = (d["outcome"] as JsonPrimitive).takeIf { it.isString }!!.content
        val outcome = CollectorReportOutcome.entries.single { it.wire == wire }
        val received = when (val r = d["receivedAt"]) {
            JsonNull -> null
            is JsonPrimitive -> Iso.parse(r.takeIf { it.isString }!!.content)
            else -> error("receivedAt")
        }
        require((outcome == CollectorReportOutcome.SUPERSEDED) == (received == null))
        CollectorReportAck(id, outcome, received)
    }.getOrNull()

    fun classify(code: Int, body: String, sentReportId: String): CollectorReportReply = when {
        code in 200..299 -> parseAck(body, sentReportId)?.let { CollectorReportReply.Acked(it) } ?: CollectorReportReply.Failed("malformed_ack")
        code == 401 -> CollectorReportReply.Unauthorized
        code == 409 && TelemetryWire.parseErrorCode(body) == CONFLICT -> CollectorReportReply.Conflict
        else -> CollectorReportReply.Failed("http_$code")
    }
}

/** Builds a fresh snapshot for one credential's binding and sends it. Never throws (except cancellation). */
class CollectorReporter(
    private val api: CollectorReportApi,
    private val queue: suspend (binding: String) -> QueueSnapshot?,
    private val collectionWanted: () -> Boolean,
    private val collectorState: () -> CollectorState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    suspend fun send(credential: DeviceCredential): CollectorReportReply = try {
        val snapshot = try {
            queue(credential.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val report = CollectorReport(credential.deviceId, newId(), clock(), collectionWanted(), collectorState(), snapshot)
        api.report(credential.deviceToken, report)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CollectorReportReply.Failed(e.javaClass.simpleName)
    }
}
