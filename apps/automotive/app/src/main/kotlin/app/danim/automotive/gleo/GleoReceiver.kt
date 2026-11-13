package app.danim.automotive.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.danim.automotive.graph
import app.danim.automotive.ui.CopilotModel
import app.danim.core.copilot.Gleo
import java.util.Calendar

/**
 * Receives Gleo Actions (Pleos app-integration guide): the car's voice assistant broadcasts one of the
 * functions declared in `assets/schema.json`, with its arguments as JSON in `ACTION_PARAM`. DANIM answers
 * from the same context the copilot uses — this car's collector readings when it is paired and collecting,
 * the labelled fixed record otherwise — and sends the callback to the Gleo package only.
 *
 * Nothing here sends a vehicle command. The integration becomes active for drivers once Pleos has reviewed
 * the schema; until then the receiver can be exercised with `adb shell am broadcast`.
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = Gleo.functionOf(intent.action) ?: return
        val g = context.graph
        val signals = if (g.collectorState.value == "running") g.signalBoard.value else null
        val c = CopilotModel.context(signals, null, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        val reply = Gleo.handle(function, intent.getStringExtra(ACTION_PARAM), c)
        if (reply.showChargers) g.navi.showNearbyChargers { }
        context.sendBroadcast(
            Intent(RESPONSE_ACTION).setPackage(GLEO_PACKAGE)
                .putExtra("packageName", context.packageName).putExtra("response", reply.json()),
        )
        Log.i(TAG, "gleo ${reply.functionName} -> ${reply.status}")
    }

    private companion object {
        const val TAG = "DanimGleo"
        const val ACTION_PARAM = "ACTION_PARAM"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        /** Gleo AI on Pleos Connect 2.1 (observed on the official emulator); the callback is not sent to any other app. */
        const val GLEO_PACKAGE = "ai.umos.ambientai"
    }
}
