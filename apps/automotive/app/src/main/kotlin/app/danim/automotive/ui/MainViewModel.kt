package app.danim.automotive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.danim.automotive.R
import app.danim.automotive.SignalInfo
import app.danim.automotive.collector.Collector
import app.danim.automotive.graph
import app.danim.automotive.nav.NaviOutcome
import app.danim.automotive.work.UploadScheduler
import app.danim.core.DisconnectCoordinator
import app.danim.core.PairingCommit
import app.danim.core.PairingEnd
import app.danim.core.PairingPoller
import app.danim.core.PairingStart
import app.danim.core.Qr
import app.danim.core.commitPairing
import app.danim.core.Signal
import app.danim.core.UnobservedGap
import app.danim.core.copilot.Automations
import app.danim.core.copilot.Copilot
import app.danim.core.copilot.CopilotContext
import app.danim.core.copilot.Intent
import app.danim.core.copilot.Rule
import app.danim.core.copilot.Say
import app.danim.core.copilot.TripRecap
import app.danim.core.copilot.TripTracker
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MOCK is the labelled fixed-record showcase: bundled public records only, no credential, collector, upload or network.
 * COPILOT is the labelled copilot demonstration: it reads this car's collector readings when paired, speaks and
 * listens through the Pleos speech SDKs, hands off to Pleos navigation, and sends no command to the car.
 */
enum class Phase { SETUP, PAIRING, PAIRED, MOCK, COPILOT }

class PairingUi(val code: String, val link: String, val qr: Array<BooleanArray>, val expiresAtMs: Long)

class Diagnostics(
    val collectorState: String,
    val sdkState: String,
    val signals: Map<Signal, SignalInfo>,
    val queued: Int,
    val oldestQueuedMs: Long?,
    val acked: Long,
    val expired: Long,
    val quarantined: Long,
    val lostOnShutdown: Long,
    val lastUploadAtMs: Long,
    val lastUploadResult: String,
    val credentialResult: String,
    val collectorReportResult: String,
    val pausedReauth: Boolean,
    val disconnectIncomplete: Boolean,
    val gaps: List<UnobservedGap>,
    val bootNote: String,
    val deviceIdShort: String,
)

