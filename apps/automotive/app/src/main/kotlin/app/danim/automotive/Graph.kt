package app.danim.automotive

import android.app.Application
import android.content.Context
import app.danim.automotive.data.AppDatabase
import app.danim.automotive.data.CredentialVault
import app.danim.automotive.data.RoomOutboxStore
import app.danim.automotive.data.RoomSessionStore
import app.danim.automotive.data.Settings
import app.danim.automotive.net.DanimHttp
import app.danim.automotive.nav.PleosNavi
import app.danim.automotive.voice.PleosEar
import app.danim.automotive.voice.PleosVoice
import app.danim.core.Availability
import app.danim.core.CollectorReporter
import app.danim.core.CollectorState
import app.danim.core.CredentialRenewer
import app.danim.core.LifecycleLock
import app.danim.core.Signal
import app.danim.core.Value
import kotlinx.coroutines.flow.MutableStateFlow

/** Latest in-process view of one signal, for diagnostics. Not persisted. */
data class SignalInfo(val availability: Availability, val value: Value?, val observedAtMs: Long)

/** Manual dependency container; the process is small enough that a DI framework would add nothing. */
class Graph(app: Application) {
    val settings = Settings(app)
    val vault = CredentialVault(app)
    val db = AppDatabase.create(app)
    val outbox = RoomOutboxStore(db.outbox())
    val sessions = RoomSessionStore(db.sessions())
    val http = DanimHttp(settings)

    /** Serialises upload runs, disconnect and re-pair commits (see core LifecycleLock). */
    val lifecycle = LifecycleLock()

    /** Runs only inside [lifecycle] (see core renewAndUpload). */
    val renewer = CredentialRenewer(vault.state, http.credentials)

    /** Latest-state diagnostics sent in the upload cycle; reads this process's service flag and intent only. */
    val collectorReporter = CollectorReporter(
        http.collectorReports,
        queue = { binding -> outbox.snapshot(binding) },
        collectionWanted = { settings.collectionWanted },
        collectorState = {
            when (collectorState.value) {
                "running" -> CollectorState.RUNNING
                "stopped" -> CollectorState.STOPPED
                else -> CollectorState.UNKNOWN
            }
        },
    )

    /** The running collector coroutine, if any; disconnect joins it so its final drain finishes before data is cleared. */
    @Volatile var collectorJob: kotlinx.coroutines.Job? = null

    val signalBoard = MutableStateFlow<Map<Signal, SignalInfo>>(emptyMap())
    val collectorState = MutableStateFlow("stopped")
    val sdkState = MutableStateFlow("not started")

    /** Spoken output (Pleos TextToSpeech, on-device). Created lazily on first use. */
    val voice: PleosVoice by lazy { PleosVoice(app) }

    /** Voice input (Pleos SpeechToText, on-device) and the navigation hand-off (Pleos NaviHelper). */
    val ear: PleosEar by lazy { PleosEar(app) }
    val navi: PleosNavi by lazy { PleosNavi(app) }
}

class DanimApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

val Context.graph: Graph get() = (applicationContext as DanimApp).graph
