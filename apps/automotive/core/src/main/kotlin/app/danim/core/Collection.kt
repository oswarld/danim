package app.danim.core

import kotlinx.coroutines.sync.withLock

/**
 * Decides which readings are persisted. A reading is emitted when its status/value changed
 * (rate-limited per signal) or when [heartbeatMs] has passed since the last emission, so an
 * unchanged value still proves the collector was alive. Not thread-safe; call from one dispatcher.
 */
class SignalGate(
    private val heartbeatMs: Long = 5 * 60_000,
    /**
     * Signals that re-report sooner. Motion bounds trips: the server treats more than five minutes without a
     * motion reading as a gap, and the collector polls once a minute, so motion re-reports every four.
     */
    private val heartbeatOverrideMs: Map<Signal, Long> = mapOf(Signal.VEHICLE_MOTION to 4 * 60_000),
    private val minChangeIntervalMs: Map<Signal, Long> = mapOf(
        Signal.ODOMETER_KM to 30_000,
        Signal.RANGE_KM to 60_000,
        Signal.BATTERY_SOC_PERCENT to 30_000,
        Signal.FUEL_LEVEL_PERCENT to 60_000,
    ),
) {
    private data class Last(val availability: Availability, val value: Value?, val atMs: Long)

    private val last = HashMap<Signal, Last>()

    fun shouldEmit(o: Observation): Boolean {
        val prev = last[o.signal]
        val emit = when {
            prev == null -> true
            o.observedAtMs <= prev.atMs -> false // late or duplicate delivery never replaces newer state
            prev.availability == o.availability && prev.value == o.value ->
                o.observedAtMs - prev.atMs >= (heartbeatOverrideMs[o.signal] ?: heartbeatMs)
            else -> o.observedAtMs - prev.atMs >= (minChangeIntervalMs[o.signal] ?: 0L)
        }
        if (emit) last[o.signal] = Last(o.availability, o.value, o.observedAtMs)
        return emit
    }

    fun reset() = last.clear()
}

/**
 * Unit conversions from the official Vehicle SDK 2.3.2 sources (checked against the SDK source JAR).
 * Odometer is already km and fuel economy already L/100 km, so they need no conversion.
 */
object Conversions {
    /** EV efficiency arrives as km/kWh; canonical is kWh/100 km = 100 / value. Zero, negative or non-finite is unavailable (null). */
    fun kmPerKwhToKwhPer100km(kmPerKwh: Float?): Double? {
        val v = kmPerKwh?.toDouble() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return (100.0 / v).takeIf { it.isFinite() }
    }

    /** Fuel economy is already L/100 km; accept only finite, non-negative values. */
    fun fuelEconomyLPer100km(raw: Float?): Double? =
        raw?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
}

enum class EndReason { USER_STOP, DISCONNECT, SERVICE_DESTROYED, UNPAIRED }

/** One collection session as persisted: [lastHeartbeatMs] advances while running; [endedAtMs] is set on clean stop. */
data class SessionRecord(
    val id: Long,
    val startedAtMs: Long,
    val lastHeartbeatMs: Long,
    val endedAtMs: Long?,
    val endReason: String?,
)

/** An interval during which nothing was collected and the cause is unknown or abnormal. Never hidden. */
data class UnobservedGap(val fromMs: Long, val toMs: Long, val cause: String) {
    val durationMs get() = toMs - fromMs
}

interface SessionStore {
    suspend fun latest(): SessionRecord?
    suspend fun insert(startedAtMs: Long): Long
    suspend fun heartbeat(id: Long, atMs: Long)
    suspend fun end(id: Long, atMs: Long, reason: String)
    suspend fun recentGaps(limit: Int): List<UnobservedGap>
    suspend fun saveGap(gap: UnobservedGap)
}

/**
 * Tracks session boundaries so a crash, force-stop, reboot or sleep is reported as an
 * unobserved gap instead of being silently bridged.
 */
