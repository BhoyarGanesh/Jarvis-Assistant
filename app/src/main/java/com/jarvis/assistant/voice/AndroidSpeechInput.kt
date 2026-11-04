package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.assistant.domain.SpeechInput
import com.jarvis.assistant.domain.SpeechResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Command speech-to-text via the platform SpeechRecognizer, preferring on-device recognition. */
@Singleton
class AndroidSpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechInput {

    private var active: SpeechRecognizer? = null

    private sealed interface Attempt {
        data class Done(val result: SpeechResult) : Attempt
        data object LanguageUnavailable : Attempt
    }

    override suspend fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        startTimeoutMs: Long,
    ): SpeechResult {
        if (startTimeoutMs <= 0) return listenOnce(languageTag, onPartial, onLevel, 0)
        // The recognizer gives up on silence after ~5 s on its own; keep listening for the whole window.
        val deadline = SystemClock.elapsedRealtime() + startTimeoutMs
        while (true) {
            speechBegan = false
            val result = listenOnce(languageTag, onPartial, onLevel, deadline - SystemClock.elapsedRealtime())
            val left = deadline - SystemClock.elapsedRealtime()
            if (result != SpeechResult.NoMatch || speechBegan || left < MIN_RETRY_MS) return result
        }
    }

    @Volatile private var speechBegan = false

    private suspend fun listenOnce(
        languageTag: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        startTimeoutMs: Long,
    ): SpeechResult = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext SpeechResult.Error("I need microphone permission to hear you.", permissionMissing = true)
        }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext SpeechResult.Error("Speech recognition isn't available on this phone. Install or enable the Google app.")
        }

        if (onDevice) {
            when (val first = attempt(onDevice = true, languageTag, onPartial, onLevel, startTimeoutMs)) {
                is Attempt.Done -> return@withContext first.result
                // On-device pack for this language isn't installed; fall back to the regular recognizer.
                Attempt.LanguageUnavailable -> Unit
            }
        }
        when (val second = attempt(onDevice = false, languageTag, onPartial, onLevel, startTimeoutMs)) {
            is Attempt.Done -> second.result
            Attempt.LanguageUnavailable -> SpeechResult.Error("Speech recognition for this language isn't available.")
        }
    }

    override fun cancel() {
        active?.let {
            it.cancel()
            it.destroy()
        }
        active = null
    }

    private suspend fun attempt(
        onDevice: Boolean,
        languageTag: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        startTimeoutMs: Long,
    ): Attempt = suspendCancellableCoroutine { cont ->
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        active = recognizer
        val handler = Handler(Looper.getMainLooper())

        fun finish(attempt: Attempt) {
            handler.removeCallbacksAndMessages(null)
            if (!cont.isActive) return
            recognizer.destroy()
            if (active === recognizer) active = null
            onLevel(0f)
            cont.resume(attempt)
        }

        fun began() {
            speechBegan = true
            handler.removeCallbacksAndMessages(null)
        }

        // Nobody spoke in time: stop quietly (a follow-up window ending).
        if (startTimeoutMs > 0) {
            handler.postDelayed({
                recognizer.cancel()
                finish(Attempt.Done(SpeechResult.NoMatch))
            }, startTimeoutMs)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = began()
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // RMS dB arrives roughly in -2..10.
                onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstResult()?.takeIf { it.isNotBlank() }?.let {
                    began()
                    onPartial(it)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstResult()?.trim().orEmpty()
                finish(Attempt.Done(if (text.isEmpty()) SpeechResult.NoMatch else SpeechResult.Text(text)))
            }

            override fun onError(error: Int) {
                finish(mapError(error, onDevice))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            recognizer.cancel()
            recognizer.destroy()
            if (active === recognizer) active = null
        }
    }

    private fun mapError(error: Int, onDevice: Boolean): Attempt = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT ->
            Attempt.Done(SpeechResult.NoMatch)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Attempt.Done(SpeechResult.Error("I need microphone permission to hear you.", permissionMissing = true))
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            if (onDevice) Attempt.LanguageUnavailable
            else Attempt.Done(SpeechResult.Error("Speech recognition for this language isn't available."))
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            Attempt.Done(SpeechResult.Error("Speech recognition needs a network connection right now."))
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            Attempt.Done(SpeechResult.Error("The speech recognizer is busy. Please try again."))
        SpeechRecognizer.ERROR_AUDIO ->
            Attempt.Done(SpeechResult.Error("I couldn't access the microphone."))
        else -> if (onDevice) Attempt.LanguageUnavailable else Attempt.Done(SpeechResult.Error("Speech recognition failed."))
    }

    private companion object {
        /** A recognizer restart shorter than this would hardly hear anything. */
        const val MIN_RETRY_MS = 1_500L
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
