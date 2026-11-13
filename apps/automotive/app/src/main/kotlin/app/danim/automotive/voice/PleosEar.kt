package app.danim.automotive.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** NOTHING_HEARD: the microphone opened but no speech arrived within [PleosEar.LISTEN_TIMEOUT_MS]. */
enum class EarState { IDLE, PREPARING, LISTENING, NOTHING_HEARD, UNAVAILABLE }

/**
 * Voice input through the Pleos SpeechToText SDK (CAAS speech service) in on-device mode: no app
 * registration, no client secret and no network. DANIM keeps no audio; it receives recognised text only.
 * A failure ends in [EarState.UNAVAILABLE] with the SDK's own error name, and every question can also be tapped.
 */
class PleosEar(private val context: Context) {
    private val _state = MutableStateFlow(EarState.IDLE)
    val state: StateFlow<EarState> = _state
    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var stt: SpeechToText? = null
    private var ready = false
    private var onFinal: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** The on-device recogniser keeps the microphone open in silence, so an unanswered request is closed here. */
    private val timeout = Runnable {
        if (_state.value == EarState.LISTENING || _state.value == EarState.PREPARING) {
            stop()
            _state.value = EarState.NOTHING_HEARD
        }
    }

    private val results = object : ResultListener {
        override fun onReady() { _state.value = EarState.LISTENING }
        override fun onStartedRecognition() { _state.value = EarState.LISTENING }
        override fun onUpdated(text: String, isFinal: Boolean) {
            _heard.value = text
            if (isFinal) {
                main.removeCallbacks(timeout)
                _state.value = EarState.IDLE
                val deliver = synchronized(this@PleosEar) { onFinal.also { onFinal = null } }
                if (text.isNotBlank()) deliver?.invoke(text)
            }
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}
        override fun onEndedRecognition() { if (_state.value == EarState.LISTENING) _state.value = EarState.IDLE }
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(error: ErrorType) = fail(error.name)
    }

    private fun fail(reason: String) {
        main.removeCallbacks(timeout)
        Log.w(TAG, "stt unavailable: $reason")
        _error.value = reason
        _state.value = EarState.UNAVAILABLE
    }

    @Synchronized
    fun listen(locale: Locale, onFinal: (String) -> Unit) {
        this.onFinal = onFinal
        _error.value = null
        _heard.value = ""
        _state.value = EarState.PREPARING
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS)
        try {
            if (stt == null) {
                val created = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
                stt = created
                // The SDK binds its service in initialize(); listeners can only be registered after that.
                created.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        try { created.addListener(results) } catch (e: Throwable) { fail(e.javaClass.simpleName); return }
                        ready = true
                        request(locale)
                    }
                    override fun onFailure(error: ErrorType) = fail(error.name)
                })
            } else if (ready) request(locale)
        } catch (e: Throwable) {
            fail(e.javaClass.simpleName)
        }
    }

    private fun request(locale: Locale) {
        try {
            stt?.setLocale(locale)
            stt?.request()
        } catch (e: Throwable) {
            fail(e.javaClass.simpleName)
        }
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(timeout)
        onFinal = null
        try { stt?.stop() } catch (_: Throwable) {}
        if (_state.value != EarState.UNAVAILABLE) _state.value = EarState.IDLE
    }

    companion object {
        private const val TAG = "DanimEar"
        const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
