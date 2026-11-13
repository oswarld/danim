package app.danim.core

sealed interface UploadResponse {
    /** HTTP 2xx. [ack] is null when the body was not a valid acknowledgement (nothing may be deleted). */
    data class Success(val ack: TelemetryAck?) : UploadResponse

    /** Transport failure, 408, 429 or 5xx. Safe to retry with the same event ids; never a reason to delete. */
    data class Transient(val retryAfterMs: Long? = null, val reason: String) : UploadResponse

    /** 401/403: device credential is not accepted. Stop and require re-pairing. */
    data class AuthRejected(val httpCode: Int) : UploadResponse

    /**
     * The server answered HTTP 409 with the explicit contract code `event_conflict` on `/telemetry`
     * (event-receipt content or device collision). Only this exact status/code pair proves that a single
     * event is at fault; see [HttpClassifier].
     */
    data class Rejected(val httpCode: Int, val errorCode: String) : UploadResponse

    /**
     * Redirects, 404, 405, other 4xx, and every 400/409/413/422 that is not 409 `event_conflict`
     * (request-level `invalid_request`/`invalid_json`/`body_too_large`, generic `conflict`, unknown codes,
     * or a non-contract body such as a proxy page). Says nothing about the events, so nothing is deleted.
     */
    data class EndpointError(val httpCode: Int) : UploadResponse
}

interface TelemetryTransport {
    suspend fun post(events: List<Observation>): UploadResponse
}

object HttpClassifier {
    private const val EVENT_CONFLICT_STATUS = 409
    private const val EVENT_CONFLICT_CODE = "event_conflict"

    fun classify(httpCode: Int, body: String, retryAfterHeader: String?): UploadResponse = when {
        httpCode in 200..299 -> UploadResponse.Success(TelemetryWire.parseAck(body))
        httpCode == 401 || httpCode == 403 -> UploadResponse.AuthRejected(httpCode)
        httpCode == 429 || httpCode == 408 || httpCode >= 500 ->
            UploadResponse.Transient(parseRetryAfterMs(retryAfterHeader), "http_$httpCode")
        httpCode == EVENT_CONFLICT_STATUS && TelemetryWire.parseErrorCode(body) == EVENT_CONFLICT_CODE ->
            UploadResponse.Rejected(httpCode, EVENT_CONFLICT_CODE)
        else -> UploadResponse.EndpointError(httpCode)
    }

    /** Seconds form only; HTTP-date is ignored and the backoff policy applies instead. */
    fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it in 0..3600 }?.times(1000)
}

data class BackoffPolicy(
    val baseMs: Long = 30_000,
    val maxMs: Long = 15 * 60_000,
) {
    /** [attempt] starts at 1. Exponential, capped; a server Retry-After may raise the delay but never beyond [maxMs]. */
    fun delayMs(attempt: Int, retryAfterMs: Long? = null): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val exp = minOf(maxMs, baseMs shl shift)
        return minOf(maxMs, maxOf(exp, retryAfterMs ?: 0L))
    }
}

/** Receives counts the moment they happen, so diagnostics cannot lose them on an early return. */
interface UploadStats {
    fun acked(count: Int) {}
    fun quarantined(count: Int) {}

    /** Rows removed by retention (age or row cap) without ever being acknowledged. */
    fun expired(count: Int) {}

    companion object {
        val None = object : UploadStats {}
    }
}

sealed interface UploadOutcome {
    /** Nothing queued. */
    data object Idle : UploadOutcome

    /** [morePending] is true when the per-run batch bound was reached with data still queued. */
    data class Progress(val acked: Int, val quarantined: Int, val morePending: Boolean) : UploadOutcome

    data class RetryLater(val acked: Int, val quarantined: Int, val delayMs: Long, val reason: String) : UploadOutcome

    /** Credential rejected: uploads stay paused until the user pairs again. Data is retained. */
    data class PausedReauth(val httpCode: Int) : UploadOutcome

    /** Wrong endpoint/protocol/config. Data is retained; fix the setting (or wait for a server fix). */
    data class EndpointError(val httpCode: Int) : UploadOutcome
}

