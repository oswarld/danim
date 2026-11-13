package app.danim.core

data class OutboxEntry(
    val rowId: Long,
    val observation: Observation,
    val attempts: Int,
    val enqueuedAtMs: Long,
)

/**
 * Durable queue contract. Room implements it on device; tests use an in-memory fake.
 *
 * Every row is bound at insert time to an immutable [binding] (the paired device id the collector was started
 * with). Reads and uploads are scoped to the *current* binding, so a reading captured for one car can never be sent
 * under another device's credential, even if it lands in the table after a disconnect/re-pair.
 */
interface OutboxStore {
    suspend fun insert(observations: List<Observation>, nowMs: Long, binding: String)

    /** Pending (non-quarantined) rows of [binding], oldest-observed first, at most [limit]. */
    suspend fun nextBatch(limit: Int, binding: String): List<OutboxEntry>

    /** Deletes every row whose binding differs from [keep] (including legacy unbound rows); returns the count. */
    suspend fun purgeOtherBindings(keep: String): Int

    /** Deletes everything (disconnect). */
    suspend fun clear(): Int

    /** Removes rows by event id; returns how many existed. Called only after a server acknowledgement. */
    suspend fun deleteByEventIds(eventIds: Collection<String>): Int

    suspend fun recordAttempt(eventIds: Collection<String>)

    /**
     * Removes rows from the upload queue without deleting them: the server proved the event invalid.
     * Quarantined rows are never returned by [nextBatch] or counted by [count]; retention still bounds them.
     */
    suspend fun quarantine(eventIds: Collection<String>): Int

    suspend fun deleteOlderThan(observedBeforeMs: Long): Int

    /** Drops the oldest rows beyond [maxRows]; returns how many were dropped. */
    suspend fun trimToMax(maxRows: Int): Int

    suspend fun count(binding: String): Int

    suspend fun oldestObservedAtMs(binding: String): Long?
}

/** Bounded local retention: age and row cap. Server retention is 30 days; local is shorter on purpose. */
data class RetentionPolicy(
    val maxAgeMs: Long = 7L * 24 * 3600 * 1000,
    val maxRows: Int = 20_000,
) {
    /** Returns the number of rows discarded without ever being acknowledged. */
    suspend fun apply(store: OutboxStore, nowMs: Long): Int =
        store.deleteOlderThan(nowMs - maxAgeMs) + store.trimToMax(maxRows)
}
