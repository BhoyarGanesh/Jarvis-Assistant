package com.jarvis.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.jarvis.assistant.data.remote.BackendClient
import com.jarvis.assistant.data.remote.SpeechAudio
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.AppSettings
import com.jarvis.assistant.domain.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.BufferedSource
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks with the backend's Gemini voice, streamed and played as it arrives, and feeds the
 * loudness of what is playing to [MouthLevel] for lip sync. Falls back to the phone's own
 * text-to-speech when the phone voice is chosen or the cloud voice is unavailable (offline,
 * free-tier quota used up). Audio is never written to disk.
 */
@Singleton
class CloudSpeechOutput @Inject constructor(
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val phone: AndroidSpeechOutput,
    private val mouth: MouthLevel,
) : SpeechOutput {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    override suspend fun speak(text: String, languageTag: String) {
        if (text.isBlank()) return
        val s = settings.current()
        if (s.voiceName == AppSettings.PHONE_VOICE || !s.backendConfigured) {
            phone.speak(text, languageTag)
            return
        }
        stopped = false
        val played = when (val audio = backend.openSpeech(text, s.voiceName)) {
            is SpeechAudio.Stream -> audio.body.use { play(it.source(), audio.sampleRate) }
            is SpeechAudio.Unavailable -> {
                Log.i(TAG, "cloud voice unavailable (${audio.reason}), using the phone voice")
                false
            }
        }
        if (!played && !stopped) phone.speak(text, languageTag)
    }

    /** Returns false if nothing could be played, so the caller can fall back. */
    private suspend fun play(source: BufferedSource, sampleRate: Int): Boolean = withContext(Dispatchers.IO) {
        val output = createTrack(sampleRate) ?: return@withContext false
        track = output
        val meter = PcmMeter(frameSamples = sampleRate / FRAMES_PER_SECOND)
        try {
            coroutineScope {
                // Looks up the loudness of the sample the speaker is playing now, ~60 times a second.
                val lipSync = launch {
                    while (isActive) {
                        mouth.level(meter.levelAt(output.playbackHeadPosition.toLong()))
                        delay(16)
                    }
                }
                val buffer = ByteArray(4_096)
                var pendingByte = false
                var carryByte: Byte = 0
                output.play()
                try {
                    while (!stopped) {
                        ensureActive()
                        val read = source.read(buffer)
                        if (read == -1) break
                        meter.feed(buffer, read)
                        // AudioTrack needs whole samples; hold back an odd trailing byte.
                        val start = if (pendingByte) 1 else 0
                        if (pendingByte) output.write(byteArrayOf(carryByte, buffer[0]), 0, 2)
                        val usable = read - start
                        val even = usable - usable % 2
                        if (even > 0) output.write(buffer, start, even)
                        pendingByte = usable % 2 == 1
                        if (pendingByte) carryByte = buffer[read - 1]
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "voice stream broke: ${e.message}")
                }
                meter.finish()
                // Let the speaker finish what was written before stopping.
                val deadline = System.currentTimeMillis() + 2_000
                while (!stopped && output.playbackHeadPosition < meter.samples && System.currentTimeMillis() < deadline) {
                    delay(20)
                }
                lipSync.cancel()
            }
            meter.samples > 0
        } finally {
            withContext(NonCancellable) {
                track = null
                runCatching { output.stop() }
                output.release()
                mouth.silent()
            }
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack? = runCatching {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // ~200 ms of audio: small enough that lip sync and "stop" feel instant.
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2 / 5))
            .build()
            .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
    }.onFailure { Log.w(TAG, "AudioTrack unavailable", it) }.getOrNull()

    override fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
        mouth.silent()
        phone.stop()
    }

    private companion object {
        const val TAG = "CloudSpeech"
        const val FRAMES_PER_SECOND = 50
    }
}