/**
 * Upload-and-ack only. A row leaves the queue in exactly three ways: the server acknowledged its event id,
 * retention removed it (age/row cap, reported through [UploadStats.expired]), or the server proved with a
 * 409 `event_conflict` that this single event is invalid, in which case it is quarantined, not deleted.
 * Transient failures, missing acknowledgements and endpoint errors only retain; per-run work is bounded by
 * [maxBatchesPerRun].
 */
class OutboxUploader(
    private val store: OutboxStore,
    private val transport: TelemetryTransport,
    /** Only rows bound to this device id are read; the transport must authenticate as the same device. */
    private val binding: String,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val retention: RetentionPolicy = RetentionPolicy(),
    private val batchSize: Int = MAX_EVENTS_PER_REQUEST,
    private val maxBatchesPerRun: Int = 10,
    private val stats: UploadStats = UploadStats.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(batchSize in 1..MAX_EVENTS_PER_REQUEST)
    }

    /** [runAttempt] is the 1-based WorkManager attempt, used only to scale the suggested delay. */
    suspend fun drain(runAttempt: Int = 1): UploadOutcome {
        retention.apply(store, clock()).let { if (it > 0) stats.expired(it) }
        var acked = 0
        var quarantined = 0
        repeat(maxBatchesPerRun) {
            val batch = store.nextBatch(batchSize, binding)
            if (batch.isEmpty()) {
                return if (acked == 0 && quarantined == 0) UploadOutcome.Idle
                else UploadOutcome.Progress(acked, quarantined, morePending = false)
            }
            when (val r = send(batch.map { it.observation })) {
                is SendResult.Auth -> return UploadOutcome.PausedReauth(r.httpCode)
                is SendResult.Endpoint -> return UploadOutcome.EndpointError(r.httpCode)
                is SendResult.Retry -> {
                    store.recordAttempt(batch.map { it.observation.eventId })
                    return UploadOutcome.RetryLater(acked, quarantined, backoff.delayMs(runAttempt, r.retryAfterMs), r.reason)
                }
                is SendResult.Done -> {
                    acked += r.ackedIds.size
                    quarantined += r.quarantinedIds.size
                    val unacked = batch.map { it.observation.eventId }.filter { it !in r.ackedIds && it !in r.quarantinedIds }
                    if (unacked.isNotEmpty()) {
                        store.recordAttempt(unacked)
                        if (r.ackedIds.isEmpty() && r.quarantinedIds.isEmpty()) {
                            return UploadOutcome.RetryLater(acked, quarantined, backoff.delayMs(runAttempt), "no_ack")
                        }
                    }
                }
            }
        }
        return UploadOutcome.Progress(acked, quarantined, morePending = store.count(binding) > 0)
    }

    private sealed interface SendResult {
        data class Done(val ackedIds: Set<String>, val quarantinedIds: Set<String>) : SendResult
        data class Retry(val retryAfterMs: Long?, val reason: String) : SendResult
        data class Auth(val httpCode: Int) : SendResult
        data class Endpoint(val httpCode: Int) : SendResult
    }

    private suspend fun send(events: List<Observation>): SendResult =
        when (val resp = transport.post(events)) {
            is UploadResponse.Success -> {
                val sent = events.map { it.eventId }.toSet()
                val ids = resp.ack?.acceptedEventIds.orEmpty().intersect(sent)
                if (ids.isNotEmpty()) {
                    store.deleteByEventIds(ids)
                    stats.acked(ids.size)
                }
                SendResult.Done(ids, emptySet())
            }
            is UploadResponse.Transient -> SendResult.Retry(resp.retryAfterMs, resp.reason)
            is UploadResponse.AuthRejected -> SendResult.Auth(resp.httpCode)
            is UploadResponse.EndpointError -> SendResult.Endpoint(resp.httpCode)
            is UploadResponse.Rejected ->
                if (events.size == 1) {
                    store.quarantine(listOf(events[0].eventId))
                    stats.quarantined(1)
                    SendResult.Done(emptySet(), setOf(events[0].eventId))
                } else {
                    // Bisect so one invalid event cannot block its neighbours.
                    val mid = events.size / 2
                    val left = send(events.subList(0, mid))
                    if (left !is SendResult.Done) left
                    else when (val right = send(events.subList(mid, events.size))) {
                        is SendResult.Done -> SendResult.Done(left.ackedIds + right.ackedIds, left.quarantinedIds + right.quarantinedIds)
                        else -> right
                    }
                }
        }
}