class SessionTracker(
    private val store: SessionStore,
    private val minGapMs: Long = 60_000,
    /** Expected heartbeat period is about a minute; a longer silence inside one process means the device slept or froze. */
    private val beatGapMs: Long = 180_000,
) {
    var currentId: Long? = null
        private set

    private var lastBeatMs: Long = 0

    /** Starts a session; returns the gap since the previous one when it did not end cleanly or was long. */
    suspend fun begin(nowMs: Long): UnobservedGap? {
        val prev = store.latest()
        val gap = when {
            prev == null -> null
            prev.endedAtMs == null && nowMs - prev.lastHeartbeatMs >= minGapMs ->
                UnobservedGap(prev.lastHeartbeatMs, nowMs, "previous_session_not_closed")
            prev.endedAtMs != null && nowMs - prev.endedAtMs >= minGapMs ->
                UnobservedGap(prev.endedAtMs, nowMs, "stopped:${prev.endReason ?: "unknown"}")
            else -> null
        }
        if (gap != null) store.saveGap(gap)
        currentId = store.insert(nowMs)
        lastBeatMs = nowMs
        return gap
    }

    /**
     * Advances the heartbeat. If the previous heartbeat is older than [beatGapMs] (suspend, freeze, clock jump
     * within the same process) the delayed interval is recorded *before* the heartbeat is overwritten.
     */
    suspend fun beat(nowMs: Long): UnobservedGap? {
        val id = currentId ?: return null
        val gap = if (lastBeatMs > 0 && nowMs - lastBeatMs >= beatGapMs) {
            UnobservedGap(lastBeatMs, nowMs, "heartbeat_delayed").also { store.saveGap(it) }
        } else null
        store.heartbeat(id, nowMs)
        lastBeatMs = nowMs
        return gap
    }

    suspend fun end(nowMs: Long, reason: EndReason) {
        currentId?.let { store.end(it, nowMs, reason.name.lowercase()) }
        currentId = null
    }
}

/**
 * Persists accepted observations as soon as they arrive: each received batch is gated and inserted before
 * the next receive, so nothing waits in RAM for a later callback. Returns only after the channel is closed
 * and fully drained, which makes shutdown "close, then join" safe. The insert is non-cancellable so a
 * cancel racing with a batch cannot lose it.
 */
class ObservationPipeline(
    private val store: OutboxStore,
    private val gate: SignalGate,
    /** Device id the collector session was started for; rows keep it forever. */
    private val binding: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPersisted: (List<Observation>) -> Unit = {},
) {
    suspend fun consume(channel: kotlinx.coroutines.channels.ReceiveChannel<Observation>) {
        while (true) {
            val first = channel.receiveCatching()
            if (first.isClosed) return
            val batch = ArrayList<Observation>()
            batch += first.getOrThrow()
            while (true) batch += channel.tryReceive().getOrNull() ?: break
            val accepted = batch.filter(gate::shouldEmit)
            if (accepted.isEmpty()) continue
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { store.insert(accepted, clock(), binding) }
            onPersisted(accepted)
        }
    }
}

