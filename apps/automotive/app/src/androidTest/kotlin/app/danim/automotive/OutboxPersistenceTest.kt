package app.danim.automotive

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.data.AppDatabase
import app.danim.automotive.data.RoomOutboxStore
import app.danim.core.Availability
import app.danim.core.Observation
import app.danim.core.OutboxUploader
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.TelemetryAck
import app.danim.core.TelemetryTransport
import app.danim.core.UploadResponse
import app.danim.core.Value
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real on-disk Room (no in-memory DB, no mocks): persistence across close/reopen, binding scoping, ACK deletion. */
@RunWith(AndroidJUnit4::class)
class OutboxPersistenceTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "outbox-persistence-test.db"
    private val carA = "device-A"
    private val carB = "device-B"
    private val now = 1_800_000_000_000L

    private fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun cleanup() { ctx.deleteDatabase(name) }

    private fun obs(id: String, at: Long, signal: Signal = Signal.ODOMETER_KM, source: Source = Source.PLEOS_VEHICLE_SDK) =
        Observation(id, at, signal, Availability.AVAILABLE, Value.Num(at.toDouble()), source)

    private class AckingTransport(private val ack: (List<Observation>) -> Set<String>) : TelemetryTransport {
        val sent = mutableListOf<List<Observation>>()
        override suspend fun post(events: List<Observation>): UploadResponse {
            sent += events
            return UploadResponse.Success(TelemetryAck(ack(events), null))
        }
    }

    @Test
    fun eventIdSourceAndBindingSurviveCloseAndReopen() = runBlocking {
        val aaos = Observation("11111111-1111-1111-1111-111111111111", now - 5, Signal.RANGE_KM, Availability.AVAILABLE, Value.Num(321.5), Source.AAOS_CAR_PROPERTY)
        val denied = Observation("22222222-2222-2222-2222-222222222222", now - 4, Signal.VEHICLE_WARNING, Availability.PERMISSION_DENIED, null, Source.PLEOS_VEHICLE_SDK)
        open().useDb { db ->
            RoomOutboxStore(db.outbox()).insert(listOf(aaos, denied), now, carA)
        }
        // database object closed; reopen the same file
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            val rows = store.nextBatch(10, carA)
            assertEquals(listOf(aaos, denied), rows.map { it.observation }) // eventId, source, value, status, times identical
            assertEquals(Source.AAOS_CAR_PROPERTY, rows[0].observation.source)
            assertTrue(store.nextBatch(10, carB).isEmpty())
            assertEquals(2, store.count(carA))
            assertEquals(0, store.count(carB))
            assertEquals(now - 5, store.oldestObservedAtMs(carA))
        }
    }

    @Test
    fun ackDeletesOnlyAcknowledgedBoundRowsAndKeepsEverythingElse() = runBlocking {
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            store.insert(listOf(obs("a1", now - 3), obs("a2", now - 2), obs("a3", now - 1)), now, carA)
            store.insert(listOf(obs("b1", now - 3)), now, carB)
            val transport = AckingTransport { events -> events.map { it.eventId }.filter { it != "a2" }.toSet() } // server omits a2
            OutboxUploader(store, transport, carA, clock = { now }).drain()
            // first request carried only car A's rows; the unacknowledged a2 was then re-sent alone (b1 never appears)
            assertEquals(listOf(listOf("a1", "a2", "a3"), listOf("a2")), transport.sent.map { r -> r.map { it.eventId } })
            assertEquals(listOf("a2"), store.nextBatch(10, carA).map { it.observation.eventId }) // unacknowledged kept
            assertEquals(listOf("b1"), store.nextBatch(10, carB).map { it.observation.eventId }) // other device untouched
            assertEquals(2, db.outbox().nextBatch(10, carA).first().attempts) // retry bookkeeping persisted in Room
        }
    }

    @Test
    fun rowForAnotherDeviceIsNeverUploadedAndPurgeRemovesIt() = runBlocking {
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            store.insert(listOf(obs("old-car", now - 1)), now, carA)
            val transport = AckingTransport { it.map { e -> e.eventId }.toSet() }
            OutboxUploader(store, transport, carB, clock = { now }).drain()
            assertTrue(transport.sent.isEmpty())
            assertEquals(1, store.purgeOtherBindings(carB))
            assertEquals(0, store.nextBatch(10, carA).size)
        }
    }

    @Test
    fun quarantinedRowsStayStoredButLeaveTheUploadQueue() = runBlocking {
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            store.insert(listOf(obs("q1", now - 2), obs("ok", now - 1)), now, carA)
            assertEquals(1, store.quarantine(listOf("q1")))
            assertEquals(listOf("ok"), store.nextBatch(10, carA).map { it.observation.eventId })
            assertEquals(1, store.count(carA))
            assertEquals(1, db.openHelper.readableDatabase.query("SELECT eventId FROM outbox WHERE eventId='q1'").use { it.count })
            assertEquals(2, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getInt(0) })
        }
    }

    @Test
    fun duplicateEventIdIsNotInsertedTwice() = runBlocking {
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            store.insert(listOf(obs("dup", now - 1)), now, carA)
            store.insert(listOf(obs("dup", now - 1)), now, carA)
            assertEquals(1, store.count(carA))
        }
    }
}
