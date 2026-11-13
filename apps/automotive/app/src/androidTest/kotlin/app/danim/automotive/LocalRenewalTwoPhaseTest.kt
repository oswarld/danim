package app.danim.automotive

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.work.UploadScheduler
import app.danim.core.Availability
import app.danim.core.CredentialState
import app.danim.core.DeviceCredential
import app.danim.core.DeviceTokens
import app.danim.core.MetadataReply
import app.danim.core.Observation
import app.danim.core.PairingCommit
import app.danim.core.PairingEnd
import app.danim.core.PairingPoller
import app.danim.core.PendingRenewal
import app.danim.core.RenewReply
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
import java.security.SecureRandom
import java.util.UUID

/**
 * Opt-in, two-process renewal recovery against the local stack only, for a freshly owned emulator. Never runs by
 * default: the host passes `danimRenewalLocal=true` and runs [stageAndCommitWithoutPromotion] and
 * [recoverAndUploadWithoutActivity] as separate instrumentation processes, stopping the app process in between.
 *
 * Phase 1 deliberately stops after the server committed the renewal and before local promotion. That models process
 * loss in that window; it is not a real TCP or network fault. Phase 2 lets the production UploadWorker recover it.
 * The queued observation is a SYNTHETIC wire fixture (status `unavailable`, null value) written by this test; it was
 * not read from the Vehicle SDK or any vehicle property. Its `source` is only the required wire enum.
 *
 * No Activity, collector, OAuth, service key or credential output: markers carry only the pairing code, UUIDs and
 * counts, and every assertion message is a fixed string so no token or hash can appear in a failure.
 */