/** Serialises everything that touches the credential/outbox pairing: upload runs, disconnect and re-pair commits. */
class LifecycleLock {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Upload run under the lifecycle lock. The credential is read inside the lock and its device id becomes the
 * binding, so rows, token and device id always belong together; no credential means no run.
 */
suspend fun <R> uploadExclusive(
    lock: LifecycleLock,
    credential: () -> DeviceCredential?,
    run: suspend (DeviceCredential) -> R,
): R? = lock.exclusive {
    val cred = credential() ?: return@exclusive null
    run(cred)
}

/**
 * Upload run whose side effects are applied under the same lock hold. [skipIf] and [onPausedReauth] execute inside
 * the lock; a 401/403 pauses only if the credential that produced it is still the current one, so an old
 * session's rejection can never pause a newly paired session.
 */
suspend fun uploadAndApply(
    lock: LifecycleLock,
    credential: () -> DeviceCredential?,
    skipIf: () -> Boolean = { false },
    run: suspend (DeviceCredential) -> UploadOutcome,
    onPausedReauth: (DeviceCredential, UploadOutcome.PausedReauth) -> Unit,
): UploadOutcome? = lock.exclusive {
    val cred = credential() ?: return@exclusive null
    if (skipIf()) return@exclusive null
    val outcome = run(cred)
    if (outcome is UploadOutcome.PausedReauth && credential()?.deviceId == cred.deviceId) onPausedReauth(cred, outcome)
    outcome
}

data class DrainResult(val drained: Boolean, val discardedTasks: Int)

/**
 * Shuts [executor] down and waits for it. Never reports "drained" unless it really terminated: on timeout the queue is
 * discarded ([DrainResult.discardedTasks]) and the caller must record the loss. Runs the wait on IO, never the caller's thread.
 */
suspend fun shutdownAndAwait(executor: java.util.concurrent.ExecutorService, timeoutMs: Long): DrainResult =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        executor.shutdown()
        if (executor.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) DrainResult(true, 0)
        else DrainResult(false, executor.shutdownNow().size)
    }

/**
 * [fenced]: the stop intent and "disconnect incomplete" fence reached disk before anything was removed, so a retained
 * credential cannot restart automatic work after process loss. [removed]: every local record, including the
 * credential, was durably removed. [finished]: the fence was then durably lifted.
 */
data class DisconnectResult(val fenced: Boolean, val removed: Boolean, val finished: Boolean) {
    val complete: Boolean get() = removed && finished
}

/**
 * Disconnect ordering: durably fence automatic work first, stop new work, cancel uploads, **wait for the collector to
 * finish draining**, and only then clear local data under the lock, so a late drain can never insert after the clear.
 * The fence is lifted ([finish]) only after a complete removal, in the same lock hold.
 */
class DisconnectCoordinator(
    private val lock: LifecycleLock,
    private val cancelUploads: () -> Unit,
    private val stopCollectorAndJoin: suspend () -> Unit,
    /** Returns true only when every local record, including the credential, was durably removed. */
    private val clearLocal: suspend () -> Boolean,
    /** Durably clears collection intent/consent and sets the fence; false if that did not reach disk. */
    private val fenceDurably: () -> Boolean = { true },
    /** Durably lifts the fence after a complete removal. */
    private val finish: () -> Boolean = { true },
) {
    suspend fun disconnect(): DisconnectResult {
        val fenced = fenceDurably()
        cancelUploads()
        stopCollectorAndJoin()
        return lock.exclusive {
            val removed = clearLocal()
            DisconnectResult(fenced, removed, finished = removed && finish())
        }
    }
}

sealed interface PairingCommit {
    /** [cleanupSaved] is false when [commitPairing]'s follow-up cleanup did not durably complete. */
    data class Published(val purged: Int, val cleanupSaved: Boolean = true) : PairingCommit

    /** The credential was not durably stored: nothing was purged and nothing may report success. */
    data object StorageFailed : PairingCommit
}

/**
 * Re-pair commit, atomic w.r.t. uploads: durably publish the new credential first, then purge rows of any other
 * device and run [onPublished]. A failed [save] leaves the previous device's queue and state untouched.
 */
suspend fun commitPairing(
    lock: LifecycleLock,
    store: OutboxStore,
    credential: DeviceCredential,
    /** Cleanup after a durable publication (pairing secret, pause, fence); returns false if any of it was not saved. */
    onPublished: suspend () -> Boolean = { true },
    save: suspend (DeviceCredential) -> Boolean,
): PairingCommit = lock.exclusive {
    if (!save(credential)) return@exclusive PairingCommit.StorageFailed
    val purged = store.purgeOtherBindings(credential.deviceId)
    PairingCommit.Published(purged, cleanupSaved = onPublished())
}