data class UiState(
    val phase: Phase = Phase.SETUP,
    val apiBaseUrl: String = "",
    val siteUrl: String = "",
    val parkedAck: Boolean = false,
    val consentAck: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pairing: PairingUi? = null,
    val diag: Diagnostics? = null,
    /** Present only while [phase] is [Phase.MOCK] (the fixed-record showcase); discarded on exit. */
    val mock: ShowcaseUi? = null,
    /** Phase to return to when the mock demo exits (SETUP when unpaired, PAIRED when a credential exists). */
    val mockReturn: Phase = Phase.SETUP,
    /** Present only while [phase] is [Phase.COPILOT]; discarded on exit. [mockReturn] is where it returns to. */
    val copilot: CopilotUi? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
    private val _ui = MutableStateFlow(
        UiState(apiBaseUrl = g.settings.apiBaseUrl, siteUrl = g.settings.siteUrl),
    )
    val ui: StateFlow<UiState> = _ui

    private var pairingJob: Job? = null

    init {
        viewModelScope.launch {
            // Resume an unexpired pairing that was in flight when the app was closed.
            val pending = g.vault.loadPending()
            if (g.vault.loadCredential() == null && pending != null && pending.expiresAtMs > System.currentTimeMillis()) {
                beginPolling(pending)
            }
            while (true) {
                refresh()
                delay(2_000)
            }
        }
        // Speech and navigation outcomes are shown next to their controls, so a silent failure is visible.
        viewModelScope.launch { g.voice.state.collect { v -> updateCopilot { it.copy(speech = v) } } }
        viewModelScope.launch { g.voice.error.collect { v -> updateCopilot { it.copy(speechError = v) } } }
        viewModelScope.launch { g.ear.state.collect { v -> updateCopilot { it.copy(ear = v) } } }
        viewModelScope.launch { g.ear.error.collect { v -> updateCopilot { it.copy(earError = v) } } }
        viewModelScope.launch { g.ear.heard.collect { v -> updateCopilot { it.copy(heard = v) } } }
    }

    fun onConfig(api: String, site: String) = _ui.update { it.copy(apiBaseUrl = api, siteUrl = site, message = null) }
    fun onParked(v: Boolean) = _ui.update { it.copy(parkedAck = v) }
    fun onConsent(v: Boolean) = _ui.update { it.copy(consentAck = v) }

    fun startPairing() {
        val s = _ui.value
        if (!s.parkedAck || !s.consentAck) return
        g.settings.apiBaseUrl = s.apiBaseUrl
        g.settings.siteUrl = s.siteUrl
        if (g.settings.apiConfig() == null) {
            _ui.update { it.copy(message = text(R.string.msg_invalid_urls)) }
            return
        }
        // A claimed pairing whose credential could not be stored is resumed (the server answers the same secret
        // again) instead of being replaced by a new pairing.
        val pending = g.vault.loadPending()
        if (g.vault.loadCredential() == null && pending != null && pending.expiresAtMs > System.currentTimeMillis()) {
            beginPolling(pending)
            return
        }
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            g.http.start()
                .onSuccess { start ->
                    // Without a durably stored pairing secret a claimed credential could not be recovered.
                    if (!g.vault.savePending(start)) {
                        _ui.update { it.copy(busy = false, message = text(R.string.msg_pairing_store_failed, STORAGE_FAILED)) }
                        return@onSuccess
                    }
                    g.settings.consentAtMs = System.currentTimeMillis()
                    beginPolling(start)
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, message = text(R.string.msg_pairing_start_failed, e.message ?: text(R.string.network))) }
                }
        }
    }

    private fun beginPolling(start: PairingStart) {
        val cfg = g.settings.apiConfig() ?: return
        _ui.update { it.showingPairing(PairingUi(start.code, cfg.pairingLink(start.code), Qr.matrix(cfg.pairingLink(start.code)), start.expiresAtMs)) }
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            when (val end = PairingPoller(g.http).run(start)) {
                is PairingEnd.Claimed -> {
                    // Atomic w.r.t. uploads: the credential is durably published, then rows of any other device are purged.
                    val commit = commitPairing(g.lifecycle, g.outbox, end.credential, onPublished = {
                        val pairingCleared = g.vault.clearPending()
                        g.settings.pausedReauth = false // a fresh credential starts unpaused, atomically with publishing it
                        g.settings.setDisconnectIncomplete(false) && pairingCleared
                    }) { g.vault.saveCredential(it) }
                    if (commit is PairingCommit.StorageFailed) {
                        // Nothing was published (the failed write is rolled back), the previous queue is intact and the
                        // stored pairing secret is kept, so "Start pairing" resumes this claim.
                        _ui.update {
                            it.copy(phase = Phase.SETUP, pairing = null, message = text(R.string.msg_credential_store_failed, STORAGE_FAILED))
                        }
                        return@launch
                    }
                    val cleanupNote = if ((commit as PairingCommit.Published).cleanupSaved) null
                        else text(R.string.msg_cleanup_failed, STORAGE_FAILED)
                    g.settings.collectionWanted = true
                    UploadScheduler.ensurePeriodic(getApplication<Application>())
                    val failure = Collector.start(getApplication<Application>())
                    _ui.update { it.copy(phase = Phase.PAIRED, pairing = null, message = failure?.let { f -> text(R.string.msg_collection_start_failed_after_pairing, f) } ?: cleanupNote) }
                }
                PairingEnd.Expired -> {
                    g.vault.clearPending()
                    g.settings.consentAtMs = 0
                    _ui.update { it.copy(phase = Phase.SETUP, pairing = null, message = text(R.string.msg_code_expired)) }
                }
                is PairingEnd.Failed -> {
                    g.vault.clearPending()
                    g.settings.consentAtMs = 0
                    _ui.update { it.copy(phase = Phase.SETUP, pairing = null, message = text(R.string.msg_pairing_failed, end.reason)) }
                }
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        g.vault.clearPending()
        g.settings.consentAtMs = 0
        _ui.update { it.copy(phase = Phase.SETUP, pairing = null, message = null) }
    }

    fun startCollection() {
        g.settings.collectionWanted = true
        val f = Collector.start(getApplication<Application>())
        _ui.update { it.copy(message = f?.let { r -> text(R.string.msg_collection_start_failed, r) }) }
    }

    /**
     * Foreground-only recovery, called from the Activity's onStart (a user-initiated launch, which Android allows to
     * start a foreground service). After a force-stop or process loss the collector is gone but the user's intent
     * (`collectionWanted`) persists; resume it here, and only if every guard still holds: a credential exists, consent
     * was given, the user has not stopped/disconnected, and the server has not rejected the credential. It never
     * changes the stored intent, so explicit Stop and Disconnect stay in force, and it is never called from
     * Application/background code.
     */
    fun resumeIfWanted() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val s = g.settings
            if (!s.collectionWanted || s.consentAtMs <= 0L || s.pausedReauth || s.disconnectIncomplete) return@launch
            if (g.vault.loadCredential() == null) return@launch
            if (g.collectorState.value == "running") return@launch
            val failure = Collector.start(getApplication<Application>())
            if (failure != null) _ui.update { it.copy(message = text(R.string.msg_resume_failed, failure)) }
        }
    }

    fun stopCollection() = Collector.stop(getApplication<Application>())

    // ---- Showcase (fixed competition records) ----------------------------------------------------
    // Phase.MOCK now shows the bundled fixed public record set. The state lives only in this ViewModel
    // (a tab and a reset counter). It never touches settings, the vault, Room, the collector or the
    // uploader, and no record is generated from any action.

    fun enterMock() = _ui.update { it.enteringMock() }

    fun exitMock() = _ui.update { if (it.phase == Phase.MOCK) it.copy(phase = it.mockReturn, mock = null) else it }

    private inline fun updateMock(crossinline f: (ShowcaseUi) -> ShowcaseUi) = _ui.update { s ->
        val m = s.mock
        if (s.phase != Phase.MOCK || m == null) s else s.copy(mock = f(m))
    }

    fun showcaseTab(tab: ShowcaseTab) = updateMock { it.copy(tab = tab) }

    /** Back to the fixed status tab; the reset counter proves repeats. Nothing else exists to clear. */
    fun mockReset() = updateMock { it.reset() }
    /** Show or hide the limitations detail under the controls; a tab change keeps it, Reset collapses it. */
    fun showcaseLimits() = updateMock { it.toggleLimits() }

    // ---- Copilot (0.5.0) ---------------------------------------------------------------------------
    // Presentation state only. Speech goes through the Pleos TextToSpeech / SpeechToText SDKs on the
    // device, navigation hand-offs through NaviHelper. No vehicle command, location read or upload exists.

    private val tripTracker = TripTracker()
    private var liveTrip: TripRecap? = null
    private val korean: Boolean get() = Locale.getDefault().language == "ko"
    private val speechLocale: Locale get() = if (korean) Locale.KOREA else Locale.US
    private fun hourNow(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun copilotContext(signals: Map<Signal, SignalInfo>?) = CopilotModel.context(signals, liveTrip, hourNow())

    private inline fun updateCopilot(crossinline f: (CopilotUi) -> CopilotUi) = _ui.update { s ->
        val c = s.copilot
        if (s.phase != Phase.COPILOT || c == null) s else s.copy(copilot = f(c))
    }

    fun enterCopilot() = _ui.update { it.enteringCopilot(copilotContext(it.diag?.signals)) }

    fun exitCopilot() {
        g.voice.stop()
        g.ear.stop()
        _ui.update { if (it.phase == Phase.COPILOT) it.copy(phase = it.mockReturn, copilot = null) else it }
    }

    fun copilotTab(tab: CopilotTab) = updateCopilot { it.copy(tab = tab) }

    fun copilotSay(text: Say) = g.voice.speak(Copilot.spoken(text.text(korean), korean), speechLocale)
    fun copilotHush() = g.voice.stop()

    /** A tapped or recognised question: answered from the context only, shown and spoken. */
    fun copilotAsk(question: String) {
        val c = _ui.value.copilot ?: return
        val answer = Copilot.answer(question, c.context)
        updateCopilot { it.asked(Turn(question, answer.text, answer.intent)) }
        copilotSay(answer.text)
        if (answer.intent == Intent.CHARGERS) copilotChargers()
    }

    fun copilotListen() {
        g.voice.stop()
        g.ear.listen(speechLocale) { heard -> viewModelScope.launch { copilotAsk(heard) } }
    }
    fun copilotStopListening() = g.ear.stop()

    fun copilotToggle(ruleId: String) = updateCopilot { it.toggled(ruleId) }

    fun copilotRun(rule: Rule) {
        val c = _ui.value.copilot ?: return
        val result = Automations.simulate(rule, c.context)
        updateCopilot { it.copy(ran = it.ran + (rule.id to result)) }
        copilotSay(result)
    }

    fun copilotChargers() {
        updateCopilot { it.copy(chargers = NaviOutcome.Waiting) }
        g.navi.showNearbyChargers { outcome -> updateCopilot { it.copy(chargers = outcome) } }
    }

    fun copilotDestination() {
        updateCopilot { it.copy(destination = NaviOutcome.Waiting) }
        g.navi.readDestination { outcome ->
            updateCopilot { it.copy(destination = outcome) }
            val c = _ui.value.copilot
            if (outcome is NaviOutcome.Destination && c != null) copilotSay(Copilot.destinationLine(c.context.now, outcome.name, outcome.remainingKm))
        }
    }

    /** Feeds the on-device trip tracker; a finished trip becomes the arrival report and is spoken if the copilot is open. */
    private fun trackTrip(signals: Map<Signal, SignalInfo>) {
        val (motion, odometer, soc) = CopilotModel.trackerInput(signals)
        val recap = tripTracker.onReading(System.currentTimeMillis(), motion, odometer, soc)
        if (recap != null) {
            liveTrip = recap
            if (_ui.value.phase == Phase.COPILOT) copilotSay(Copilot.arrival(recap))
            return
        }
        // The low-battery prompt is spoken once each time this car's charge falls to the threshold, also while
        // Pleos covers the screen during driving.
        val low = CopilotModel.liveNow(signals)?.let(Copilot::lowBattery)
        if (low == null) lowAnnounced = false
        else if (!lowAnnounced && _ui.value.phase == Phase.COPILOT) { lowAnnounced = true; copilotSay(low) }
    }
    private var lowAnnounced = false

    fun retryUpload() = UploadScheduler.kick(getApplication<Application>())

    /**
     * Explicit disconnect: stops collection, cancels uploads, deletes the credential and every queued
     * (un-uploaded) observation from this device. Server-side invalidation is done by the owner in the
     * DANIM web app (POST /vehicles/:id/disconnect is first-party only).
     */
    fun disconnect() {
        pairingJob?.cancel()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = DisconnectCoordinator(
                lock = g.lifecycle,
                // Before anything else: durably drop collection intent/consent and raise the fence.
                fenceDurably = { g.settings.beginDisconnect() },
                cancelUploads = { UploadScheduler.cancelAll(app) },
                // Waits for the collector's final drain, so nothing can be inserted after the clear below.
                stopCollectorAndJoin = { Collector.stopAndJoin(app) },
                clearLocal = {
                    g.outbox.clear()
                    g.db.sessions().clearSessions()
                    g.db.sessions().clearGaps()
                    val credentialRemoved = g.vault.clearCredential()
                    val pairingRemoved = g.vault.clearPending()
                    g.signalBoard.value = emptyMap()
                    credentialRemoved && pairingRemoved
                },
                finish = { g.settings.finishDisconnect() },
            ).disconnect()
            val message = when {
                result.complete -> null
                result.removed -> text(R.string.msg_disconnect_settings_failed, STORAGE_FAILED)
                result.fenced -> text(R.string.msg_disconnect_fenced, STORAGE_FAILED)
                else -> text(R.string.msg_disconnect_unfenced, STORAGE_FAILED)
            }
            if (result.removed) {
                _ui.update { UiState(apiBaseUrl = g.settings.apiBaseUrl, siteUrl = g.settings.siteUrl, message = message ?: text(R.string.msg_disconnected)) }
            } else {
                _ui.update { it.copy(message = message) }
            }
        }
    }

    private suspend fun refresh() {
        val cred = g.vault.loadCredential()
        val s = g.settings
        if (cred == null) {
            _ui.update {
                when (it.phase) {
                    Phase.PAIRED -> it.copy(phase = Phase.SETUP, diag = null)
                    // A mock or copilot session keeps running; it only needs to know where to return.
                    Phase.MOCK -> if (it.mockReturn == Phase.PAIRED) it.copy(mockReturn = Phase.SETUP, diag = null) else it
                    Phase.COPILOT -> it.copy(mockReturn = Phase.SETUP, diag = null, copilot = it.copilot?.copy(context = copilotContext(null)))
                    else -> it
                }
            }
            return
        }
        val diag = Diagnostics(
            collectorState = g.collectorState.value,
            sdkState = g.sdkState.value,
            signals = g.signalBoard.value,
            queued = g.outbox.count(cred.deviceId),
            oldestQueuedMs = g.outbox.oldestObservedAtMs(cred.deviceId),
            acked = s.ackedTotal,
            expired = s.expiredTotal,
            quarantined = s.quarantinedTotal,
            lostOnShutdown = s.lostOnShutdownTotal,
            lastUploadAtMs = s.lastUploadAtMs,
            lastUploadResult = s.lastUploadResult,
            credentialResult = s.lastCredentialResult,
            collectorReportResult = s.lastCollectorReportResult,
            pausedReauth = s.pausedReauth,
            disconnectIncomplete = s.disconnectIncomplete,
            gaps = g.sessions.recentGaps(5),
            bootNote = s.lastBootNote,
            deviceIdShort = cred.deviceId.take(8),
        )
        trackTrip(diag.signals)
        _ui.update {
            when (it.phase) {
                Phase.PAIRING -> it.copy(diag = diag)
                Phase.MOCK -> it.copy(diag = diag, mockReturn = Phase.PAIRED)
                Phase.COPILOT -> it.copy(diag = diag, mockReturn = Phase.PAIRED, copilot = it.copilot?.copy(context = copilotContext(diag.signals)))
                else -> it.copy(phase = Phase.PAIRED, diag = diag)
            }
        }
    }
}

