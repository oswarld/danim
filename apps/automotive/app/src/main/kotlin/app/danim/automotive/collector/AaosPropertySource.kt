package app.danim.automotive.collector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.danim.core.AaosAccessTracker
import app.danim.core.AaosAction
import app.danim.core.AaosEvent
import app.danim.core.AaosProperty
import app.danim.core.AaosReadings
import app.danim.core.Availability
import app.danim.core.DrainResult
import app.danim.core.ReadinessDeadline
import app.danim.core.shutdownAndAwait
import app.danim.core.ReadingSink
import app.danim.core.Signal
import app.danim.core.Source
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Read-only source for ignition, motion (speed + gear), charging state and remaining range from the standard AAOS
 * `CarPropertyManager`. Every reading is tagged `aaos_car_property`; all interpretation (units, freshness, status,
 * meaning) is in the unit-tested core `AaosReadings`.
 *
 * The Car library (`android.car`) is not a Maven artifact; on API 35 it ships as the SDK's optional `android.car.jar`,
 * but this app deliberately reaches it by reflection and declares `<uses-library android:required="false">`, so the
 * APK installs on minSdk 28 and on devices without the library. Every reflected class, method and constant was
 * independently checked against the real API 35 `optional/android.car.jar` signatures. It remains reflection (no
 * compile-time checking); it ran on Pleos 2.1/API 34, but not a real vehicle. `CarPropertyManager`, `VehiclePropertyIds`,
 * `CarPropertyValue` and the lifecycle-aware `Car.createCar` overload exist from API 29; below
 * API 29 every AAOS signal is reported `unsupported` with an explicit reason (no legacy sensor/factory fallback).
 * Where the library, a property, or a permission is absent the signal is reported explicitly
 * (`unsupported` / `permission_denied` / `error`), never silently skipped.
 *
 * Properties and permissions (android.car.VehiclePropertyIds / android.car.Car):
 * IGNITION_STATE, GEAR_SELECTION -> CAR_POWERTRAIN (normal); PERF_VEHICLE_SPEED -> CAR_SPEED (dangerous, runtime grant);
 * EV_CHARGE_STATE, RANGE_REMAINING -> CAR_ENERGY (dangerous, runtime grant). Property lists omit both unsupported and
 * inaccessible entries, so permission is checked first and only then is absence attributed to the vehicle.
 *
 * All Car calls run on one private thread (never main). Callbacks are re-posted to it, so the non-thread-safe fusion
 * in [AaosReadings] is single-threaded.
 *
 * `createCar` is called with `CAR_WAIT_TIMEOUT_DO_NOT_WAIT`, so readiness arrives only through the lifecycle callback.
 * Each readiness wait is bounded by [READINESS_TIMEOUT_MS] on the monotonic clock. If no ready callback arrives in time,
 * the next heartbeat invalidates the connection generation, disconnects that Car, reports every AAOS signal as
 * `unavailable` (null value) and creates a fresh Car on a later heartbeat. Late callbacks from the abandoned Car are ignored.
 */
