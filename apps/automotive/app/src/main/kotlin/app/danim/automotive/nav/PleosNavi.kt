package app.danim.automotive.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** What a navigation request ended in. Distances are what the Pleos navigation app reported. */
sealed interface NaviOutcome {
    data object Waiting : NaviOutcome
    data object ChargersRequested : NaviOutcome
    data class Destination(val name: String, val remainingKm: Double) : NaviOutcome
    data object NoDestination : NaviOutcome
    data class Failed(val reason: String) : NaviOutcome
}

/**
 * The two navigation hand-offs the copilot uses, through the Pleos NaviHelper SDK: ask the navigation app
 * to show its nearby-chargers screen, and read the destination it already has. DANIM reads no location and
 * sets no route; both calls go to the car's own navigation app, which decides what to show.
 */
class PleosNavi(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var connected = false
    private var pendingDestination: ((NaviOutcome) -> Unit)? = null

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name.ifBlank { destinationInfo.address }
            deliver(if (name.isBlank() && destinationInfo.remainDistance <= 0) NaviOutcome.NoDestination
            else NaviOutcome.Destination(name, destinationInfo.remainDistance / 1000.0))
        }
        override fun onError(errorCode: NaviErrorCode) = deliver(NaviOutcome.Failed(errorCode.name))
    }

    private fun deliver(outcome: NaviOutcome) {
        val cb = synchronized(this) { pendingDestination.also { pendingDestination = null } } ?: return
        main.post { cb(outcome) }
    }

    private fun withHelper(onFailed: (NaviOutcome) -> Unit, block: (NaviHelper) -> Unit) {
        try {
            val existing = helper
            if (existing != null && connected) { block(existing); return }
            val created = existing ?: NaviHelper(context.applicationContext).also { helper = it }
            created.initialize(
                onConnected = {
                    connected = true
                    try { created.addListener(events); block(created) } catch (e: Throwable) { fail(onFailed, e) }
                },
                onFailed = { fail(onFailed, it) },
            )
        } catch (e: Throwable) {
            fail(onFailed, e)
        }
    }

    private fun fail(onFailed: (NaviOutcome) -> Unit, e: Throwable) {
        Log.w(TAG, "navi unavailable: ${e.javaClass.simpleName}")
        main.post { onFailed(NaviOutcome.Failed(e.javaClass.simpleName)) }
    }

    fun showNearbyChargers(onResult: (NaviOutcome) -> Unit) = withHelper(onResult) { navi ->
        navi.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations)
        main.post { onResult(NaviOutcome.ChargersRequested) }
    }

    fun readDestination(onResult: (NaviOutcome) -> Unit) = withHelper(onResult) { navi ->
        synchronized(this) { pendingDestination = onResult }
        navi.getDestinationInfo()
        // The navigation app answers through the listener; with no route it may not answer at all.
        main.postDelayed({ deliver(NaviOutcome.NoDestination) }, ANSWER_TIMEOUT_MS)
    }

    private companion object {
        const val TAG = "DanimNavi"
        const val ANSWER_TIMEOUT_MS = 4_000L
    }
}
