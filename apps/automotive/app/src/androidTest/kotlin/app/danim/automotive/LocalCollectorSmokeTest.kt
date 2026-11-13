package app.danim.automotive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.ui.MainActivity
import app.danim.automotive.ui.MainViewModel
import app.danim.automotive.ui.Phase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OPT-IN functional smoke test against a LOCAL stack and a (Pleos) emulator. It is skipped (Assume, no network, no
 * side effects) unless every guard holds:
 *  - instrumentation argument `danimRuntime=true`
 *  - a debug build whose default URLs are exactly the local emulator ones below
 *  - no credential and no pending pairing already stored (an existing paired device is never touched)
 *
 * It drives the real MainActivity/MainViewModel the way a user would (synthetic local test setup: parked + consent),
 * reports ONLY the 8-character pairing code through `Instrumentation.sendStatus` so an operator can claim it in an
 * authenticated local browser, then waits for the real collector to run and for a real local upload to be
 * acknowledged. It never prints the pairing secret or the device token and it does NOT disconnect or delete the
 * credential, so the paired state can be exercised afterwards (reopen/background/boot) with adb.
 *
 * Run (operator): `adb -s emulator-5554 shell am instrument -w -e danimRuntime true -e class
 * app.danim.automotive.LocalCollectorSmokeTest app.danim.automotive.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class LocalCollectorSmokeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext

    @Test
    fun pairClaimCollectAndAcknowledgeAgainstLocalStack() {
        // ---- refusal guards (all Assume: skipped, never failed, never networked) ----
        assumeTrue("opt-in: pass -e danimRuntime true", InstrumentationRegistry.getArguments().getString("danimRuntime") == "true")
        assumeTrue("debug build only", BuildConfig.DEBUG)
        assumeTrue("build must default to the local emulator API", BuildConfig.DEFAULT_API_BASE_URL == LOCAL_API)
        assumeTrue("build must default to the local emulator site", BuildConfig.DEFAULT_SITE_URL == LOCAL_SITE)
        val g = ctx.graph
        assumeTrue("an existing credential is never touched", g.vault.loadCredential() == null)
        assumeTrue("a pending pairing is never touched", g.vault.loadPending() == null)
        assumeNotNull(g.settings.apiConfig())

        // ---- launch the real Activity and get its ViewModel ----
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = inst.startActivitySync(intent) as MainActivity
        lateinit var vm: MainViewModel
        inst.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }

        // ---- synthetic local test setup, then the real pairing call ----
        inst.runOnMainSync {
            vm.onConfig(LOCAL_API, LOCAL_SITE)
            vm.onParked(true)
            vm.onConsent(true)
            vm.startPairing()
        }

        val code = awaitNotNull(60_000, "pairing code") { vm.ui.value.pairing?.code }
        assertTrue("pairing code must contain exactly eight uppercase letters/digits", code.matches(Regex("[A-Z0-9]{8}")))
        status("DANIM_PAIRING_CODE=$code", "danim_pairing_code" to code) // code only: never secret or token

        awaitUntil(180_000, "claim -> PAIRED") { vm.ui.value.phase == Phase.PAIRED }
        awaitUntil(60_000, "CollectorService running") { g.collectorState.value == "running" }
        awaitUntil(120_000, "first upload ACKed (ackedTotal > 0)") { g.settings.ackedTotal > 0 }

        val acked = g.settings.ackedTotal
        assertTrue("ackedTotal must be positive after a real local upload", acked > 0)
        status(diagnostics(g))
        // Deliberately no disconnect and no credential/outbox cleanup: the paired state is left for lifecycle checks.
    }

    /**
     * SECOND opt-in method (`-e danimLifecycle true`): an explicit Stop must survive an Activity reopen, i.e.
     * `MainActivity.onStart -> resumeIfWanted()` must respect `collectionWanted = false`. Needs an already paired LOCAL
     * debug install (credential present; never printed, deleted or modified). It intentionally leaves
     * `collectionWanted = false` and the collector stopped.
     */
    @Test
    fun explicitStopSurvivesActivityReopen() {
        assumeTrue("opt-in: pass -e danimLifecycle true", InstrumentationRegistry.getArguments().getString("danimLifecycle") == "true")
        assumeTrue("debug build only", BuildConfig.DEBUG)
        assumeTrue("build must default to the local emulator API", BuildConfig.DEFAULT_API_BASE_URL == LOCAL_API)
        assumeTrue("build must default to the local emulator site", BuildConfig.DEFAULT_SITE_URL == LOCAL_SITE)
        val g = ctx.graph
        assumeNotNull("requires an existing local paired credential", g.vault.loadCredential())
        assumeTrue("credential must not be paused for re-pairing", !g.settings.pausedReauth)
        assumeTrue("consent must have been recorded", g.settings.consentAtMs > 0)

        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val first = inst.startActivitySync(intent) as MainActivity
        lateinit var vm: MainViewModel
        inst.runOnMainSync { vm = ViewModelProvider(first)[MainViewModel::class.java] }

        // Ensure the collector is really running first (onStart may already have resumed it if it was wanted).
        if (!waitFor(10_000) { g.collectorState.value == "running" }) {
            inst.runOnMainSync { vm.startCollection() }
        }
        awaitUntil(60_000, "CollectorService running before Stop") { g.collectorState.value == "running" && g.settings.collectionWanted }

        // Explicit Stop, as the user's Stop button does.
        inst.runOnMainSync { vm.stopCollection() }
        awaitUntil(30_000, "collector stopped") { g.collectorState.value == "stopped" }
        awaitUntil(5_000, "collectionWanted cleared") { !g.settings.collectionWanted }

        // Leave the Activity and reopen it.
        inst.runOnMainSync { first.finish() }
        inst.waitForIdleSync()
        Thread.sleep(1_000)
        inst.startActivitySync(intent) as MainActivity
        Thread.sleep(3_000) // time for onStart/resumeIfWanted and any (wrongly) started service to show up

        status("DANIM_LIFECYCLE_STOP_REOPEN collector=${g.collectorState.value} wanted=${g.settings.collectionWanted}")
        assertEquals("collector must stay stopped after reopen", "stopped", g.collectorState.value)
        assertTrue("explicit Stop must stay in force (collectionWanted=false)", !g.settings.collectionWanted)
        assertNotNull("credential must be left intact", g.vault.loadCredential())
        // Deliberately leaves collectionWanted=false so a later lifecycle check starts from an explicit Stop.
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun diagnostics(g: Graph): String {
        val queued = g.vault.loadCredential()?.let { runBlocking { g.outbox.count(it.deviceId) } }
        val signals = g.signalBoard.value.entries.sortedBy { it.key.key }.joinToString(";") { (s, i) ->
            "${s.key}=${i.availability.wire}:${i.value ?: "null"}"
        }
        return "DANIM_DIAG collector=${g.collectorState.value} sdk=${g.sdkState.value} acked=${g.settings.ackedTotal} " +
            "quarantined=${g.settings.quarantinedTotal} expired=${g.settings.expiredTotal} queued=$queued " +
            "lastUpload=${g.settings.lastUploadResult} signals=[$signals]"
    }

    private fun status(line: String, vararg extra: Pair<String, String>) {
        val b = Bundle().apply {
            putString("stream", "$line\n")
            extra.forEach { (k, v) -> putString(k, v) }
        }
        inst.sendStatus(IN_PROGRESS, b)
    }

    private fun awaitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(500)
        }
        status(diagnostics(ctx.graph))
        throw AssertionError("timed out after ${timeoutMs / 1000}s waiting for: $what")
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun <T : Any> awaitNotNull(timeoutMs: Long, what: String, get: () -> T?): T {
        var v: T? = null
        awaitUntil(timeoutMs, what) { v = get(); v != null }
        return v!!
    }

    private companion object {
        const val LOCAL_API = "http://10.0.2.2:54381/functions/v1/api"
        const val LOCAL_SITE = "http://10.0.2.2:3000"
        const val IN_PROGRESS = 2 // Instrumentation status code: in progress
    }
}
