package app.danim.automotive

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.work.UploadScheduler
import app.danim.core.Availability
import app.danim.core.CollectorReportOutcome
import app.danim.core.CollectorReportReply
import app.danim.core.Observation
import app.danim.core.PairingCommit
import app.danim.core.PairingEnd
import app.danim.core.PairingPoller
import app.danim.core.QueueSnapshot
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.StoredCredential
import app.danim.core.commitPairing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in, two-process collector-report scenario against the local stack only, for a freshly owned emulator. Never runs
 * by default: the host passes `danimCollectorLocal=true` and runs [publishBoundQueueSnapshotWithoutActivity] and
 * [uploadAndReportWithoutActivity] as separate instrumentation processes.
 *
 * Evidence class: instrumented test process, real Room on an emulator, real local HTTP. Reports are sent directly
 * (phase 1) or by the production UploadWorker after an explicit test kick (phase 2). The queued rows are SYNTHETIC wire
 * fixtures (status `unavailable`, null value) written by this test, not Vehicle SDK readings; `source` is only the
 * required wire enum. Nothing here shows Vehicle SDK collection, autonomous scheduling or real-car health.
 *
 * No Activity or collector start, no credential/body/log output: markers carry only the pairing code, UUIDs, counts and
 * the process id, and every assertion message is a fixed string.
 */
