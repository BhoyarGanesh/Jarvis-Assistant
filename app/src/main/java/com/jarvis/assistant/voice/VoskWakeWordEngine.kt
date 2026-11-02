package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.domain.wakeword.WakeWordConfig
import com.jarvis.assistant.domain.wakeword.WakeWordDetector
import com.jarvis.assistant.domain.wakeword.WakeWordEngine
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline wake word detection with Vosk and a two-entry grammar (["jarvis", "[unk]"]), which keeps
 * CPU low and makes everything that isn't "jarvis" collapse into [unk].
 * All Vosk calls happen on the main thread (its callbacks are delivered there too).
 */
@Singleton
class VoskWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : WakeWordEngine {

    private val _state = MutableStateFlow<WakeWordEngineState>(WakeWordEngineState.Stopped)
    override val state: StateFlow<WakeWordEngineState> = _state.asStateFlow()

    private val _detections = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val detections: Flow<Unit> = _detections.asSharedFlow()

    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var detector = WakeWordDetector()
    private var requested = false
    private var paused = false
    private var loading = false

    override fun start(config: WakeWordConfig) {
        detector = WakeWordDetector(sensitivity = config.sensitivity, debounceMs = config.debounceMs)
        requested = true
        if (paused) return
        if (service != null) return
        val loaded = model
        if (loaded != null) listen(loaded) else loadModel()
    }

    override fun stop() {
        requested = false
        release()
        _state.value = WakeWordEngineState.Stopped
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        paused = true
        if (service != null) {
            release()
            // Give the platform a moment to hand the microphone to the next recorder.
            delay(MIC_HANDOFF_MS)
        }
        if (requested) _state.value = WakeWordEngineState.Paused
    }

    override fun resume() {
        paused = false
        if (!requested) return
        val loaded = model
        if (loaded != null) listen(loaded) else loadModel()
    }

    private fun loadModel() {
        if (loading) return
        loading = true
        _state.value = WakeWordEngineState.Loading
        try {
            StorageService.unpack(
                context, MODEL_ASSET_DIR, "model",
                { m ->
                    loading = false
                    model = m
                    if (requested && !paused) listen(m)
                },
                { e ->
                    loading = false
                    Log.e(TAG, "Vosk model unpack failed", e)
                    _state.value = WakeWordEngineState.Unavailable(MODEL_MISSING)
                },
            )
        } catch (e: IOException) {
            loading = false
            Log.e(TAG, "Vosk model missing", e)
            _state.value = WakeWordEngineState.Unavailable(MODEL_MISSING)
        }
    }

    private fun listen(model: Model) {
        if (service != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = WakeWordEngineState.Unavailable("Microphone permission is required for the wake word.")
            return
        }
        try {
            val rec = Recognizer(model, SAMPLE_RATE, WakeWordDetector.GRAMMAR).apply { setWords(true) }
            recognizer = rec
            service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(listener) }
            _state.value = WakeWordEngineState.Listening
        } catch (e: IOException) {
            Log.e(TAG, "Could not start Vosk", e)
            release()
            _state.value = WakeWordEngineState.Unavailable("The microphone is busy. Wake word paused.")
        }
    }

    private fun release() {
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        recognizer?.close()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (hypothesis != null && detector.onPartial(hypothesis)) fire()
        }

        override fun onResult(hypothesis: String?) {
            if (hypothesis != null && detector.onResult(hypothesis)) fire()
        }

        override fun onFinalResult(hypothesis: String?) {
            if (hypothesis != null && detector.onResult(hypothesis)) fire()
        }

        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            release()
            _state.value = WakeWordEngineState.Unavailable("Wake word stopped: ${exception?.message ?: "audio error"}")
        }

        override fun onTimeout() {
            release()
            if (requested && !paused) model?.let(::listen)
        }
    }

    private fun fire() {
        // Reset so the rest of this utterance doesn't re-trigger.
        recognizer?.reset()
        _detections.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "VoskWakeWord"
        private const val SAMPLE_RATE = 16_000f
        private const val MIC_HANDOFF_MS = 150L
        const val MODEL_ASSET_DIR = "model-en-us"
        const val MODEL_MISSING =
            "Wake word model not found. Run scripts/download_vosk_model and rebuild. Push-to-talk still works."
    }
}