class AaosPropertySource(
    context: Context,
    private val clock: () -> Long,
    private val sink: ReadingSink,
) {
    private val app = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "danim-aaos") }
    private val eventHandler = Handler(Looper.getMainLooper())
    private val readings = AaosReadings(sink)

    @Volatile private var state = "AAOS not started"
    @Volatile private var stopping = false

    // Everything below is confined to [executor].
    private var car: Any? = null
    private var carReady = false
    private var connectionGeneration = 0L
    private var lifecycleCallback: Any? = null
    private val readinessDeadline = ReadinessDeadline(READINESS_TIMEOUT_MS)
    private var manager: Any? = null
    private var configIds: Set<Int> = emptySet()
    private val access = AaosAccessTracker()
    private var callback: Any? = null
    private var connectFailures = 0
    private val names = HashMap<AaosProperty, Map<Int, String>>()

    fun readiness(): String = state

    fun start() = post { connect() }

    /** Heartbeat: connect/retry, (re)register newly permitted properties, re-read, and re-evaluate motion staleness. */
    fun poll() = post {
        if (manager == null) connect() else {
            reconcile() // re-fetch the accessible property list and re-check permissions on every heartbeat
            access.registeredProperties().forEach { readNow(it) }
        }
        // Only fuse motion while speed is actually subscribed; otherwise the explicit status from report() stands.
        if (access.isRegistered(AaosProperty.PERF_VEHICLE_SPEED)) readings.reevaluateMotion(clock())
    }

    /**
     * Teardown barrier: queued reads/callbacks run first (so readings already received still reach the sink),
     * then the callback is unregistered and the Car disconnected; returns once the executor has drained. Call from a
     * coroutine before closing the observation channel. Blocks only a background dispatcher, bounded by [timeoutMs].
     */
    suspend fun stopAndAwait(timeoutMs: Long = 5_000): DrainResult {
        stopping = true
        post { teardown() }
        return shutdownAndAwait(executor, timeoutMs) // drained=false means queued AAOS work was discarded: caller records it
    }

    private fun post(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    state = "AAOS error: ${e.javaClass.simpleName}"
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    // ---- connection -------------------------------------------------------------------------

    private fun connect() {
        if (stopping || manager != null) return
        car?.let { current ->
            when {
                carReady -> acquireManager(current, connectionGeneration)
                readinessDeadline.isExpired(SystemClock.elapsedRealtime()) -> expireReadinessWait()
            }
            // After expiry, a fresh Car is created on a later heartbeat, never in the same pass.
            return
        }
        if (Build.VERSION.SDK_INT < MIN_CAR_PROPERTY_API) {
            state = "AAOS unavailable: CarPropertyManager requires API $MIN_CAR_PROPERTY_API (this device is API ${Build.VERSION.SDK_INT})"
            reportAll(Availability.UNSUPPORTED)
            return
        }
        val carClass = try {
            Class.forName("android.car.Car")
        } catch (_: ClassNotFoundException) {
            state = "AAOS unavailable: android.car library not present on this device"
            reportAll(Availability.UNSUPPORTED)
            return
        }
        try {
            val generation = ++connectionGeneration
            val listener = Class.forName("android.car.Car\$CarServiceLifecycleListener")
            val lifecycle = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
                when (method.name) {
                    "onLifecycleChanged" -> {
                        val changedCar = args?.getOrNull(0)
                        val ready = args?.getOrNull(1) as? Boolean
                        // The platform invokes this on main. Never call a Car manager from this callback.
                        if (!stopping && changedCar != null && ready != null) {
                            post { onLifecycleChanged(changedCar, ready, generation) }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DanimAaosLifecycleCallback"
                    else -> null
                }
            }
            lifecycleCallback = lifecycle
            state = "AAOS waiting for car service readiness"
            val noWait = carClass.getField("CAR_WAIT_TIMEOUT_DO_NOT_WAIT").getLong(null)
            val created = carClass.getMethod(
                "createCar", Context::class.java, Handler::class.java, Long::class.javaPrimitiveType, listener,
            ).invoke(null, app, eventHandler, noWait, lifecycle)
            if (created == null) {
                lifecycleCallback = null
                connectionGeneration++
                readinessDeadline.clear()
                state = "AAOS unavailable: not a car environment"
                reportAll(Availability.UNSUPPORTED)
                return
            }
            car = created
            // Even if createCar returns a connected object, its lifecycle callback is the readiness boundary.
            readinessDeadline.start(SystemClock.elapsedRealtime())
        } catch (e: Throwable) {
            lifecycleCallback = null
            connectionGeneration++
            readinessDeadline.clear()
            val cause = (e as? InvocationTargetException)?.cause ?: e
            state = "AAOS connect failed (${cause.javaClass.simpleName}); attempt ${++connectFailures}"
            reportAll(if (cause is SecurityException) Availability.PERMISSION_DENIED else Availability.ERROR)
        }
    }

    private fun onLifecycleChanged(changedCar: Any, ready: Boolean, generation: Long) {
        if (stopping || generation != connectionGeneration || car !== changedCar) return
        carReady = ready
        if (!ready) {
            // The platform has disconnected these managers already. Do not make calls on stale binders.
            clearManager()
            // A new readiness wait for the same Car; it is bounded like the first one.
            readinessDeadline.start(SystemClock.elapsedRealtime())
            state = "AAOS car service disconnected; waiting for readiness"
            reportAll(Availability.UNAVAILABLE)
            return
        }
        readinessDeadline.clear()
        if (manager == null) acquireManager(changedCar, generation)
    }

    /**
     * The ready callback did not arrive within [READINESS_TIMEOUT_MS]. Invalidate the generation first so any later
     * callback from this Car (including one triggered by disconnect) is ignored, then drop it and report explicitly.
     * No observation is invented: every AAOS signal becomes `unavailable` with a null value.
     */
    private fun expireReadinessWait() {
        val staleCar = car
        connectionGeneration++
        readinessDeadline.clear()
        lifecycleCallback = null
        carReady = false
        car = null
        clearManager()
        runCatching { staleCar?.javaClass?.getMethod("disconnect")?.invoke(staleCar) }
        state = "AAOS car service not ready within ${READINESS_TIMEOUT_MS / 1000} s; " +
            "retrying on a later heartbeat (attempt ${++connectFailures})"
        reportAll(Availability.UNAVAILABLE)
    }

    private fun acquireManager(readyCar: Any, generation: Long) {
        if (stopping || generation != connectionGeneration || car !== readyCar || !carReady) return
        try {
            val service = readyCar.javaClass.getField("PROPERTY_SERVICE").get(null) as String
            val acquired = readyCar.javaClass.getMethod("getCarManager", String::class.java).invoke(readyCar, service)
            if (stopping || generation != connectionGeneration || car !== readyCar || !carReady) return
            manager = acquired
            if (manager == null) {
                state = "AAOS unavailable: property service missing"
                reportAll(Availability.UNSUPPORTED)
                return
            }
            connectFailures = 0
            reconcile()
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.cause ?: e
            state = "AAOS property manager failed (${cause.javaClass.simpleName}); attempt ${++connectFailures}"
            reportAll(if (cause is SecurityException) Availability.PERMISSION_DENIED else Availability.ERROR)
            clearManager()
        }
    }

    /** Fetches the property list the app can *currently* read. Android omits denied and unsupported entries alike. */
    private fun fetchConfigIds(): Set<Int>? = try {
        (manager!!.javaClass.getMethod("getPropertyList").invoke(manager) as Collection<*>)
            .mapNotNull { it?.javaClass?.getMethod("getPropertyId")?.invoke(it) as? Int }.toSet()
    } catch (e: Throwable) {
        null
    }

    /**
     * Per heartbeat/reconnect: refresh the list, re-check each permission, then register newly accessible
     * properties and unregister ones that lost access. A list fetched before a runtime grant is never reused.
     */
    private fun reconcile() {
        val ids = fetchConfigIds()
        if (ids == null) {
            state = "AAOS property list unavailable; keeping current subscriptions"
            return
        }
        configIds = ids
        for (p in AaosProperty.entries) {
            val granted = app.checkSelfPermission(permissionFor(p)) == PackageManager.PERMISSION_GRANTED
            val id = propertyId(p)
            when (val action = access.evaluate(p, granted, id != null, id != null && id in ids)) {
                AaosAction.Register -> register(p)
                is AaosAction.Unregister -> { unregisterProperty(p); report(p, action.reason) }
                is AaosAction.Report -> report(p, action.availability)
                AaosAction.None -> Unit
            }
        }
        state = "AAOS connected; ${ids.size} accessible properties listed; registered: " +
            access.registeredProperties().joinToString { it.name }.ifEmpty { "none" }
    }

    private fun register(p: AaosProperty) {
        val mgr = manager ?: return
        val id = propertyId(p) ?: return
        try {
            val cb = callback ?: newCallback().also { callback = it }
            val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val ok = mgr.javaClass
                .getMethod("registerCallback", iface, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(mgr, cb, id, rateFor(p)) as? Boolean ?: false
            if (!ok) {
                report(p, Availability.ERROR) // subscription failure stays visible
                return
            }
            access.markRegistered(p)
            readNow(p)
        } catch (e: Throwable) {
            report(p, classify(e))
        }
    }

    /** Per-property unregister; if the platform lacks that overload, drop the whole callback and let reconcile re-register. */
    private fun unregisterProperty(p: AaosProperty) {
        val mgr = manager ?: return
        val cb = callback ?: return
        val id = propertyId(p) ?: return
        val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        try {
            mgr.javaClass.getMethod("unregisterCallback", iface, Int::class.javaPrimitiveType).invoke(mgr, cb, id)
        } catch (_: NoSuchMethodException) {
            runCatching { mgr.javaClass.getMethod("unregisterCallback", iface).invoke(mgr, cb) }
            access.reset()
            callback = null
        } catch (_: Throwable) {
        }
        // Readings already queued for this property must not be presented as current after access was lost.
        readings.forget(p)
    }

    private fun readNow(p: AaosProperty) {
        val mgr = manager ?: return
        val id = propertyId(p) ?: return
        try {
            val m: Method = mgr.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val value = m.invoke(mgr, id, GLOBAL_AREA)
            if (value == null) report(p, Availability.UNAVAILABLE) else onValue(value)
        } catch (e: Throwable) {
            report(p, classify(e))
        }
    }

    // ---- events -------------------------------------------------------------------------------

    private fun newCallback(): Any {
        val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        val generation = connectionGeneration
        val callbackManager = manager
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    args?.firstOrNull()?.let { v ->
                        post {
                            if (generation == connectionGeneration && manager === callbackManager && callback === proxy) onValue(v)
                        }
                    }
                    null
                }
                "onErrorEvent" -> {
                    val id = args?.getOrNull(0) as? Int
                    post {
                        if (generation == connectionGeneration && manager === callbackManager && callback === proxy) {
                            AaosProperty.entries.firstOrNull { propertyId(it) == id }?.let { report(it, Availability.ERROR) }
                        }
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DanimAaosCallback"
                else -> null
            }
        }
    }

    /** Converts a reflected CarPropertyValue into an [AaosEvent] and hands it to the core mapper. */
    private fun onValue(v: Any) {
        val c = v.javaClass
        val id = c.getMethod("getPropertyId").invoke(v) as Int
        val p = AaosProperty.entries.firstOrNull { propertyId(it) == id } ?: return
        val raw = c.getMethod("getValue").invoke(v)
        val status = c.getMethod("getStatus").invoke(v) as Int
        val ts = c.getMethod("getTimestamp").invoke(v) as Long
        val number = (raw as? Number)?.toDouble()
        val enumName = if (p == AaosProperty.IGNITION_STATE || p == AaosProperty.EV_CHARGE_STATE || p == AaosProperty.GEAR_SELECTION) {
            (raw as? Number)?.toInt()?.let { constantNames(p)[it] }
        } else null
        readings.handle(AaosEvent(p, number, enumName, status, ts), SystemClock.elapsedRealtimeNanos(), clock())
    }

    private fun teardown() {
        // Capture exactly the owned connection; stale callbacks never close a replacement Car.
        val ownedCar = car
        connectionGeneration++
        readinessDeadline.clear()
        lifecycleCallback = null
        carReady = false
        car = null
        val mgr = manager
        val cb = callback
        if (mgr != null && cb != null) {
            runCatching {
                val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                mgr.javaClass.getMethod("unregisterCallback", iface).invoke(mgr, cb)
            }
        }
        runCatching { ownedCar?.javaClass?.getMethod("disconnect")?.invoke(ownedCar) }
        clearManager()
        state = "AAOS stopped"
    }

    private fun clearManager() {
        access.reset()
        AaosProperty.entries.forEach(readings::forget)
        configIds = emptySet()
        callback = null
        manager = null
    }

    // ---- reflection helpers ------------------------------------------------------------------------

    private fun propertyId(p: AaosProperty): Int? = runCatching {
        Class.forName("android.car.VehiclePropertyIds").getField(p.name).getInt(null)
    }.getOrNull()

    /** Platform constant names when the class exists; otherwise the values documented in the Android reference. */
    private fun constantNames(p: AaosProperty): Map<Int, String> = names.getOrPut(p) {
        val (className, fallback) = when (p) {
            AaosProperty.IGNITION_STATE -> "android.car.VehicleIgnitionState" to
                mapOf(0 to "UNDEFINED", 1 to "LOCK", 2 to "OFF", 3 to "ACC", 4 to "ON", 5 to "START")
            AaosProperty.EV_CHARGE_STATE -> "android.car.hardware.property.EvChargeState" to
                mapOf(0 to "STATE_UNKNOWN", 1 to "STATE_CHARGING", 2 to "STATE_FULLY_CHARGED", 3 to "STATE_NOT_CHARGING", 4 to "STATE_ERROR")
            else -> "android.car.VehicleGear" to mapOf(4 to "GEAR_PARK")
        }
        val reflected = runCatching {
            Class.forName(className).fields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name }
        }.getOrDefault(emptyMap())
        fallback + reflected
    }

    private fun permissionFor(p: AaosProperty) = when (p) {
        AaosProperty.IGNITION_STATE, AaosProperty.GEAR_SELECTION -> "android.car.permission.CAR_POWERTRAIN"
        AaosProperty.PERF_VEHICLE_SPEED -> "android.car.permission.CAR_SPEED"
        AaosProperty.EV_CHARGE_STATE, AaosProperty.RANGE_REMAINING -> "android.car.permission.CAR_ENERGY"
    }

    /** SENSOR_RATE_NORMAL (1 Hz) for the continuous speed property, SENSOR_RATE_ONCHANGE (0) otherwise. */
    private fun rateFor(p: AaosProperty) = if (p == AaosProperty.PERF_VEHICLE_SPEED) 1.0f else 0.0f

    private fun classify(e: Throwable): Availability {
        val cause = (e as? InvocationTargetException)?.cause ?: e
        val n = cause.javaClass.simpleName
        return when {
            cause is SecurityException -> Availability.PERMISSION_DENIED
            n.contains("NotAvailable") -> Availability.UNAVAILABLE
            else -> Availability.ERROR
        }
    }

    // ---- status reporting --------------------------------------------------------------------------

    private fun signalOf(p: AaosProperty) = when (p) {
        AaosProperty.IGNITION_STATE -> Signal.IGNITION_STATE
        AaosProperty.EV_CHARGE_STATE -> Signal.CHARGING_STATE
        AaosProperty.RANGE_REMAINING -> Signal.RANGE_KM
        AaosProperty.PERF_VEHICLE_SPEED, AaosProperty.GEAR_SELECTION -> Signal.VEHICLE_MOTION
    }

    /**
     * Gear alone is not a motion signal, so a missing gear property never reports motion as failed; only speed does.
     * (Without gear, motion can still be `moving` or `unknown`, but never `parked`.)
     */
    private fun report(p: AaosProperty, a: Availability) {
        if (p == AaosProperty.GEAR_SELECTION) return
        sink(signalOf(p), a, null, clock(), Source.AAOS_CAR_PROPERTY)
    }

    private fun reportAll(a: Availability) = AaosProperty.entries.forEach { report(it, a) }

    private companion object {
        const val GLOBAL_AREA = 0

        /** CarPropertyManager, vehicle property values and lifecycle-aware Car creation require API 29. */
        const val MIN_CAR_PROPERTY_API = 29

        /** Bound on each wait for the Car lifecycle ready callback (monotonic elapsed realtime). */
        const val READINESS_TIMEOUT_MS = 30_000L
    }
}
