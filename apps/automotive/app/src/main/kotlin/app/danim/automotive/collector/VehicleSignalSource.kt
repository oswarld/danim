package app.danim.automotive.collector

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.content.Context
import app.danim.core.Availability
import app.danim.core.Conversions
import app.danim.core.ReadingSink
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Read-only adapter over the public, non-deprecated Pleos Vehicle SDK 2.3.2 typed API, each signal gated by its
 * capability check (check -> get -> register). Unit evidence is the SDK source JAR. Every reading
 * is tagged `pleos_vehicle_sdk`.
 *
 * | signal | SDK calls | value handling |
 * |---|---|---|
 * | odometer_km | Odometer.checkCurrentDistanceDrivenCapabilities / getCurrentDistanceDriven / registerDistanceDriven | already km, passed through |
 * | battery_soc_percent | EvBattery.checkEvBatteryStateOfChargeCapabilities / getEvBatteryStateOfCharge / registerEvBatteryStateOfCharge | Int percent |
 * | fuel_level_percent | Fuel.checkFuelPercentCapabilities / getFuelPercent / registerFuelPercent | Int percent |
 * | ev_efficiency_kwh_per_100km | EvBattery.checkCurrentEvEfficiencyCapabilities / getCurrentEvEfficiency / registerCurrentEvEfficiency | km/kWh -> 100/x, positive finite only |
 * | fuel_efficiency_l_per_100km | Fuel.checkCurrentFuelEconomyCapabilities / getCurrentFuelEconomy / registerCurrentFuelEconomy | already L/100 km |
 *
 * Not read through the Pleos SDK (ignition, motion, charging and range come from [AaosPropertySource]):
 * - Gear and range APIs are hidden/deprecated; `getEvChargeSwitchState` is the user's requested switch, not a
 *   charging state (no write API is referenced).
 * - vehicle_warning, tire_low_pressure: telltales and tyre pressure need the signature/privileged PRIVATE_CAR_SAFETY
 *   permission, which the app does not request -> reported `permission_denied`. The normal-permission low-tyre getter
 *   returns a kPa threshold, not a warning state, and is not used.
 *
 * SDK callbacks may arrive on any thread, so [sink] must be thread-safe.
 */
