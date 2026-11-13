package app.danim.automotive

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.data.AppDatabase
import app.danim.automotive.data.RoomOutboxStore
import app.danim.core.Observation
import app.danim.core.OutboxUploader
import app.danim.core.TelemetryAck
import app.danim.core.TelemetryTransport
import app.danim.core.UploadResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a genuine version-1 database file (pre-binding schema, user_version 1) with legacy rows, then opens it with
 * Room v2 + MIGRATION_1_2. Room validates the migrated schema against the entities on open, so a column/index/default
 * mismatch fails here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun cleanup() { ctx.deleteDatabase(name) }

    private fun createVersion1WithLegacyRows() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` TEXT NOT NULL, `observedAtMs` INTEGER NOT NULL, `signal` TEXT NOT NULL, `availability` TEXT NOT NULL, `valueType` INTEGER NOT NULL, `numValue` REAL, `textValue` TEXT, `attempts` INTEGER NOT NULL, `enqueuedAtMs` INTEGER NOT NULL, `quarantined` INTEGER NOT NULL, `source` TEXT NOT NULL)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_eventId` ON `outbox` (`eventId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_observedAtMs` ON `outbox` (`observedAtMs`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAtMs` INTEGER NOT NULL, `lastHeartbeatMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `gap` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromMs` INTEGER NOT NULL, `toMs` INTEGER NOT NULL, `cause` TEXT NOT NULL)")
                    db.execSQL("INSERT INTO outbox (eventId, observedAtMs, signal, availability, valueType, numValue, textValue, attempts, enqueuedAtMs, quarantined, source) VALUES ('legacy-1', 1000, 'odometer_km', 'available', 1, 42.0, NULL, 3, 1000, 0, 'pleos_vehicle_sdk')")
                    db.execSQL("INSERT INTO outbox (eventId, observedAtMs, signal, availability, valueType, numValue, textValue, attempts, enqueuedAtMs, quarantined, source) VALUES ('legacy-2', 2000, 'range_km', 'available', 1, 7.5, NULL, 0, 2000, 0, 'aaos_car_property')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openV2() = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()

    @Test
    fun migratedSchemaValidatesAndBindingDefaultsToEmpty() {
        createVersion1WithLegacyRows()
        openV2().useDb { db ->
            val sdb = db.openHelper.writableDatabase // triggers migration + Room schema validation
            assertEquals(2, sdb.version)
            sdb.query("PRAGMA table_info(outbox)").use { c ->
                var found = false
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow("name")) == "binding") {
                        found = true
                        assertEquals(1, c.getInt(c.getColumnIndexOrThrow("notnull")))
                        assertEquals("''", c.getString(c.getColumnIndexOrThrow("dflt_value")))
                    }
                }
                assertTrue("binding column missing", found)
            }
            sdb.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_outbox_binding'").use { assertEquals(1, it.count) }
            sdb.query("SELECT eventId, source, attempts, binding FROM outbox ORDER BY eventId").use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals("legacy-1", c.getString(0)); assertEquals("pleos_vehicle_sdk", c.getString(1)); assertEquals(3, c.getInt(2)); assertEquals("", c.getString(3))
                c.moveToNext()
                assertEquals("legacy-2", c.getString(0)); assertEquals("aaos_car_property", c.getString(1)); assertEquals("", c.getString(3))
            }
        }
    }

    @Test
    fun legacyUnboundRowsAreNeverUploadedAndArePurgedAtNextPairing() = runBlocking {
        createVersion1WithLegacyRows()
        openV2().useDb { db ->
            val store = RoomOutboxStore(db.outbox())
            for (device in listOf("device-new", "", "legacy-1")) {
                if (device.isEmpty()) continue
                assertEquals(0, store.count(device))
                assertTrue(store.nextBatch(10, device).isEmpty())
            }
            val sent = mutableListOf<Observation>()
            val transport = object : TelemetryTransport {
                override suspend fun post(events: List<Observation>): UploadResponse {
                    sent += events
                    return UploadResponse.Success(TelemetryAck(events.map { it.eventId }.toSet(), null))
                }
            }
            OutboxUploader(store, transport, "device-new", clock = { 3_000L }).drain()
            assertTrue("legacy rows must not be uploaded under a new device", sent.isEmpty())
            // still stored (not silently lost) until the pairing commit purges them
            assertEquals(2, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2, store.purgeOtherBindings("device-new"))
            assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getInt(0) })
        }
    }
}
