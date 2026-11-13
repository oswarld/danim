package app.danim.automotive.voice

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the car's speech output is doing; shown next to every "listen" control so a silent failure is visible. */
enum class SpeechState { IDLE, PREPARING, SPEAKING, UNAVAILABLE }

/**
 * Spoken output through the Pleos TextToSpeech SDK (CAAS speech service) in on-device mode: no app
 * registration, no client secret and no network. Every failure ends in [SpeechState.UNAVAILABLE] with the
 * SDK's own error name; the text being spoken is always on screen as well, so speech is never the only channel.
 */
class PleosVoice(private val context: Context) {
    private val _state = MutableStateFlow(SpeechState.IDLE)
    val state: StateFlow<SpeechState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() { _state.value = SpeechState.SPEAKING }
        override fun onDone() { _state.value = SpeechState.IDLE }
        override fun onStop() { _state.value = SpeechState.IDLE }
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(error: ErrorType) = fail(error.name)
    }

    private fun fail(reason: String) {
        Log.w(TAG, "tts unavailable: $reason")
        _error.value = reason
        _state.value = SpeechState.UNAVAILABLE
    }

    @Synchronized
    fun speak(text: String, locale: Locale) {
        if (text.isBlank()) return
        _error.value = null
        _state.value = SpeechState.PREPARING
        try {
            val engine = tts ?: TextToSpeech(context.applicationContext, Mode.ON_DEVICE).also { created ->
                tts = created
                // The SDK binds its service in initialize(); listeners can only be registered after that.
                created.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        try { created.addEventListener(events) } catch (e: Throwable) { fail(e.javaClass.simpleName); return }
                        ready = true
                        val queued = synchronized(this@PleosVoice) { pending.also { pending = null } }
                        queued?.let { (t, l) -> utter(t, l) }
                    }
                    override fun onFailure(error: ErrorType) = fail(error.name)
                })
            }
            if (ready) utter(text, locale) else pending = text to locale
            check(engine === tts)
        } catch (e: Throwable) {
            fail(e.javaClass.simpleName)
        }
    }

    private fun utter(text: String, locale: Locale) {
        try {
            tts?.setLocale(locale)
            tts?.speak(text, true)
        } catch (e: Throwable) {
            fail(e.javaClass.simpleName)
        }
    }

    @Synchronized
    fun stop() {
        pending = null
        try { tts?.stop() } catch (_: Throwable) {}
        if (_state.value != SpeechState.UNAVAILABLE) _state.value = SpeechState.IDLE
    }

    @Synchronized
    fun release() {
        try { tts?.removeEventListener(events); tts?.release() } catch (_: Throwable) {}
        tts = null; ready = false; pending = null
        _state.value = SpeechState.IDLE
    }

    private companion object { const val TAG = "DanimVoice" }
}