@RunWith(AndroidJUnit4::class)
class LocalRenewalTwoPhaseTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    /** Runs before any vault, state, settings or Room access. */
    private fun guard() {
        assumeTrue("opt-in local renewal test", InstrumentationRegistry.getArguments().getString("danimRenewalLocal") == "true")
        assertTrue("debug_only", BuildConfig.DEBUG)
        assertEquals("compiled_local_api_required", LOCAL_API, BuildConfig.DEFAULT_API_BASE_URL)
        assertEquals("compiled_local_site_required", LOCAL_SITE, BuildConfig.DEFAULT_SITE_URL)
    }

    @Test
    fun stageAndCommitWithoutPromotion() = runBlocking {
        guard()
        status("DANIM_RENEWAL_PROCESS pid=${android.os.Process.myPid()}")
        val g = ctx.graph
        assertEquals("no_existing_credential_state", StoredCredential.Absent, g.vault.state.read())
        assertTrue("no_existing_pairing", g.vault.loadPending() == null)
        assertEquals("no_queued_records", 0L, totalQueued())
        g.settings.apiBaseUrl = LOCAL_API
        g.settings.siteUrl = LOCAL_SITE
        assertEquals("local_api_only", LOCAL_API, g.settings.apiConfig()?.apiBaseUrl)

        val start = g.http.start().getOrNull()
        assertTrue("pairing_start_failed", start != null)
        assertTrue("pairing_not_stored", g.vault.savePending(start!!))
        status("DANIM_RENEWAL_PAIRING_CODE=${start.code}")
        val end = PairingPoller(g.http).run(start) // existing 5 s minimum interval
        assertTrue("pairing_not_claimed", end is PairingEnd.Claimed)
        val claimed = (end as PairingEnd.Claimed).credential
        val commit = commitPairing(g.lifecycle, g.outbox, claimed, onPublished = { g.vault.clearPending() }) { g.vault.saveCredential(it) }
        assertTrue("credential_not_stored", commit is PairingCommit.Published)
        status("DANIM_RENEWAL_READY device=${claimed.deviceId} vehicle=${claimed.vehicleId}")

        awaitRenewalDue(g, claimed)

        val event = UUID.randomUUID().toString()
        val fixture = Observation(event, System.currentTimeMillis(), Signal.ODOMETER_KM, Availability.UNAVAILABLE, null, Source.AAOS_CAR_PROPERTY)
        g.outbox.insert(listOf(fixture), System.currentTimeMillis(), claimed.deviceId)
        assertEquals("fixture_not_queued", 1, g.outbox.count(claimed.deviceId))
        status("DANIM_RENEWAL_QUEUED event=$event")

        val pending = PendingRenewal(UUID.randomUUID().toString(), DeviceTokens.generate(SecureRandom()))
        g.lifecycle.exclusive {
            assertTrue("pending_not_durable", g.vault.state.write(CredentialState(claimed, pending)))
            val reply = g.http.credentials.renew(claimed.deviceToken, claimed.deviceId, pending.requestId, pending.replacementTokenHash)
            assertTrue("renew_not_ok", reply is RenewReply.Ok)
            val receipt = (reply as RenewReply.Ok).receipt
            assertTrue(
                "renew_identity_mismatch",
                receipt.deviceId == claimed.deviceId && receipt.vehicleId == claimed.vehicleId && receipt.requestId == pending.requestId,
            )
            // Intentionally NOT promoted: phase 2 must recover the committed renewal from the durable pending record.
        }
        val kept = g.vault.state.read() as? StoredCredential.Present
        assertTrue("pending_not_kept", kept?.state?.pending?.requestId == pending.requestId)
        status("DANIM_RENEWAL_COMMITTED request=${pending.requestId}")
    }

    @Test
    fun recoverAndUploadWithoutActivity() = runBlocking {
        guard()
        status("DANIM_RENEWAL_PROCESS pid=${android.os.Process.myPid()}")
        val g = ctx.graph
        assertEquals("local_api_only", LOCAL_API, g.settings.apiBaseUrl)
        assertEquals("local_site_only", LOCAL_SITE, g.settings.siteUrl)
        val stored = g.vault.state.read() as? StoredCredential.Present
        assertTrue("staged_state_missing", stored != null && stored.durable)
        val active = stored!!.state.active
        val pending = stored.state.pending
        assertTrue("pending_missing", pending != null)
        assertTrue("uploads_fenced", !g.settings.pausedReauth && !g.settings.disconnectIncomplete)
        val queued = g.outbox.nextBatch(200, active.deviceId).map { it.observation.eventId }
        assertEquals("fixture_missing", 1, queued.size)
        val ackedBefore = g.settings.ackedTotal

        UploadScheduler.kick(ctx) // explicit test trigger for the production worker; not evidence of periodic timing

        val deadline = System.currentTimeMillis() + 120_000
        var recovered = false
        while (System.currentTimeMillis() < deadline) {
            val now = g.vault.state.read() as? StoredCredential.Present
            val promoted = now != null && now.state.pending == null && now.state.active.deviceToken == pending!!.replacementToken &&
                now.state.active.deviceId == active.deviceId
            if (promoted && g.outbox.count(active.deviceId) == 0 && g.settings.ackedTotal >= ackedBefore + 1) { recovered = true; break }
            delay(500)
        }
        assertTrue("recovery_timeout", recovered)
        assertTrue("not_renewed_by_worker", g.settings.lastCredentialResult.startsWith("renewed"))
        status("DANIM_RENEWAL_RECOVERED event=${queued.single()} acked=${g.settings.ackedTotal - ackedBefore} queued=${g.outbox.count(active.deviceId)}")
    }

    /** Host shortens only this synthetic device's expiry; the server's renewalDue decides, not the emulator clock. */
    private suspend fun awaitRenewalDue(g: Graph, c: DeviceCredential) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            when (val m = g.http.credentials.metadata(c.deviceToken, c.deviceId)) {
                is MetadataReply.Ok -> {
                    assertTrue("metadata_identity_mismatch", m.metadata.deviceId == c.deviceId && m.metadata.vehicleId == c.vehicleId)
                    if (m.metadata.renewalDue) return
                }
                MetadataReply.Unauthorized -> throw AssertionError("metadata_unauthorized")
                else -> Unit
            }
            delay(2_500) // 24 requests per minute at most
        }
        throw AssertionError("renewal_due_timeout")
    }

    private fun totalQueued(): Long = ctx.graph.db.query("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getLong(0) }

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private companion object {
        const val LOCAL_SITE = "http://10.0.2.2:3004"
        const val LOCAL_API = "http://10.0.2.2:59901/functions/v1/api"
    }
}
