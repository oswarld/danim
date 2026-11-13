package app.danim.automotive.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.danim.automotive.R
import app.danim.automotive.SignalInfo
import app.danim.automotive.graph
import app.danim.automotive.ui.MainActivity
import app.danim.automotive.work.UploadScheduler
import app.danim.core.Availability
import app.danim.core.EndReason
import app.danim.core.Observation
import app.danim.core.ObservationPipeline
import app.danim.core.ObservationFactory
import app.danim.core.RetentionPolicy
import app.danim.core.SessionTracker
import app.danim.core.ReadingSink
import app.danim.core.Signal
import app.danim.core.Source
import app.danim.core.SignalGate
import app.danim.core.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground collector. Persists every gated observation to the Room outbox *before* any upload is
 * attempted; uploading is delegated to WorkManager. Runs only while the user wants collection
 * (explicit stop or disconnect clears that intent) and a device credential exists.
 */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must follow startForegroundService promptly, even if we then stop.
        promote()
        val g = graph
        if (intent?.action == ACTION_STOP) {
            g.settings.collectionWanted = false
            finish()
            return START_NOT_STICKY
        }
        if (!canCollect()) {
            finish()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { runCollection() }.also { g.collectorJob = it }
        return START_STICKY
    }

    override fun onDestroy() {
        graph.collectorState.value = "stopped"
        // Do not cancel the scope yet: the job's finally block stops the SDK, closes the channel and lets the
        // writer drain every queued observation into Room. The scope is cancelled only once that has finished.
        val j = job
        if (j == null) scope.cancel() else {
            j.cancel()
            j.invokeOnCompletion { scope.cancel() }
        }
        super.onDestroy()
    }

    private fun canCollect(): Boolean {
        val s = graph.settings
        return s.collectionWanted && s.consentAtMs > 0 && !s.pausedReauth && !s.disconnectIncomplete && graph.vault.loadCredential() != null
    }

    private fun finish() {
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runCollection() {
        val g = graph
        val clock = System::currentTimeMillis
        // Every row this session writes is bound to this device id, whatever happens to the credential later.
        val binding = g.vault.loadCredential()?.deviceId ?: run { finish(); return }
        val tracker = SessionTracker(g.sessions)
        val factory = ObservationFactory()
        val gate = SignalGate()
        val channel = Channel<Observation>(Channel.UNLIMITED) // the writer persists each batch immediately, so this stays tiny
        val sink: ReadingSink = { signal, availability, value, at, origin ->
            channel.trySend(factory.fromReading(signal, availability, value, at, origin))
        }
        val source = VehicleSignalSource(applicationContext, clock, sink)
        val aaos = AaosPropertySource(applicationContext, clock, sink)
        var lastKick = 0L
        g.collectorState.value = "running"
        tracker.begin(clock()) // records an UnobservedGap when the previous session did not end cleanly
        UploadScheduler.ensurePeriodic(applicationContext)

        // Every accepted observation is inserted into Room as soon as it arrives (no RAM buffering).
        val pipeline = ObservationPipeline(g.outbox, gate, binding, clock) { accepted ->
            for (o in accepted) {
                g.signalBoard.value = g.signalBoard.value + (o.signal to SignalInfo(o.availability, o.value, o.observedAtMs))
            }
            val now = clock()
            if (now - lastKick >= UPLOAD_KICK_MS) {
                lastKick = now
                UploadScheduler.kick(applicationContext)
            }
        }
        val writer = scope.launch { pipeline.consume(channel) }

        var aaosStarted = false
        var reason = EndReason.SERVICE_DESTROYED
        try {
            while (currentCoroutineContext().isActive) {
                withContext(Dispatchers.Main) {
                    if (!source.isInitialized) {
                        val failure = source.start()
                        if (failure != null) reportSdkDown(channel, factory, clock())
                    } else {
                        source.poll()
                    }
                    // The AAOS source is independent of the Pleos SDK: it connects and polls on its own thread.
                    if (!aaosStarted) { aaos.start(); aaosStarted = true } else aaos.poll()
                }
                g.sdkState.value = "${source.readiness()} (SDK ${app.danim.automotive.BuildConfig.VEHICLE_SDK_VERSION}); ${aaos.readiness()}"
                tracker.beat(clock()) // records a heartbeat_delayed gap if the device slept since the last beat
                // Retention removals are never silent: they feed the same diagnostics counter as uploads.
                RetentionPolicy().apply(g.outbox, clock()).let { if (it > 0) g.settings.addExpired(it) }
                if (g.outbox.count(binding) > 0) UploadScheduler.kick(applicationContext)
                val s = g.settings
                if (!s.collectionWanted) { reason = EndReason.USER_STOP; break }
                if (s.pausedReauth || g.vault.loadCredential()?.deviceId != binding) { reason = EndReason.UNPAIRED; break }
                delay(if (source.isInitialized) POLL_MS else RETRY_INIT_MS)
            }
        } finally {
            withContext(NonCancellable) {
                if (!g.settings.collectionWanted) reason = EndReason.USER_STOP
                withContext(Dispatchers.Main) { source.stop() }
                // Barrier: queued AAOS readings reach the channel before it is closed. If the executor does not terminate in
                // time its queue is discarded and counted; the shutdown is never presented as a clean drain.
                val drain = aaos.stopAndAwait()
                if (!drain.drained) g.settings.addLostOnShutdown(maxOf(1, drain.discardedTasks))
                channel.close() // the writer drains whatever is still queued, then returns
                writer.join()
                tracker.end(clock(), reason)
                g.collectorState.value = "stopped"
                g.sdkState.value = "not started"
                g.collectorJob = null
                UploadScheduler.kick(applicationContext)
            }
        }
        finish()
    }

    /** SDK/service unreachable: say so for every collected signal instead of staying silent. */
    private fun reportSdkDown(channel: Channel<Observation>, factory: ObservationFactory, now: Long) {
        for (s in COLLECTED) channel.trySend(factory.missing(s, Availability.ERROR, now, Source.PLEOS_VEHICLE_SDK))
    }

    private fun promote() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CollectorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        const val ACTION_STOP = "app.danim.automotive.STOP"
        private const val CHANNEL = "collector"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 60_000L
        private const val RETRY_INIT_MS = 30_000L
        private const val UPLOAD_KICK_MS = 30_000L
        private val COLLECTED = listOf(
            Signal.ODOMETER_KM, Signal.BATTERY_SOC_PERCENT, Signal.FUEL_LEVEL_PERCENT,
            Signal.EV_EFFICIENCY, Signal.FUEL_EFFICIENCY,
        )
    }
}

/** Entry points used by the UI and the boot receiver. */
object Collector {
    /** Returns null on success or a short reason (e.g. background-start restriction). */
    fun start(context: Context): String? = try {
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CollectorService::class.java))
        null
    } catch (e: Exception) {
        e.javaClass.simpleName
    }

    /** Explicit stop: clears the "wanted" intent first so neither boot recovery nor the service restarts it. */
    fun stop(context: Context) {
        context.graph.settings.collectionWanted = false
        context.stopService(Intent(context, CollectorService::class.java))
    }

    /** Stop, then wait until the collector's final drain into Room has completed. */
    suspend fun stopAndJoin(context: Context) {
        val job = context.graph.collectorJob
        stop(context)
        job?.join()
    }
}
