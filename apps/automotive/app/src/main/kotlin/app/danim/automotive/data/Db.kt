package app.danim.automotive.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.danim.core.Availability
import app.danim.core.Observation
import app.danim.core.QueueSnapshot
import app.danim.core.OutboxEntry
import app.danim.core.OutboxStore
import app.danim.core.SessionRecord
import app.danim.core.SessionStore
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.UnobservedGap
import app.danim.core.Value

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["eventId"], unique = true), Index(value = ["observedAtMs"]), Index(value = ["binding"])],
)
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val eventId: String,
    val observedAtMs: Long,
    val signal: String,
    val availability: String,
    /** 0 none, 1 number, 2 text, 3 boolean */
    val valueType: Int,
    val numValue: Double?,
    val textValue: String?,
    val attempts: Int,
    val enqueuedAtMs: Long,
    /** 1 = server proved this event invalid; kept for diagnostics, never uploaded, still bounded by retention. */
    val quarantined: Int = 0,
    /** Source.wire; fixed at creation so retries send the same value. */
    val source: String = "pleos_vehicle_sdk",
    /** Device id this row was captured for. Immutable; uploads and counts only see the current device's rows. */
    @ColumnInfo(defaultValue = "''")
    val binding: String = "",
)

@Entity(tableName = "session")
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val lastHeartbeatMs: Long,
    val endedAtMs: Long?,
    val endReason: String?,
)

@Entity(tableName = "gap")
data class GapRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMs: Long,
    val toMs: Long,
    val cause: String,
)

data class QueueStatsRow(val queued: Int, val oldestMs: Long?)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<OutboxRow>)

    @Query("SELECT * FROM outbox WHERE quarantined = 0 AND binding = :binding ORDER BY observedAtMs, rowId LIMIT :limit")
    suspend fun nextBatch(limit: Int, binding: String): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE binding != :keep")
    suspend fun purgeOtherBindings(keep: String): Int

    @Query("DELETE FROM outbox WHERE eventId IN (:ids)")
    suspend fun deleteIds(ids: List<String>): Int

    @Query("UPDATE outbox SET quarantined = 1 WHERE eventId IN (:ids)")
    suspend fun quarantine(ids: List<String>): Int

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE eventId IN (:ids)")
    suspend fun bumpAttempts(ids: List<String>)

    @Query("DELETE FROM outbox WHERE observedAtMs < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query(
        "DELETE FROM outbox WHERE rowId IN (SELECT rowId FROM outbox ORDER BY observedAtMs, rowId " +
            "LIMIT (SELECT MAX(0, COUNT(*) - :max) FROM outbox))",
    )
    suspend fun trimToMax(max: Int): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE quarantined = 0 AND binding = :binding")
    suspend fun count(binding: String): Int

    @Query("SELECT MIN(observedAtMs) FROM outbox WHERE quarantined = 0 AND binding = :binding")
    suspend fun oldest(binding: String): Long?

    /** Count and oldest time in ONE statement, so the pair is a consistent snapshot. */
    @Query("SELECT COUNT(*) AS queued, MIN(observedAtMs) AS oldestMs FROM outbox WHERE quarantined = 0 AND binding = :binding")
    suspend fun snapshot(binding: String): QueueStatsRow

    @Query("DELETE FROM outbox")
    suspend fun clear(): Int
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session ORDER BY id DESC LIMIT 1")
    suspend fun latest(): SessionRow?

    @Insert
    suspend fun insert(row: SessionRow): Long

    @Query("UPDATE session SET lastHeartbeatMs = :at WHERE id = :id")
    suspend fun heartbeat(id: Long, at: Long)

    @Query("UPDATE session SET endedAtMs = :at, endReason = :reason, lastHeartbeatMs = :at WHERE id = :id")
    suspend fun end(id: Long, at: Long, reason: String)

    @Query("DELETE FROM session WHERE id NOT IN (SELECT id FROM session ORDER BY id DESC LIMIT 50)")
    suspend fun pruneSessions()

    @Insert
    suspend fun insertGap(row: GapRow)

    @Query("SELECT * FROM gap ORDER BY id DESC LIMIT :limit")
    suspend fun recentGaps(limit: Int): List<GapRow>

    @Query("DELETE FROM gap WHERE id NOT IN (SELECT id FROM gap ORDER BY id DESC LIMIT 200)")
    suspend fun pruneGaps()

    @Query("DELETE FROM session")
    suspend fun clearSessions()

    @Query("DELETE FROM gap")
    suspend fun clearGaps()
}

