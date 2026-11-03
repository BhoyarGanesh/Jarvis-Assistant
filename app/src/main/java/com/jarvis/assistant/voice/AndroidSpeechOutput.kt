package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.assistant.domain.Chime
import com.jarvis.assistant.domain.SpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mouth: MouthLevel,
) : SpeechOutput {

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private fun ensureEngine(): CompletableDeferred<Boolean> {
        ready?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        tts = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = mouth.talking()
                override fun onDone(utteranceId: String) = finish(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = finish(utteranceId)

                override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
            })
        }
        return deferred
    }

    override suspend fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        val ok = withContext(Dispatchers.Main.immediate) { ensureEngine() }.let { withTimeoutOrNull(INIT_TIMEOUT_MS) { it.await() } }
        val engine = tts
        if (ok != true || engine == null) {
            // Engine missing or broken: forget it so the next call retries.
            ready = null
            return
        }
        val locale = Locale.forLanguageTag(languageTag)
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        // Upper bound in case the engine never reports completion.
        withTimeoutOrNull(5_000L + text.length * 120L) { done.await() }
    }

    private fun finish(utteranceId: String) {
        mouth.silent()
        pending.remove(utteranceId)?.complete(Unit)
    }

    override fun stop() {
        mouth.silent()
        tts?.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 4_000L
    }
}

@Singleton
class ToneChime @Inject constructor() : Chime {
    override suspend fun play() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull() ?: return
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
            delay(220)
        } finally {
            tone.release()
        }
    }
}
