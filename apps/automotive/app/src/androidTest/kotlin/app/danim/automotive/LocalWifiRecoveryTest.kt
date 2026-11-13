package app.danim.automotive

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Opt-in physical Wi-Fi recovery test against a disposable local HTTPS fixture. After READY,
 * the host disables device Wi-Fi and mobile fallback while keeping the LAN relay healthy.
 * After RETAINED and host-side Room/server checks, the host only enables Wi-Fi again.
 *
 * The Activity remains destroyed throughout the outage and recovery. This test only observes
 * the existing collector, Room, and Android network state: it never inserts telemetry, requests
 * an upload, restarts collection, relaunches the Activity, or reboots after READY. WorkManager
 * may wait for its connectivity constraint, so an upload exception is not required.
 */
@RunWith(AndroidJUnit4::class)
class LocalWifiRecoveryTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private var testDeadline = Long.MAX_VALUE

    @Test
    fun retainedEventsAutomaticallyUploadWhenWifiReturns() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in local Wi-Fi test", args.getString("danimWifi") == "true")
        assertTrue("debug builds only", BuildConfig.DEBUG)
        assertEquals("separate disposable test package required", LOCAL_PACKAGE, ctx.packageName)
        val wifiHost = args.getString("wifiHost")
        assertTrue("a private LAN IPv4 host is required", isPrivateLanIpv4(wifiHost))
        val localSite = "https://$wifiHost:54394"
        val localApi = "$localSite/functions/v1/api"
        assertTrue(
            "compiled defaults must match the guarded LAN configuration",
            BuildConfig.DEFAULT_API_BASE_URL == localApi && BuildConfig.DEFAULT_SITE_URL == localSite,
        )

        val g = ctx.graph
        assertTrue("effective API must match guarded compiled default", g.settings.apiBaseUrl == localApi)
        assertTrue("effective site must match guarded compiled default", g.settings.siteUrl == localSite)
        assertNull("never replace an existing credential", g.vault.loadCredential())
        assertNull("never replace pending pairing", g.vault.loadPending())
        assertEquals(0L, g.settings.expiredTotal)
        assertEquals(0L, g.settings.quarantinedTotal)
        val ackedBeforePairing = g.settings.ackedTotal
        testDeadline = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS
        var startedCollection = false

        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = inst.startActivitySync(intent) as MainActivity
            lateinit var vm: MainViewModel
            inst.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }
            inst.runOnMainSync {
                vm.onConfig(localApi, localSite)
                // Synthetic test setup on a stationary phone; telemetry still comes from the real sources.
                vm.onParked(true)
                vm.onConsent(true)
                vm.startPairing()
            }
            await(60_000, "local pairing response", g) {
                vm.ui.value.pairing != null || vm.ui.value.message != null
            }
            assertTrue("pairing must succeed", vm.ui.value.message == null)
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
            val initialSession = runBlocking { g.db.sessions().latest() }
            assertNotNull("running collection session required", initialSession)
            val sessionId = initialSession!!.id
            assertNull("session must still be open", initialSession.endedAtMs)
            assertTrue("main queue accepted Activity closure", Handler(Looper.getMainLooper()).post { activity.finish() })
            await(10_000, "Activity closed before outage", g) { activity.isDestroyed }
            fun assertStableCollection() = assertStable(g, activity, pid, activeJob, sessionId)
            await(30_000, "outbox empty after Activity closure", g) {
                assertStableCollection()
                queued() == 0
            }
            assertTrue("active Wi-Fi required before outage", wifiActive())
            assertStableCollection()
            val initialAcked = g.settings.ackedTotal
            status("DANIM_WIFI_READY queued=${queued()} session=$sessionId ${diagnostics(g)}")

            // The host now disables Wi-Fi and mobile fallback, without changing the healthy relay.
            await(180_000, "no active network after Wi-Fi disable", g) {
                assertStableCollection()
                connectivity.activeNetwork == null
            }
            assertEquals("outage cannot count as an ACK", initialAcked, g.settings.ackedTotal)
            status("DANIM_WIFI_OFFLINE queued=${queued()} session=$sessionId ${diagnostics(g)}")
            await(420_000, "natural observations retained while offline", g) {
                assertStableCollection()
                assertNull("network must stay absent until retained evidence", connectivity.activeNetwork)
                assertEquals("offline observations cannot count as ACKs", initialAcked, g.settings.ackedTotal)
                queued() > 0
            }
            val retained = runBlocking { g.outbox.nextBatch(200, credential.deviceId) }
            assertTrue("offline collection must retain event UUIDs", retained.isNotEmpty())
            val retainedIds = retained.map { it.observation.eventId }
            assertTrue("event UUIDs only", retainedIds.all { it.matches(EVENT_UUID) })
            status("DANIM_WIFI_RETAINED count=${retainedIds.size} queued=${queued()} " +
                "ids=${retainedIds.joinToString(",")} session=$sessionId ${diagnostics(g)}")

            // The host verifies these UUIDs are absent on the server, then only enables Wi-Fi.
            await(360_000, "active Wi-Fi restored", g) {
                assertStableCollection()
                wifiActive()
            }
            status("DANIM_WIFI_CONNECTED queued=${queued()} session=$sessionId ${diagnostics(g)}")
            await(360_000, "automatic ACK after Wi-Fi restoration", g) {
                assertStableCollection()
                assertTrue("recovery must continue over active Wi-Fi", wifiActive())
                queued() == 0 && g.settings.ackedTotal >= initialAcked + retainedIds.size
            }
            assertStableCollection()
            assertTrue("Wi-Fi remains active after recovery", wifiActive())
            assertEquals(0L, g.settings.expiredTotal)
            assertEquals(0L, g.settings.quarantinedTotal)
            assertFalse("credential must not require re-pairing", g.settings.pausedReauth)
            assertTrue("API must remain unchanged", g.settings.apiBaseUrl == localApi)
            assertTrue("site must remain unchanged", g.settings.siteUrl == localSite)
            status("DANIM_WIFI_RECOVERED retained=${retainedIds.size} queued=${queued()} " +
                "automatic=true activityClosed=${activity.isDestroyed} session=$sessionId ${diagnostics(g)}")
            // Keep the recovered process observable until the host reads its identity.
            Thread.sleep(5_000)
            assertStableCollection()
        } catch (error: Throwable) {
            // Never report exception messages, URLs, response bodies or credential objects.
            status("DANIM_WIFI_FAILED type=${error.javaClass.simpleName} ${diagnostics(g)}")
            throw error
        } finally {
            if (startedCollection || g.settings.collectionWanted) {
                // Cleanup is permitted only after evidence capture or a bounded failure.
                Collector.stop(ctx)
                awaitCleanup(30_000, g)
                assertFalse(g.settings.collectionWanted)
                status("DANIM_WIFI_COMPLETE ${diagnostics(g)}")
            }
        }
    }

    private fun assertStable(g: Graph, activity: MainActivity, pid: Int, collectorJob: Job, sessionId: Long) {
        assertEquals("same process throughout outage and recovery", pid, Process.myPid())
        assertSame("same collector job throughout outage and recovery", collectorJob, g.collectorJob)
        assertTrue("collector job remains active", collectorJob.isActive)
        assertEquals("collector remains running", "running", g.collectorState.value)
        assertTrue("saved user intent remains active", g.settings.collectionWanted)
        assertFalse("credential remains usable", g.settings.pausedReauth)
        assertTrue("Activity remains closed throughout outage and recovery", activity.isDestroyed)
        val session = runBlocking { g.db.sessions().latest() }
        assertNotNull("collection session remains present", session)
        assertEquals("same collection session throughout outage and recovery", sessionId, session!!.id)
        assertNull("collection session stays open", session.endedAtMs)
    }

    private fun wifiActive(): Boolean = connectivity.activeNetwork?.let { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: false

    private fun await(timeoutMs: Long, description: String, g: Graph, ready: () -> Boolean) {
        val deadline = minOf(SystemClock.elapsedRealtime() + timeoutMs, testDeadline)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return
            Thread.sleep(250)
        }
        status("DANIM_WIFI_TIMEOUT stage=$description ${diagnostics(g)}")
        throw AssertionError("Timed out waiting for $description")
    }

    private fun awaitCleanup(timeoutMs: Long, g: Graph) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (g.collectorState.value == "stopped" && g.collectorJob == null) return
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for explicit cleanup Stop")
    }

    private fun diagnostics(g: Graph): String =
        "pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()} wifi=${wifiActive()} " +
            "activeNetwork=${connectivity.activeNetwork != null} collector=${g.collectorState.value} " +
            "wanted=${g.settings.collectionWanted} acked=${g.settings.ackedTotal} " +
            "expired=${g.settings.expiredTotal} quarantined=${g.settings.quarantinedTotal}"

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private fun isPrivateLanIpv4(host: String?): Boolean {
        if (host == null || !host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return false
        val parts = host.split('.')
        if (parts.any { (it.length > 1 && it.startsWith('0')) || it.toInt() !in 0..255 }) return false
        val octets = parts.map(String::toInt)
        if (octets[0] == 10 && octets[1] == 0 && octets[2] in 2..3) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private companion object {
        const val LOCAL_PACKAGE = "app.danim.automotive.wifitest"
        const val TOTAL_TIMEOUT_MS = 20 * 60_000L
        val EVENT_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
