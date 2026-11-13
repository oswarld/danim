package app.danim.automotive

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.collector.Collector
import app.danim.automotive.ui.MainActivity
import app.danim.automotive.ui.MainViewModel
import app.danim.automotive.ui.Phase
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in local emulator/phone test. A host runner claims the pairing code, closes only the
 * loopback relay listener after READY, and restores that listener after RETAINED. The official
 * vehicle console supplies real emulator signal changes, or the phone reports its actual
 * availability states on the normal heartbeat; this test never inserts telemetry.
 *
 * After READY, the Activity stays closed and this test only reads state until RECOVERED. It
 * never kicks upload work, restarts collection, relaunches the Activity, or reboots the device.
 * Credentials remain in the normal Keystore-backed vault and never enter test output.
 */
@RunWith(AndroidJUnit4::class)
class LocalConnectivityRecoveryTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test
    fun retainedEventsAutomaticallyUploadWhenConnectionReturns() {
        assumeTrue(
            "opt-in local connectivity test",
            InstrumentationRegistry.getArguments().getString("danimConnectivity") == "true",
        )
        assertTrue("debug builds only", BuildConfig.DEBUG)
        assertEquals("separate disposable test package required", LOCAL_PACKAGE, ctx.packageName)
        val localApi = BuildConfig.DEFAULT_API_BASE_URL
        val localSite = BuildConfig.DEFAULT_SITE_URL
        assertTrue(
            "compiled defaults must match one complete fixed local configuration",
            (localApi == EMULATOR_API && localSite == EMULATOR_SITE) ||
                (localApi == PHONE_API && localSite == PHONE_SITE),
        )

        val g = ctx.graph
        assertEquals("effective API must match guarded compiled default", localApi, g.settings.apiBaseUrl)
        assertEquals("effective site must match guarded compiled default", localSite, g.settings.siteUrl)
        assertNull("never replace an existing credential", g.vault.loadCredential())
        assertNull("never replace pending pairing", g.vault.loadPending())
        assertEquals(0L, g.settings.expiredTotal)
        assertEquals(0L, g.settings.quarantinedTotal)
        val ackedBeforePairing = g.settings.ackedTotal
        var startedCollection = false

        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = inst.startActivitySync(intent) as MainActivity
            lateinit var vm: MainViewModel
            inst.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }
            inst.runOnMainSync {
                vm.onConfig(localApi, localSite)
                // Explicit local test consent; the host uses a stationary emulator or physical phone.
                vm.onParked(true)
                vm.onConsent(true)
                vm.startPairing()
            }
            await(60_000, "local pairing response", g) {
                vm.ui.value.pairing != null || vm.ui.value.message != null
            }
            assertNull("pairing must succeed", vm.ui.value.message)
            val code = vm.ui.value.pairing!!.code
            assertTrue("only an eight-character pairing code may be reported", code.matches(Regex("[A-Z0-9]{8}")))
            status("DANIM_PAIRING_CODE=$code")
            await(180_000, "host claim and credential delivery", g) { vm.ui.value.phase == Phase.PAIRED }
            startedCollection = true
            await(60_000, "collector running", g) { g.collectorState.value == "running" }
            val credential = g.vault.loadCredential()!!
            fun queued() = runBlocking { g.outbox.count(credential.deviceId) }
            await(120_000, "initial ACK and empty outbox", g) {
                g.settings.ackedTotal > ackedBeforePairing && queued() == 0
            }

            val pid = Process.myPid()
            val collectorJob = g.collectorJob
            assertNotNull("running collector job required", collectorJob)
            val activeJob = collectorJob!!
            // A stalled framework callback must fail this bounded wait, not hang runOnMainSync indefinitely.
            assertTrue("main queue accepted Activity closure", Handler(Looper.getMainLooper()).post { activity.finish() })
            await(10_000, "Activity closed before outage", g) { activity.isDestroyed }
            await(30_000, "outbox empty after Activity closure", g) { queued() == 0 }
            assertStable(g, pid, activeJob)
            val initialAcked = g.settings.ackedTotal
            status("DANIM_RECOVERY_READY queued=${queued()} ${diagnostics(g)}")

            // The host closes the relay socket only after READY. A refused/reset connection must
            // preserve actual collector events before the host is permitted to restore the listener.
            await(360_000, "real observations retained after transport exception", g) {
                assertStable(g, pid, activeJob)
                queued() > 0 && TRANSPORT_RETRY.matches(g.settings.lastUploadResult)
            }
            val retained = runBlocking { g.outbox.nextBatch(200, credential.deviceId) }
            assertTrue("failed upload must retain event UUIDs", retained.isNotEmpty())
            assertEquals("outage cannot count as an ACK", initialAcked, g.settings.ackedTotal)
            assertTrue("retained rows must record an attempted upload", retained.any { it.attempts > 0 })
            val retainedIds = retained.map { it.observation.eventId }
            assertTrue("event UUIDs only", retainedIds.all { it.matches(EVENT_UUID) })
            status("DANIM_RECOVERY_RETAINED count=${retainedIds.size} queued=${queued()} " +
                "ids=${retainedIds.joinToString(",")} ${diagnostics(g)}")

            // Only the host listener is restored now. WorkManager and the existing collector must
            // schedule the retry themselves; no manual retry or lifecycle action occurs here.
            await(360_000, "automatic ACK after transport restoration", g) {
                assertStable(g, pid, activeJob)
                queued() == 0 && g.settings.ackedTotal >= initialAcked + retainedIds.size
            }
            assertStable(g, pid, activeJob)
            assertEquals(0L, g.settings.expiredTotal)
            assertEquals(0L, g.settings.quarantinedTotal)
            assertFalse("credential must not require re-pairing", g.settings.pausedReauth)
            assertEquals(localApi, g.settings.apiBaseUrl)
            assertEquals(localSite, g.settings.siteUrl)
            status("DANIM_RECOVERY_RECOVERED retained=${retainedIds.size} queued=${queued()} " +
                "automatic=true activityClosed=${activity.isDestroyed} ${diagnostics(g)}")
        } catch (error: Throwable) {
            // Do not include exception messages, URLs, response bodies or credential objects.
            status("DANIM_RECOVERY_FAILED type=${error.javaClass.simpleName} ${diagnostics(g)}")
            throw error
        } finally {
            if (startedCollection || g.settings.collectionWanted) {
                // Cleanup occurs only after the recovery assertions, or after a bounded failure.
                Collector.stop(ctx)
                await(30_000, "explicit cleanup Stop", g) {
                    g.collectorState.value == "stopped" && g.collectorJob == null
                }
                assertFalse(g.settings.collectionWanted)
                status("DANIM_RECOVERY_COMPLETE ${diagnostics(g)}")
            }
        }
    }

    private fun assertStable(g: Graph, pid: Int, collectorJob: Job) {
        assertEquals("same process throughout outage and recovery", pid, Process.myPid())
        assertSame("same collector job throughout outage and recovery", collectorJob, g.collectorJob)
        assertTrue("collector job remains active", collectorJob.isActive)
        assertEquals("collector remains running", "running", g.collectorState.value)
        assertTrue("saved user intent remains active", g.settings.collectionWanted)
    }

    private fun await(timeoutMs: Long, description: String, g: Graph, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return
            Thread.sleep(250)
        }
        status("DANIM_RECOVERY_TIMEOUT stage=$description ${diagnostics(g)}")
        throw AssertionError("Timed out waiting for $description")
    }

    private fun diagnostics(g: Graph): String =
        "pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()} " +
            "collector=${g.collectorState.value} wanted=${g.settings.collectionWanted} " +
            "acked=${g.settings.ackedTotal} expired=${g.settings.expiredTotal} " +
            "quarantined=${g.settings.quarantinedTotal} " +
            "transportRetry=${TRANSPORT_RETRY.matches(g.settings.lastUploadResult)}"

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private companion object {
        const val LOCAL_PACKAGE = "app.danim.automotive.recoverytest"
        const val EMULATOR_API = "http://10.0.2.2:54392/functions/v1/api"
        const val EMULATOR_SITE = "http://10.0.2.2:3000"
        const val PHONE_API = "http://127.0.0.1:54392/functions/v1/api"
        const val PHONE_SITE = "http://127.0.0.1:54392"
        val TRANSPORT_RETRY = Regex("^retry\\((?:ConnectException|SocketException|SocketTimeoutException|EOFException|IOException|UnknownHostException)\\) attempt=[0-9]+$")
        val EVENT_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
