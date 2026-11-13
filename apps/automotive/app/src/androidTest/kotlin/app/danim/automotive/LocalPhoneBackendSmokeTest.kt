package app.danim.automotive

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.ui.MainActivity
import app.danim.automotive.ui.MainViewModel
import app.danim.automotive.ui.Phase
import app.danim.automotive.work.UploadScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in physical-phone test. Requires a separately installed local-only APK and USB reverse tunnel.
 * The host runner claims the displayed code using a disposable LOCAL user. Credentials stay in the
 * Keystore-backed vault and never enter instrumentation output. No synthetic vehicle values are injected.
 */
@RunWith(AndroidJUnit4::class)
class LocalPhoneBackendSmokeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test
    fun pairUploadAndRecoverAgainstLoopbackOnly() {
        assumeTrue(
            "opt-in local physical-phone test",
            InstrumentationRegistry.getArguments().getString("danimPhoneLocal") == "true",
        )
        assertTrue("debug only", BuildConfig.DEBUG)
        assertEquals(LOCAL_PACKAGE, ctx.packageName)
        assertEquals(LOCAL_API, BuildConfig.DEFAULT_API_BASE_URL)
        assertEquals(LOCAL_SITE, BuildConfig.DEFAULT_SITE_URL)

        val g = ctx.graph
        assertEquals(LOCAL_API, g.settings.apiBaseUrl)
        assertEquals(LOCAL_SITE, g.settings.siteUrl)
        assertNull("never replace an existing credential", g.vault.loadCredential())
        assertNull("never replace a pending pairing", g.vault.loadPending())

        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = inst.startActivitySync(intent) as MainActivity
        lateinit var vm: MainViewModel
        inst.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }
        inst.runOnMainSync {
            vm.onConfig(LOCAL_API, LOCAL_SITE)
            // Explicit synthetic local consent flow; this is a phone, not a moving vehicle.
            vm.onParked(true)
            vm.onConsent(true)
            vm.startPairing()
        }
        await(60_000, "pairing response") { vm.ui.value.pairing != null || vm.ui.value.message != null }
        assertNull("local pairing response: ${vm.ui.value.message}", vm.ui.value.message)
        status("DANIM_PAIRING_CODE=${vm.ui.value.pairing!!.code}")
        await(120_000, "local claim and credential delivery") { vm.ui.value.phase == Phase.PAIRED }
        await(60_000, "collector running") { g.collectorState.value == "running" }
        val credential = g.vault.loadCredential()!!
        fun queued() = runBlocking { g.outbox.count(credential.deviceId) }
        await(120_000, "first real server ACK") { g.settings.ackedTotal > 0 && queued() == 0 }
        status("DANIM_LOCAL_INITIAL_ACK acked=${g.settings.ackedTotal} queued=${queued()}")

        // Only this phone's loopback relay becomes unavailable; the shared backend remains running.
        control("offline")
        val beforeOutage = g.settings.ackedTotal
        inst.runOnMainSync { vm.stopCollection() }
        await(30_000, "collector fully stopped") { g.collectorState.value == "stopped" && g.collectorJob == null }
        inst.runOnMainSync { vm.startCollection() }
        await(60_000, "new observations persisted offline") { queued() > 0 }
        await(90_000, "HTTP 503 retained for retry") { g.settings.lastUploadResult.contains("503") }
        val retained = runBlocking { g.outbox.nextBatch(200, credential.deviceId) }
        assertTrue("real collector statuses must be retained", retained.isNotEmpty())
        assertEquals("failed requests cannot count as ACKs", beforeOutage, g.settings.ackedTotal)
        status("DANIM_LOCAL_RETAINED acked=${g.settings.ackedTotal} queued=${queued()} ids=${retained.joinToString(",") { it.observation.eventId }}")

        control("online")
        UploadScheduler.kick(ctx)
        await(180_000, "retained events ACKed after server recovery") {
            queued() == 0 && g.settings.ackedTotal >= beforeOutage + retained.size
        }
        assertEquals(0L, g.settings.quarantinedTotal)
        assertEquals(0L, g.settings.expiredTotal)
        status("DANIM_LOCAL_RECOVERED acked=${g.settings.ackedTotal} queued=${queued()}")

        inst.runOnMainSync { vm.stopCollection() }
        await(30_000, "explicit Stop") { g.collectorState.value == "stopped" && g.collectorJob == null }
        assertFalse(g.settings.collectionWanted)
        assertEquals(LOCAL_API, g.settings.apiBaseUrl)
        assertEquals(LOCAL_SITE, g.settings.siteUrl)
        status("DANIM_LOCAL_COMPLETE collector=${g.collectorState.value} wanted=${g.settings.collectionWanted} acked=${g.settings.ackedTotal} queued=${queued()} device=${credential.deviceId} vehicle=${credential.vehicleId}")
    }

    private fun control(mode: String) {
        val connection = URL("$LOCAL_SITE/__test__/$mode").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            assertEquals("local test relay control", 200, connection.responseCode)
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun await(timeoutMs: Long, description: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ready()) return
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private companion object {
        const val LOCAL_PACKAGE = "app.danim.automotive.localtest"
        const val LOCAL_SITE = "http://127.0.0.1:54391"
        const val LOCAL_API = "$LOCAL_SITE/functions/v1/api"
    }
}
