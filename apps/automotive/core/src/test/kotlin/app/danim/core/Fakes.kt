package app.danim.core

const val DEV = "device-A"

class InMemoryOutbox : OutboxStore {
    private var nextRow = 1L
    private val rows = linkedMapOf<String, OutboxEntry>()
    private val quarantinedIds = linkedSetOf<String>()
    private val bindings = HashMap<String, String>()

    override suspend fun insert(observations: List<Observation>, nowMs: Long, binding: String) {
        for (o in observations) {
            if (o.eventId !in rows) { rows[o.eventId] = OutboxEntry(nextRow++, o, 0, nowMs); bindings[o.eventId] = binding }
        }
    }

    override suspend fun purgeOtherBindings(keep: String) = deleteByEventIds(bindings.filterValues { it != keep }.keys.toList())

    override suspend fun clear() = deleteByEventIds(rows.keys.toList())

    override suspend fun nextBatch(limit: Int, binding: String) =
        rows.values.filter { it.observation.eventId !in quarantinedIds && bindings[it.observation.eventId] == binding }.sortedWith(compareBy({ it.observation.observedAtMs }, { it.rowId })).take(limit)

    override suspend fun deleteByEventIds(eventIds: Collection<String>) =
        eventIds.count { quarantinedIds.remove(it); bindings.remove(it); rows.remove(it) != null }

    override suspend fun quarantine(eventIds: Collection<String>) = eventIds.count { it in rows && quarantinedIds.add(it) }

    override suspend fun recordAttempt(eventIds: Collection<String>) {
        for (id in eventIds) rows[id]?.let { rows[id] = it.copy(attempts = it.attempts + 1) }
    }

    override suspend fun deleteOlderThan(observedBeforeMs: Long): Int {
        val old = rows.values.filter { it.observation.observedAtMs < observedBeforeMs }.map { it.observation.eventId }
        return deleteByEventIds(old)
    }

    override suspend fun trimToMax(maxRows: Int): Int {
        val excess = rows.size - maxRows
        if (excess <= 0) return 0
        val oldest = rows.values.sortedWith(compareBy({ it.observation.observedAtMs }, { it.rowId })).take(excess).map { it.observation.eventId }
        return deleteByEventIds(oldest)
    }

    override suspend fun count(binding: String) = nextBatch(Int.MAX_VALUE, binding).size
    override suspend fun oldestObservedAtMs(binding: String) = nextBatch(Int.MAX_VALUE, binding).minOfOrNull { it.observation.observedAtMs }
    fun ids() = rows.keys.filter { it !in quarantinedIds }
    fun bindingOf(id: String) = bindings[id]
    fun quarantined() = quarantinedIds.toList()
    fun attempts(id: String) = rows.getValue(id).attempts
}

class ScriptedTransport(private val script: (List<Observation>) -> UploadResponse) : TelemetryTransport {
    val requests = mutableListOf<List<Observation>>()
    override suspend fun post(events: List<Observation>): UploadResponse {
        requests += events
        return script(events)
    }
}

fun ackAll(events: List<Observation>) =
    UploadResponse.Success(TelemetryAck(events.map { it.eventId }.toSet(), "2026-01-01T00:00:00Z"))

var idCounter = 0
fun obs(at: Long, signal: Signal = Signal.ODOMETER_KM, v: Double = 1.0, id: String = "id-${idCounter++}") =
    Observation(id, at, signal, Availability.AVAILABLE, Value.Num(v))

class CountingStats : UploadStats {
    var acked = 0; var quarantined = 0; var expired = 0
    override fun acked(count: Int) { acked += count }
    override fun quarantined(count: Int) { quarantined += count }
    override fun expired(count: Int) { expired += count }
}
