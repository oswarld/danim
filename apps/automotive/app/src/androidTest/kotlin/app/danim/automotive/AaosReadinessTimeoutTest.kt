package app.danim.automotive

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.danim.automotive.collector.AaosPropertySource
import app.danim.core.Availability
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.Value
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in readiness-timeout scenarios for the real [AaosPropertySource] on a real AAOS Car service (approved Pleos API 34
 * AVD). Skipped unless the host passes `-e danimAaosReadiness true`. The test bodies never pair, never read the
 * credential vault or settings, never call the app graph and never contact a backend; readings go to an in-memory
 * list. The Android runner still starts the app process normally, so `DanimApp.onCreate` constructs `Graph`
 * (settings, vault and Room wrappers) before any test runs; isolation from real credentials and backends is the
 * operator's fresh owned AVD with no pairing and localhost-only public build settings.
 *
 * Controlled delay (injected, not a provider failure): `Car.createCar` called off the main thread posts its
 * `onLifecycleChanged(car, true)` callback to the main Looper (AOSP android14 `Car.dispatchCarReadyToMainThread`; the
 * not-yet-running path also completes on main via its ServiceConnection). The tests park the app main Looper in gates,
 * so the real ready callback is withheld while the adapter executor, the real Car and the Android monotonic clock keep
 * running. The tests check that the callback really was withheld instead of assuming it. That `Car.disconnect()` leaves
 * the abandoned Car's posted callback queued, so it runs when its gate opens, is an inference from AOSP source and
 * main-Looper FIFO order; it is not separately observed here.
 *
 * Wall time is frozen at [FROZEN_WALL_MS]: only `SystemClock.elapsedRealtime()` can expire a wait.
 *
 * Reflection is read-only observation plus an ordering barrier: it reads the adapter's private `car` (to query the
 * platform's public `Car.isConnected()`) and `lifecycleCallback` (to invoke it, see [injectedNotReadyRestartsBoundedWait]),
 * and queues a no-op on the adapter's private executor so earlier posted work has run. It never writes adapter fields.
 *
 * Evidence class: instrumented process, real AAOS Car on an emulator, deliberately delayed main Looper or deliberately
 * injected lifecycle callback. Not real-car evidence and not evidence that any runtime withholds or revokes readiness
 * on its own. Status markers: `DANIM_AAOS_BASELINE`, `DANIM_AAOS_HELD_*` (delayed real callbacks),
 * `DANIM_AAOS_INJECTED_*` (injected `ready=false`), kept separate in evidence.
 *
 * Run (operator, disposable AVD): `adb -s emulator-5554 shell am instrument -w -e danimAaosReadiness true -e class
 * app.danim.automotive.AaosReadinessTimeoutTest app.danim.automotive.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class AaosReadinessTimeoutTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext
    private val readings = CopyOnWriteArrayList<Reading>()
    private val gates = mutableListOf<MainGate>()
    private var source: AaosPropertySource? = null

    private data class Reading(val signal: Signal, val availability: Availability, val value: Value?, val atMs: Long, val source: Source)

    @Before
    fun guard() {
        assumeTrue("opt-in AAOS readiness test", InstrumentationRegistry.getArguments().getString("danimAaosReadiness") == "true")
        assertTrue("car_property_api_required", Build.VERSION.SDK_INT >= 29)
        assertNotNull("aaos_car_library_required", runCatching { carClass() }.getOrNull())
    }

    /** Never leaves main parked; a source a test did not stop itself must drain, or the test fails. */
    @After
    fun cleanup() {
        gates.forEach { it.release() }
        val s = source ?: return
        source = null
        val drain = runBlocking { s.stopAndAwait(5_000) }
        status("DANIM_AAOS_CLEANUP drained=${drain.drained} discarded=${drain.discardedTasks}")
        if (!drain.drained) reportThreads("cleanup_not_drained")
        assertTrue("cleanup_not_drained", drain.drained)
    }

    /** Control: without any delay the real Car becomes ready and the adapter connects. */
    @Test
    fun realReadinessWithoutDelayConnects() {
        val s = newSource()
        s.start()
        assertTrue("baseline_not_connected", awaitConnected(s, 15_000))
        assertTrue("baseline_car_not_connected", isConnected(car(s)!!))
        status("DANIM_AAOS_BASELINE connected readings=${readings.size}")
    }

    /**
     * Detects: premature "connected" before the ready callback; a shortened or wall-clock deadline; expiry that does not
     * disconnect the Car, reports anything but unavailable/null/aaos_car_property, or recreates in the same pass; the
     * abandoned Car's late ready callback, arriving while a replacement Car exists, being accepted for or marking the
     * replacement ready; failure to recover once the replacement's own callback arrives; and a stop that is unbounded,
     * leaves the Car connected or emits afterwards.
     */
    @Test
    fun withheldReadinessTimesOutDisconnectsAndRecovers() {
        val s = newSource()
        val gateA = postGate().also { it.awaitParked() }
        val beforeStart = SystemClock.elapsedRealtime()
        s.start()
        barrier(s)
        val attemptStartedBy = SystemClock.elapsedRealtime() // the deadline started in (beforeStart, attemptStartedBy]
        val first = car(s)
        assertNotNull("first_car_missing", first)
        val connectedAtCreate = isConnected(first!!)
        status("DANIM_AAOS_HELD_ATTEMPT1 carConnectedAtCreate=$connectedAtCreate startMs=${attemptStartedBy - beforeStart}")
        // Without this, a missing expiry disconnect would pass because the Car was never connected.
        assertTrue("held_scenario_requires_car_connected_at_create", connectedAtCreate)

        // 1. Pending: never connected, nothing reported, and the real callback really is withheld (mechanism check).
        Thread.sleep(2_000)
        barrier(s)
        assertWaiting(s, "ready_callback_not_withheld_by_main_looper")
        assertTrue("pending_emitted_readings", readings.isEmpty())

        // Before the bound: a heartbeat must neither expire nor recreate (detects a deadline shorter than ~28 s).
        sleepUntil(beforeStart + 28_000)
        s.poll()
        barrier(s)
        assertWaiting(s, "expired_before_bound")
        assertTrue("same_car_before_bound", car(s) === first)
        assertTrue("early_heartbeat_emitted", readings.isEmpty())

        // 2./3. After the real 30 s bound: explicit unavailable, abandoned Car disconnected, no fresh Car in that pass.
        sleepUntil(attemptStartedBy + 30_000 + 500)
        s.poll()
        barrier(s)
        assertTrue("expiry_not_reported", s.readiness().contains("not ready within 30 s"))
        assertNull("recreated_in_expiry_pass", car(s))
        assertFalse("abandoned_car_still_connected", isConnected(first))
        assertExactlyUnavailable(readings, "expiry")
        val afterExpiry = readings.size

        // Two-phase gate. Main queue while A is still parked: [abandoned callback, B]. The replacement is created
        // now, so its own callback queues behind B: [abandoned callback, B, replacement callback].
        val gateB = postGate()
        s.poll()
        barrier(s)
        val second = car(s)
        assertNotNull("no_fresh_attempt", second)
        assertTrue("fresh_attempt_reused_car", second !== first)
        assertWaiting(s, "fresh_attempt_reported_connected")

        // 4. Run only what precedes B (the abandoned Car's late callback) while the replacement exists and waits.
        gateA.release()
        gateB.awaitParked()
        barrier(s)
        assertWaiting(s, "late_callback_marked_replacement_ready")
        assertTrue("late_callback_replaced_car", car(s) === second)
        assertFalse("late_callback_reconnected_abandoned_car", isConnected(first))
        assertEquals("late_callback_emitted", afterExpiry, readings.size)
        // A guard that let the old callback set readiness would connect the replacement on this heartbeat.
        s.poll()
        barrier(s)
        assertWaiting(s, "stale_ready_used_by_heartbeat")
        assertEquals("pending_replacement_emitted", afterExpiry, readings.size)
        status("DANIM_AAOS_HELD_LATE_CALLBACK window_passed replacementWaiting=true (delivery inferred from AOSP + FIFO)")

        // 5. Releasing B lets the replacement's own real ready callback connect it.
        gateB.release()
        assertTrue("recovery_not_observed", awaitConnected(s, 15_000))
        assertTrue("recovered_on_other_car", car(s) === second)
        assertTrue("replacement_not_connected", isConnected(second!!))
        assertFalse("abandoned_car_reconnected", isConnected(first))
        assertTrue("recovery_wrong_source", readings.all { it.source == Source.AAOS_CAR_PROPERTY })
        status("DANIM_AAOS_HELD_RECOVERED ${counts(readings.drop(afterExpiry))}")

        // 6. Bounded stop: drained, Car disconnected, no reconnect or emission afterwards.
        assertStopIsFinal(s, second)
    }

    /**
     * Injected, not an actual provider disconnect: after real readiness, the test invokes the adapter's own retained
     * lifecycle listener with `ready=false` on main, the way the platform delivers it. The real Car stays bound, so no
     * real ready callback follows. Detects: `ready=false` not restarting the bounded wait (never expires), a restarted
     * wait that expires early, non-explicit reports, stale property events still emitted, expiry that does not
     * disconnect or recreates in the same pass, and no recovery on a fresh real Car.
     */
    @Test
    fun injectedNotReadyRestartsBoundedWait() {
        val s = newSource()
        s.start()
        assertTrue("initial_readiness_not_connected", awaitConnected(s, 15_000))
        val first = car(s)!!
        assertTrue("initial_car_not_connected", isConnected(first))
        val listener = lifecycleCallback(s)
        assertNotNull("lifecycle_listener_missing", listener)
        // The cut is taken on the adapter executor, queued from the same main runnable just before the injected
        // callback, so every earlier main/executor callback precedes it and the transition is queued right behind it.
        val cut = AtomicInteger(-1)
        val executor = privateField(s, "executor") as ExecutorService

        val injectedAfter = SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            executor.execute { cut.set(readings.size) } // read-only observer
            listenerClass().getMethod("onLifecycleChanged", carClass(), Boolean::class.javaPrimitiveType).invoke(listener, first, false)
        }
        barrier(s)
        val beforeInjection = cut.get()
        assertTrue("transition_cut_not_observed", beforeInjection >= 0)
        val restartedBy = SystemClock.elapsedRealtime() // the restarted deadline began in (injectedAfter, restartedBy]
        status("DANIM_AAOS_INJECTED_NOT_READY restartMs=${restartedBy - injectedAfter}")
        assertEquals("not_ready_state", "AAOS car service disconnected; waiting for readiness", s.readiness())
        assertTrue("not_ready_dropped_car", car(s) === first)
        val notReady = readings.drop(beforeInjection)
        assertExactlyUnavailable(notReady, "not_ready")

        sleepUntil(injectedAfter + 28_000)
        s.poll()
        barrier(s)
        assertEquals("not_ready_expired_before_bound", "AAOS car service disconnected; waiting for readiness", s.readiness())
        assertTrue("not_ready_recreated_before_bound", car(s) === first)
        assertEquals("stale_property_events_emitted", beforeInjection + notReady.size, readings.size)

        sleepUntil(restartedBy + 30_000 + 500)
        s.poll()
        barrier(s)
        assertTrue("not_ready_wait_not_expired", s.readiness().contains("not ready within 30 s"))
        assertNull("recreated_in_expiry_pass", car(s))
        assertFalse("expired_car_still_connected", isConnected(first))
        val expiry = readings.drop(beforeInjection + notReady.size)
        assertExactlyUnavailable(expiry, "not_ready_expiry")
        val afterExpiry = readings.size

        s.poll()
        assertTrue("fresh_readiness_not_observed", awaitConnected(s, 15_000))
        val second = car(s)!!
        assertTrue("fresh_attempt_reused_car", second !== first)
        assertTrue("fresh_car_not_connected", isConnected(second))
        assertFalse("expired_car_reconnected", isConnected(first))
        status("DANIM_AAOS_INJECTED_RECOVERED ${counts(readings.drop(afterExpiry))}")
        assertStopIsFinal(s, second)
    }

    /** Detects: a stop that leaves the pending Car connected, or a late ready callback that reconnects/emits after stop. */
    @Test
    fun stopWhileReadinessPendingIsFinal() {
        val s = newSource()
        val gate = postGate().also { it.awaitParked() }
        s.start()
        barrier(s)
        val pending = car(s)
        assertNotNull("pending_car_missing", pending)
        assertWaiting(s, "ready_callback_not_withheld_by_main_looper")

        source = null
        val drain = runBlocking { s.stopAndAwait(5_000) }
        if (!drain.drained) reportThreads("stop_not_drained")
        assertTrue("stop_not_drained", drain.drained)
        assertEquals("stop_state", "AAOS stopped", s.readiness())
        assertFalse("stopped_car_still_connected", isConnected(pending!!))

        gate.release()
        awaitMainLooper()
        Thread.sleep(2_000)
        s.poll()
        s.start()
        Thread.sleep(1_000)
        assertEquals("late_callback_after_stop", "AAOS stopped", s.readiness())
        assertFalse("late_callback_reconnected", isConnected(pending))
        assertTrue("emitted_after_stop", readings.isEmpty())
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun newSource() = AaosPropertySource(app, { FROZEN_WALL_MS }) { signal, availability, value, at, src ->
        readings += Reading(signal, availability, value, at, src)
    }.also { source = it }

    private fun assertExactlyUnavailable(batch: List<Reading>, label: String) {
        assertEquals("${label}_signals", EXPECTED_SIGNALS, batch.map { it.signal }.toSet())
        assertEquals("${label}_reading_count", EXPECTED_SIGNALS.size, batch.size)
        batch.forEach {
            assertEquals("${label}_not_unavailable", Availability.UNAVAILABLE, it.availability)
            assertNull("${label}_invented_value", it.value)
            assertEquals("${label}_source", Source.AAOS_CAR_PROPERTY, it.source)
            assertEquals("${label}_wall_time", FROZEN_WALL_MS, it.atMs)
        }
    }

    private fun assertStopIsFinal(s: AaosPropertySource, connectedCar: Any) {
        source = null
        val started = SystemClock.elapsedRealtime()
        val drain = runBlocking { s.stopAndAwait(5_000) }
        if (!drain.drained) reportThreads("stop_not_drained")
        assertTrue("stop_not_drained", drain.drained)
        assertTrue("stop_unbounded", SystemClock.elapsedRealtime() - started < 6_000)
        assertEquals("stop_state", "AAOS stopped", s.readiness())
        assertFalse("stopped_car_still_connected", isConnected(connectedCar))
        val atStop = readings.size
        s.poll()
        s.start()
        Thread.sleep(3_000)
        awaitMainLooper()
        assertEquals("emitted_after_stop", atStop, readings.size)
        assertEquals("restarted_after_stop", "AAOS stopped", s.readiness())
        assertFalse("reconnected_after_stop", isConnected(connectedCar))
    }

    private fun assertWaiting(s: AaosPropertySource, message: String) =
        assertEquals(message, "AAOS waiting for car service readiness", s.readiness())

    private fun awaitConnected(s: AaosPropertySource, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            awaitMainLooper()
            barrier(s)
            if (s.readiness().startsWith("AAOS connected")) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun counts(batch: List<Reading>) =
        "readings=${batch.size} " + Availability.entries.joinToString(" ") { a -> "${a.name.lowercase()}=${batch.count { it.availability == a }}" }

    /** Ordering only: runs a no-op on the adapter's private executor after everything already posted to it. */
    private fun barrier(s: AaosPropertySource) {
        val executor = privateField(s, "executor") as ExecutorService
        try {
            executor.submit {}.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            reportThreads("barrier_timeout")
            throw e
        }
    }

    /** Read-only: the Car object the adapter currently owns, or null. */
    private fun car(s: AaosPropertySource): Any? = privateField(s, "car")

    /** Read-only: the adapter's own lifecycle listener for its current Car, or null. */
    private fun lifecycleCallback(s: AaosPropertySource): Any? = privateField(s, "lifecycleCallback")

    private fun privateField(s: AaosPropertySource, name: String): Any? =
        AaosPropertySource::class.java.getDeclaredField(name).apply { isAccessible = true }.get(s)

    private fun isConnected(car: Any): Boolean = carClass().getMethod("isConnected").invoke(car) as Boolean

    private fun carClass(): Class<*> = Class.forName("android.car.Car", false, AaosPropertySource::class.java.classLoader)

    private fun listenerClass(): Class<*> =
        Class.forName("android.car.Car\$CarServiceLifecycleListener", false, AaosPropertySource::class.java.classLoader)

    /** Queues a gate at the end of the main Looper's queue; it parks main when reached. */
    private fun postGate(): MainGate {
        val gate = MainGate().also { gates += it }
        assertTrue("main_gate_not_posted", Handler(Looper.getMainLooper()).post { gate.park() })
        return gate
    }

    /** Waits until main has run everything queued before now. */
    private fun awaitMainLooper() {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { done.countDown() }
        val ran = done.await(10, TimeUnit.SECONDS)
        if (!ran) reportThreads("main_looper_stuck")
        assertTrue("main_looper_stuck", ran)
    }

    private fun sleepUntil(elapsedMs: Long) {
        val wait = elapsedMs - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
    }

    private fun status(line: String) = inst.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })

    private class MainGate {
        private val parked = CountDownLatch(1)
        private val open = CountDownLatch(1)

        /** Bounded so a crashed test can never park main forever. */
        fun park() {
            parked.countDown()
            open.await(MAX_PARK_S, TimeUnit.SECONDS)
        }

        fun awaitParked() {
            val ok = parked.await(10, TimeUnit.SECONDS)
            if (!ok) reportThreads("main_looper_not_parked")
            assertTrue("main_looper_not_parked", ok)
        }

        fun release() = open.countDown()
    }

    private companion object {
        val DIAGNOSED_THREADS = setOf("main", "danim-aaos")
        const val MAX_FRAMES = 24

        /**
         * Diagnostics only, sent before the original failure is thrown: state and at most [MAX_FRAMES] frames
         * (class/method/file:line) of the threads named in [DIAGNOSED_THREADS]. No locals, heap values or other threads.
         */
        fun reportThreads(reason: String) {
            val inst = InstrumentationRegistry.getInstrumentation()
            Thread.getAllStackTraces().filterKeys { it.name in DIAGNOSED_THREADS }.forEach { (t, frames) ->
                val text = buildString {
                    append("DANIM_AAOS_THREAD reason=").append(reason).append(" name=").append(t.name)
                        .append(" id=").append(t.id).append(" state=").append(t.state).append('\n')
                    frames.take(MAX_FRAMES).forEach { append("    at ").append(it).append('\n') }
                    if (frames.size > MAX_FRAMES) append("    ... ").append(frames.size - MAX_FRAMES).append(" more\n")
                }
                inst.sendStatus(2, Bundle().apply { putString("stream", text) })
            }
        }

        const val FROZEN_WALL_MS = 1_800_000_000_000L
        const val MAX_PARK_S = 90L
        val EXPECTED_SIGNALS = setOf(Signal.IGNITION_STATE, Signal.CHARGING_STATE, Signal.RANGE_KM, Signal.VEHICLE_MOTION)
    }
}