@Database(entities = [OutboxRow::class, SessionRow::class, GapRow::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun sessions(): SessionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "danim.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Pre-binding rows get binding '' which matches no device: they are never uploaded and are purged at the next pairing. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN binding TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_binding ON outbox(binding)")
            }
        }
    }
}

class RoomOutboxStore(private val dao: OutboxDao) : OutboxStore {
    override suspend fun insert(observations: List<Observation>, nowMs: Long, binding: String) {
        dao.insertAll(observations.map { it.toRow(nowMs, binding) })
    }

    override suspend fun purgeOtherBindings(keep: String) = dao.purgeOtherBindings(keep)
    override suspend fun clear() = dao.clear()

    override suspend fun nextBatch(limit: Int, binding: String): List<OutboxEntry> =
        dao.nextBatch(limit, binding).map { OutboxEntry(it.rowId, it.toObservation(), it.attempts, it.enqueuedAtMs) }

    override suspend fun deleteByEventIds(eventIds: Collection<String>): Int =
        eventIds.chunked(500).sumOf { dao.deleteIds(it) }

    override suspend fun recordAttempt(eventIds: Collection<String>) {
        eventIds.chunked(500).forEach { dao.bumpAttempts(it) }
    }

    override suspend fun quarantine(eventIds: Collection<String>): Int =
        eventIds.chunked(500).sumOf { dao.quarantine(it) }

    override suspend fun deleteOlderThan(observedBeforeMs: Long) = dao.deleteOlderThan(observedBeforeMs)
    override suspend fun trimToMax(maxRows: Int) = dao.trimToMax(maxRows)
    override suspend fun count(binding: String) = dao.count(binding)
    override suspend fun oldestObservedAtMs(binding: String) = dao.oldest(binding)

    /** Unquarantined rows bound to [binding]; throws if the database cannot be read (the caller reports unknown). */
    suspend fun snapshot(binding: String): QueueSnapshot = dao.snapshot(binding).let { QueueSnapshot(it.queued, it.oldestMs) }
}

private fun Observation.toRow(nowMs: Long, binding: String): OutboxRow {
    val v = value
    return OutboxRow(
        eventId = eventId, observedAtMs = observedAtMs, signal = signal.key, availability = availability.wire,
        valueType = when (v) { null -> 0; is Value.Num -> 1; is Value.Text -> 2; is Value.Bool -> 3 },
        numValue = when (v) { is Value.Num -> v.v; is Value.Bool -> if (v.v) 1.0 else 0.0; else -> null },
        textValue = (v as? Value.Text)?.v,
        attempts = 0, enqueuedAtMs = nowMs, source = source.wire, binding = binding,
    )
}

private fun OutboxRow.toObservation() = Observation(
    eventId = eventId,
    observedAtMs = observedAtMs,
    signal = requireNotNull(Signal.fromKey(signal)),
    availability = requireNotNull(Availability.fromWire(availability)),
    source = Source.fromWire(source) ?: Source.PLEOS_VEHICLE_SDK,
    value = when (valueType) {
        1 -> Value.Num(numValue!!)
        2 -> Value.Text(textValue!!)
        3 -> Value.Bool(numValue == 1.0)
        else -> null
    },
)

class RoomSessionStore(private val dao: SessionDao) : SessionStore {
    override suspend fun latest() = dao.latest()?.let { SessionRecord(it.id, it.startedAtMs, it.lastHeartbeatMs, it.endedAtMs, it.endReason) }
    override suspend fun insert(startedAtMs: Long): Long {
        dao.pruneSessions()
        return dao.insert(SessionRow(startedAtMs = startedAtMs, lastHeartbeatMs = startedAtMs, endedAtMs = null, endReason = null))
    }
    override suspend fun heartbeat(id: Long, atMs: Long) = dao.heartbeat(id, atMs)
    override suspend fun end(id: Long, atMs: Long, reason: String) = dao.end(id, atMs, reason)
    override suspend fun recentGaps(limit: Int) = dao.recentGaps(limit).map { UnobservedGap(it.fromMs, it.toMs, it.cause) }
    override suspend fun saveGap(gap: UnobservedGap) {
        dao.pruneGaps()
        dao.insertGap(GapRow(fromMs = gap.fromMs, toMs = gap.toMs, cause = gap.cause))
    }
}
