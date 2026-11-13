package app.danim.automotive

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.data.AppDatabase
import app.danim.automotive.data.RoomOutboxStore
import app.danim.core.Availability
import app.danim.core.Observation
import app.danim.core.QueueSnapshot
import app.danim.core.Signal
import app.danim.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The collector-report queue snapshot on real on-disk Room: one statement, current binding, unquarantined only. */
@RunWith(AndroidJUnit4::class)
class OutboxSnapshotTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "outbox-snapshot-test.db"
    private val now = 1_800_000_000_000L

    private fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
    private fun row(id: String, at: Long) = Observation(id, at, Signal.ODOMETER_KM, Availability.UNAVAILABLE, null, Source.AAOS_CAR_PROPERTY)

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun cleanup() { ctx.deleteDatabase(name) }

    @Test
    fun snapshotCountsOnlyUnquarantinedRowsOfTheBinding() = runBlocking {
        open().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            assertEquals(QueueSnapshot(0, null), store.snapshot("device-A"))
            store.insert(listOf(row("a-new", now - 1), row("a-quarantined", now - 50)), now, "device-A")
            store.insert(listOf(row("b-older", now - 100)), now, "device-B")
            store.quarantine(listOf("a-quarantined"))
            assertEquals(QueueSnapshot(1, now - 1), store.snapshot("device-A"))
            assertEquals(QueueSnapshot(1, now - 100), store.snapshot("device-B"))
            store.deleteByEventIds(listOf("a-new"))
            assertEquals(QueueSnapshot(0, null), store.snapshot("device-A"))
        }
    }
}