private const val STORAGE_FAILED = "storage_write_failed"

// ---- Pure state transitions (unit-tested in MockEntryTest) --------------------------------------

/** The pairing start answered: show the code. */
internal fun UiState.showingPairing(p: PairingUi): UiState = copy(busy = false, phase = Phase.PAIRING, pairing = p)

/**
 * [MainViewModel.enterMock]: open the labelled fixed-record showcase. Entry is refused while a pairing
 * start is in flight ([UiState.busy], set by `startPairing` before the request and cleared by its
 * answer) and while a pairing code is showing, so a late `POST /pairing/start` answer can neither land
 * on top of the showcase nor be hidden by it; the entry button is disabled in the same states. A
 * showcase that is already open is kept, not reset.
 */
internal fun UiState.enteringMock(): UiState = when {
    busy || phase == Phase.PAIRING || phase == Phase.MOCK || phase == Phase.COPILOT -> this
    else -> copy(phase = Phase.MOCK, mock = ShowcaseUi(), mockReturn = if (phase == Phase.PAIRED) Phase.PAIRED else Phase.SETUP, message = null)
}

/** [MainViewModel.enterCopilot]: open the copilot under the same entry rules as the showcase. */
internal fun UiState.enteringCopilot(context: CopilotContext): UiState = when {
    busy || phase == Phase.PAIRING || phase == Phase.MOCK || phase == Phase.COPILOT -> this
    else -> copy(phase = Phase.COPILOT, copilot = CopilotUi(context), mockReturn = if (phase == Phase.PAIRED) Phase.PAIRED else Phase.SETUP, message = null)
}