@RunWith(AndroidJUnit4::class)
class LocalCollectorReportTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** Runs before any vault, settings or Room access. */
    private fun guard() {
        assumeTrue("opt-in local collector report test", InstrumentationRegistry.getArguments().getString("danimCollectorLocal") == "true")
        assertTrue("debug_only", BuildConfig.DEBUG)
        assertEquals("compiled_local_api_required", LOCAL_API, BuildConfig.DEFAULT_API_BASE_URL)
        assertEquals("compiled_local_site_required", LOCAL_SITE, BuildConfig.DEFAULT_SITE_URL)
    }

    @Test
    fun publishBoundQueueSnapshotWithoutActivity() = runBlocking {
        guard()
        status("DANIM_COLLECTOR_PROCESS pid=${android.os.Process.myPid()}")
        val g = ctx.graph
        assertEquals("no_existing_credential_state", StoredCredential.Absent, g.vault.state.read())
        assertTrue("no_existing_pairing", g.vault.loadPending() == null)
        assertEquals("no_queued_records", 0L, longQuery("SELECT COUNT(*) FROM outbox"))
        g.settings.apiBaseUrl = LOCAL_API
        g.settings.siteUrl = LOCAL_SITE
        assertEquals("local_api_only", LOCAL_API, g.settings.apiConfig()?.apiBaseUrl)

        val start = g.http.start().getOrNull()
        assertTrue("pairing_start_failed", start != null)
        assertTrue("pairing_not_stored", g.vault.savePending(start!!))
        status("DANIM_COLLECTOR_PAIRING_CODE=${start.code}")
        val end = PairingPoller(g.http).run(start) // existing 5 s minimum interval, stops at expiry
        assertTrue("pairing_not_claimed", end is PairingEnd.Claimed)
        val cred = (end as PairingEnd.Claimed).credential
        val commit = commitPairing(g.lifecycle, g.outbox, cred, onPublished = { g.vault.clearPending() }) { g.vault.saveCredential(it) }
        assertTrue("credential_not_stored", commit is PairingCommit.Published && commit.cleanupSaved)
        g.settings.collectionWanted = false

        // Oldest first: the quarantined and other-binding rows are older, so a wrong filter would change the oldest time.
        val now = System.currentTimeMillis()
        val event = UUID.randomUUID().toString()
        val quarantined = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        g.outbox.insert(listOf(fixture(event, now - 1_000), fixture(quarantined, now - 2_000)), now, cred.deviceId)
        g.outbox.insert(listOf(fixture(other, now - 3_000)), now, FAKE_BINDING)
        assertEquals("one_row_quarantined", 1, g.outbox.quarantine(listOf(quarantined)))

        assertEquals("bound_unquarantined_snapshot", QueueSnapshot(1, now - 1_000), g.outbox.snapshot(cred.deviceId))
        val reply = g.collectorReporter.send(cred)
        assertTrue("report_not_stored", reply is CollectorReportReply.Acked && reply.ack.outcome == CollectorReportOutcome.STORED)
        assertEquals("report_changed_queue", QueueSnapshot(1, now - 1_000), g.outbox.snapshot(cred.deviceId))
        status("DANIM_COLLECTOR_READY device=${cred.deviceId} vehicle=${cred.vehicleId} event=$event quarantined=$quarantined other=$other")
        status("DANIM_COLLECTOR_PROCESS pid=${android.os.Process.myPid()}")
    }

    @Test
    fun uploadAndReportWithoutActivity() = runBlocking {
        guard()
        status("DANIM_COLLECTOR_PROCESS pid=${android.os.Process.myPid()}")
        val g = ctx.graph
        assertEquals("local_api_only", LOCAL_API, g.settings.apiBaseUrl)
        assertEquals("local_site_only", LOCAL_SITE, g.settings.siteUrl)
        val stored = g.vault.state.read() as? StoredCredential.Present
        assertTrue("credential_missing", stored != null && stored.durable && stored.state.pending == null)
        val cred = stored!!.state.active
        assertTrue("uploads_fenced", !g.settings.pausedReauth && !g.settings.disconnectIncomplete)
        val queued = g.outbox.nextBatch(10, cred.deviceId).map { it.observation.eventId }
        assertEquals("bound_fixture_missing", 1, queued.size)
        val quarantinedBefore = longQuery("SELECT COUNT(*) FROM outbox WHERE binding = ? AND quarantined = 1", cred.deviceId)
        val otherBefore = longQuery("SELECT COUNT(*) FROM outbox WHERE binding != ?", cred.deviceId)
        assertEquals("quarantined_fixture_missing", 1L, quarantinedBefore)
        assertEquals("other_binding_fixture_missing", 1L, otherBefore)

        val ackedBefore = g.settings.ackedTotal
        val uploadAtBefore = g.settings.lastUploadAtMs
        g.settings.lastCollectorReportResult = "" // so only this run's worker report can satisfy the wait

        UploadScheduler.kick(ctx) // explicit test trigger for the production worker; not evidence of autonomous scheduling

        val deadline = System.currentTimeMillis() + 120_000
        var done = false
        while (System.currentTimeMillis() < deadline) {
            if (g.outbox.count(cred.deviceId) == 0 && g.settings.ackedTotal == ackedBefore + 1 &&
                g.settings.lastUploadAtMs > uploadAtBefore && g.settings.lastCollectorReportResult == CollectorReportOutcome.STORED.wire
            ) { done = true; break }
            delay(500)
        }
        assertTrue("upload_and_report_timeout", done)
        assertEquals("bound_snapshot_after_upload", QueueSnapshot(0, null), g.outbox.snapshot(cred.deviceId))
        assertEquals("quarantined_row_touched", 1L, longQuery("SELECT COUNT(*) FROM outbox WHERE binding = ? AND quarantined = 1", cred.deviceId))
        assertEquals("other_binding_row_touched", 1L, longQuery("SELECT COUNT(*) FROM outbox WHERE binding != ?", cred.deviceId))
        assertTrue("credential_changed", cred.deviceToken == g.vault.loadCredential()?.deviceToken)
        status("DANIM_COLLECTOR_FINISHED event=${queued.single()} acked=${g.settings.ackedTotal - ackedBefore} queued=${g.outbox.count(cred.deviceId)}")
        status("DANIM_COLLECTOR_PROCESS pid=${android.os.Process.myPid()}")
    }

    /** Synthetic wire fixture: an `unavailable` odometer status with no value; never a vehicle reading. */
    private fun fixture(id: String, atMs: Long) =
        Observation(id, atMs, Signal.ODOMETER_KM, Availability.UNAVAILABLE, null, Source.AAOS_CAR_PROPERTY)

    private fun longQuery(sql: String, vararg args: Any): Long =
        ctx.graph.db.query(sql, args).use { it.moveToFirst(); it.getLong(0) }

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private companion object {
        const val LOCAL_SITE = "http://10.0.2.2:3004"
        const val LOCAL_API = "http://10.0.2.2:59901/functions/v1/api"
        /** A binding no paired device can have; proves other bindings are excluded from snapshot and upload. */
        const val FAKE_BINDING = "00000000-0000-4000-8000-00000000f4ce"
    }
}
