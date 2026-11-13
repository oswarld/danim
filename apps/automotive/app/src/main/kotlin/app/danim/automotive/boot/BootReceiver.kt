package app.danim.automotive.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.danim.automotive.collector.Collector
import app.danim.automotive.graph
import app.danim.automotive.work.UploadScheduler

/**
 * Restores collection after boot or app update, only if the user previously paired, consented and did
 * not stop/disconnect. Android may refuse a background foreground-service start; the outcome is
 * recorded for the diagnostics screen rather than hidden. A force-stopped app receives no broadcasts
 * until the user opens it again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val g = context.graph
        val s = g.settings
        val now = System.currentTimeMillis()
        if (g.vault.loadCredential() == null) { s.lastBootNote = "boot@$now: not paired"; return }
        if (s.disconnectIncomplete) { s.lastBootNote = "boot@$now: disconnect incomplete; nothing started"; return }
        UploadScheduler.ensurePeriodic(context)
        UploadScheduler.kick(context) // flush anything left in the outbox from before the restart
        if (!s.collectionWanted || s.consentAtMs == 0L || s.pausedReauth) {
            s.lastBootNote = "boot@$now: collection not enabled"
            return
        }
        val failure = Collector.start(context)
        s.lastBootNote = if (failure == null) "boot@$now: collector start requested" else "boot@$now: start blocked ($failure)"
    }
}