class VehicleSignalSource(
    private val context: Context,
    private val clock: () -> Long,
    private val sink: ReadingSink,
) {
    private var vehicle: Vehicle? = null
    private val unregister = CopyOnWriteArrayList<() -> Unit>()

    /** Signals whose capability check returned a readable access type. */
    private val confirmed = ConcurrentHashMap.newKeySet<Signal>()

    /** Signals whose capability check failed transiently (not unsupported/denied); retried a bounded number of times. */
    private val retry = ConcurrentHashMap.newKeySet<Signal>()
    private val retries = ConcurrentHashMap<Signal, Int>()

    private fun emit(signal: Signal, availability: Availability, value: Value?, at: Long) =
        sink(signal, availability, value, at, Source.PLEOS_VEHICLE_SDK)

    /** The SDK object exists and `initialize()` did not throw. This is NOT proof that any signal is readable. */
    val isInitialized: Boolean get() = vehicle != null

    /** Allocated SDK vs. capabilities actually confirmed by the vehicle service. */
    fun readiness(): String =
        if (vehicle == null) "Pleos SDK not initialized"
        else "Pleos SDK initialized; capabilities confirmed for ${confirmed.size}/${READ_SIGNALS.size} readable signals"

    /** Initializes the SDK and wires every signal. Returns null on success, otherwise a short failure reason. */
    @Synchronized
    fun start(): String? {
        if (vehicle != null) return null
        val v = Vehicle(context.applicationContext)
        try {
            v.initialize()
        } catch (e: Exception) {
            runCatching { v.release() }
            return "sdk_init_failed:${errorCodeName(e) ?: e.javaClass.simpleName}"
        }
        vehicle = v
        reportStatic()
        READ_SIGNALS.forEach { setup(v, it) }
        return null
    }

    /** One-shot real reads of confirmed signals, plus bounded retries of transiently failed capability checks. */
    @Synchronized
    fun poll() {
        val v = vehicle ?: return
        reportStatic()
        for (s in retry.toList()) {
            val n = retries.merge(s, 1, Int::plus) ?: 1
            if (n <= MAX_CAPABILITY_RETRIES) {
                retry.remove(s)
                setup(v, s)
            }
        }
        confirmed.forEach { read(v, it) }
    }

    @Synchronized
    fun stop() {
        unregister.forEach { runCatching { it() } }
        unregister.clear()
        confirmed.clear()
        retry.clear()
        retries.clear()
        vehicle?.let { runCatching { it.release() } }
        vehicle = null
    }

    private fun reportStatic() {
        val now = clock()
        for (s in listOf(Signal.VEHICLE_WARNING, Signal.TIRE_LOW_PRESSURE)) {
            emit(s, Availability.PERMISSION_DENIED, null, now)
        }
    }

    // ---- readable signals --------------------------------------------------------------------

    private fun setup(v: Vehicle, signal: Signal) {
        val onCap: (CapabilityConfig) -> Unit = { cfg ->
            if (canRead(cfg)) {
                confirmed += signal
                guard(signal) { register(v, signal) }
                read(v, signal)
            } else {
                emit(signal, Availability.UNSUPPORTED, null, clock())
            }
        }
        val onFail: (Exception) -> Unit = { e ->
            val status = classify(e)
            if (status == Availability.ERROR || status == Availability.UNAVAILABLE) retry += signal
            emit(signal, status, null, clock())
        }
        guard(signal) {
            when (signal) {
                Signal.ODOMETER_KM -> v.getOdometer().checkCurrentDistanceDrivenCapabilities(onCap, onFail)
                Signal.BATTERY_SOC_PERCENT -> v.getEvBattery().checkEvBatteryStateOfChargeCapabilities(onCap, onFail)
                Signal.FUEL_LEVEL_PERCENT -> v.getFuel().checkFuelPercentCapabilities(onCap, onFail)
                Signal.EV_EFFICIENCY -> v.getEvBattery().checkCurrentEvEfficiencyCapabilities(onCap, onFail)
                Signal.FUEL_EFFICIENCY -> v.getFuel().checkCurrentFuelEconomyCapabilities(onCap, onFail)
                else -> Unit
            }
        }
    }

    private fun register(v: Vehicle, signal: Signal) {
        when (signal) {
            Signal.ODOMETER_KM -> {
                val o = v.getOdometer()
                val l = object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(distance: Float?) = km(distance)
                    override fun onFailed(e: Exception) = failed(signal, e)
                }
                o.registerDistanceDriven(l)
                unregister += { o.unregisterDistanceDriven(l) }
            }
            Signal.BATTERY_SOC_PERCENT -> {
                val ev = v.getEvBattery()
                val l = object : EvBatteryStateOfChargeListener {
                    override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = percent(signal, soc)
                    override fun onFailed(e: Exception) = failed(signal, e)
                }
                ev.registerEvBatteryStateOfCharge(l)
                unregister += { ev.unregisterEvBatteryStateOfCharge(l) }
            }
            Signal.FUEL_LEVEL_PERCENT -> {
                val f = v.getFuel()
                val l = object : FuelPercentListener {
                    override fun onFuelPercentUpdated(percent: Int?) = percent(signal, percent)
                    override fun onFailed(e: Exception) = failed(signal, e)
                }
                f.registerFuelPercent(l)
                unregister += { f.unregisterFuelPercent(l) }
            }
            Signal.EV_EFFICIENCY -> {
                val ev = v.getEvBattery()
                val l = object : EvBatteryEfficiencyListener {
                    override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) = evEfficiency(efficiency)
                    override fun onFailed(e: Exception) = failed(signal, e)
                }
                ev.registerCurrentEvEfficiency(l)
                unregister += { ev.unregisterCurrentEvEfficiency(l) }
            }
            Signal.FUEL_EFFICIENCY -> {
                val f = v.getFuel()
                val l = object : FuelEconomyListener {
                    override fun onFuelEconomyUpdated(economy: Float?) = fuelEconomy(economy)
                    override fun onFailed(e: Exception) = failed(signal, e)
                }
                f.registerCurrentFuelEconomy(l)
                unregister += { f.unregisterCurrentFuelEconomy(l) }
            }
            else -> Unit
        }
    }

    private fun read(v: Vehicle, signal: Signal) = guard(signal) {
        val fail: (Exception) -> Unit = { e -> failed(signal, e) }
        when (signal) {
            Signal.ODOMETER_KM -> v.getOdometer().getCurrentDistanceDriven({ d -> km(d) }, fail)
            Signal.BATTERY_SOC_PERCENT -> v.getEvBattery().getEvBatteryStateOfCharge({ s -> percent(signal, s) }, fail)
            Signal.FUEL_LEVEL_PERCENT -> v.getFuel().getFuelPercent({ p -> percent(signal, p) }, fail)
            Signal.EV_EFFICIENCY -> v.getEvBattery().getCurrentEvEfficiency({ e -> evEfficiency(e) }, fail)
            Signal.FUEL_EFFICIENCY -> v.getFuel().getCurrentFuelEconomy({ e -> fuelEconomy(e) }, fail)
            else -> Unit
        }
    }

    // ---- value mapping -------------------------------------------------------------------------

    private fun km(value: Float?) = number(Signal.ODOMETER_KM, value?.toDouble())

    private fun percent(signal: Signal, value: Int?) = number(signal, value?.toDouble())

    private fun evEfficiency(kmPerKwh: Float?) = number(Signal.EV_EFFICIENCY, Conversions.kmPerKwhToKwhPer100km(kmPerKwh))

    private fun fuelEconomy(raw: Float?) = number(Signal.FUEL_EFFICIENCY, Conversions.fuelEconomyLPer100km(raw))

    /** null (or a non-finite value) is `unavailable`, never zero. The SDK gives no timestamp, so time is callback arrival. */
    private fun number(signal: Signal, value: Double?) {
        val now = clock()
        if (value == null || !value.isFinite()) emit(signal, Availability.UNAVAILABLE, null, now)
        else emit(signal, Availability.AVAILABLE, Value.Num(value), now)
    }

    private fun canRead(cfg: CapabilityConfig?): Boolean =
        cfg?.accessType == AccessType.READ || cfg?.accessType == AccessType.READ_WRITE

    private fun failed(signal: Signal, e: Exception) = emit(signal, classify(e), null, clock())

    private inline fun guard(signal: Signal, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failed(signal, e)
        }
    }

    companion object {
        private const val MAX_CAPABILITY_RETRIES = 5

        private val READ_SIGNALS = listOf(
            Signal.ODOMETER_KM, Signal.BATTERY_SOC_PERCENT, Signal.FUEL_LEVEL_PERCENT,
            Signal.EV_EFFICIENCY, Signal.FUEL_EFFICIENCY,
        )

        /** ErrorCode name via reflection: VehicleException is obfuscated in the AAR and ErrorCode is a runtime-only dependency. */
        fun errorCodeName(e: Exception): String? = runCatching {
            e.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType.isEnum && it.returnType.simpleName == "ErrorCode" }
                ?.invoke(e)?.toString()
        }.getOrNull()

        fun classify(e: Exception): Availability = when (errorCodeName(e)) {
            "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE" -> Availability.UNSUPPORTED
            "ERROR_SECURITY_PERMISSION" -> Availability.PERMISSION_DENIED
            "ERROR_EMPTY_RESULT", "ERROR_DRIVING_RESTRICTED" -> Availability.UNAVAILABLE
            else -> Availability.ERROR
        }
    }
}
