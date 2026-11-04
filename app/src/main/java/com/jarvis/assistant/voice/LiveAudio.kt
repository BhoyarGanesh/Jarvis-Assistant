package com.jarvis.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "JarvisLive"

/**
 * The microphone for Live mode: 16-bit mono PCM in 40 ms chunks from the voice-call source, with the
 * phone's echo canceller on, so her own voice coming out of the speaker isn't heard as the user
 * talking over her.
 */
class LiveMicrophone(
    private val sampleRate: Int,
    private val onLevel: (Float) -> Unit,
    private val onChunk: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    // RECORD_AUDIO is checked in GeminiLiveVoice.start before this runs.
    @SuppressLint("MissingPermission")
    fun start(): Boolean = runCatching {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate * 2 / 5),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        record = r
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        }
        Log.i(TAG, "mic: echo canceller ${echoCanceler?.enabled ?: "unavailable"}, noise suppressor ${noiseSuppressor?.enabled ?: "unavailable"}")
        r.startRecording()
        running = true
        thread = Thread({ loop(r) }, "JarvisLiveMic").apply { start() }
        true
    }.onFailure { Log.w(TAG, "microphone unavailable", it) }.getOrDefault(false)

    private fun loop(r: AudioRecord) {
        val buffer = ByteArray(sampleRate / CHUNKS_PER_SECOND * 2)
        while (running) {
            val read = r.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (read < 0) break
                continue
            }
            onLevel(levelOf(buffer, read))
            onChunk(buffer.copyOf(read))
        }
        onLevel(0f)
    }

    fun release() {
        running = false
        runCatching { record?.stop() }
        thread?.join(300)
        echoCanceler?.release()
        noiseSuppressor?.release()
        record?.release()
        record = null
        thread = null
    }

    private companion object {
        const val CHUNKS_PER_SECOND = 25

        /** Rough 0..1 loudness for the glow. */
        fun levelOf(bytes: ByteArray, length: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val rms = sqrt(sum / maxOf(1, length / 2))
            return ((rms - 300) / 4_000).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * Plays her streamed voice, drives the avatar's mouth from what is actually coming out of the speaker
 * (like [CloudSpeechOutput]), reports when she starts and stops talking, and can be cut off instantly.
 */
class LivePlayer(
    private val sampleRate: Int,
    private val mouth: MouthLevel,
    private val onSpeaking: (Boolean) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    private var monitor: Thread? = null

    // Guarded by lock: the meter for the audio since the last silence, and the head position it starts at.
    private var meter = newMeter()
    private var base = 0L

    private fun newMeter() = PcmMeter(frameSamples = sampleRate / 50)

    fun start(): Boolean {
        val t = runCatching {
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // The voice-call stream is what the echo canceller listens to.
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2 / 4))
                .build()
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        }.onFailure { Log.w(TAG, "AudioTrack unavailable", it) }.getOrNull() ?: return false
        track = t
        t.play()
        running = true
        writer = Thread({ writeLoop(t) }, "JarvisLiveSpeaker").apply { start() }
        monitor = Thread({ monitorLoop(t) }, "JarvisLiveLipSync").apply { start() }
        return true
    }

    /** Queues PCM from the network; never blocks the caller. */
    fun write(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    /** The user talked over her: drop everything queued or buffered at once. */
    fun interrupt() {
        val t = track ?: return
        synchronized(lock) {
            generation++
            queue.clear()
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
            meter = newMeter()
            base = t.playbackHeadPosition.toLong()
        }
        mouth.silent()
    }

    private fun writeLoop(t: AudioTrack) {
        while (running) {
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val gen = generation
            val even = chunk.size - chunk.size % 2
            synchronized(lock) {
                if (gen != generation) return@synchronized
                meter.feed(chunk, even)
            }
            var offset = 0
            while (running && offset < even && gen == generation) {
                val wrote = synchronized(lock) {
                    if (gen != generation) 0 else t.write(chunk, offset, even - offset, AudioTrack.WRITE_NON_BLOCKING)
                }
                if (wrote < 0) break
                offset += wrote
                if (offset < even) Thread.sleep(10)
            }
        }
    }

    private fun monitorLoop(t: AudioTrack) {
        var speaking = false
        var quietSince = 0L
        while (running) {
            val (playing, level) = synchronized(lock) {
                val position = t.playbackHeadPosition.toLong() - base
                val m = meter
                val playing = m.samples > 0 && position < m.samples
                if (!playing && queue.isEmpty() && m.samples > 0) {
                    // Everything played: start a fresh meter so it never fills up.
                    meter = newMeter()
                    base = t.playbackHeadPosition.toLong()
                }
                playing to if (playing) m.levelAt(position) else 0f
            }
            val now = System.currentTimeMillis()
            if (playing) {
                mouth.level(level)
                quietSince = 0L
                if (!speaking) {
                    speaking = true
                    onSpeaking(true)
                }
            } else if (speaking) {
                if (quietSince == 0L) {
                    quietSince = now
                    mouth.silent()
                } else if (now - quietSince >= END_OF_SPEECH_MS && queue.isEmpty()) {
                    speaking = false
                    onSpeaking(false)
                }
            }
            Thread.sleep(16)
        }
    }

    fun release() {
        running = false
        queue.clear()
        writer?.join(300)
        monitor?.join(300)
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
        track = null
        mouth.silent()
    }

    private companion object {
        /** A pause between her sentences shorter than this doesn't count as having finished. */
        const val END_OF_SPEECH_MS = 400L
    }
}

/**
 * Puts the phone in voice-call audio mode for Live (so the echo canceller has her voice as a reference)
 * and sends that audio to the loudspeaker, or to a headset if one is connected. Restored afterwards.
 */
class CommunicationRoute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var entered = false

    fun enter() {
        val am = audio ?: return
        runCatching {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val target = devices.firstOrNull { it.type in HEADSETS } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                target?.let(am::setCommunicationDevice)
            } else {
                val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = !headset
            }
            entered = true
        }.onFailure { Log.w(TAG, "couldn't switch audio route", it) }
    }

    fun exit() {
        val am = audio ?: return
        if (!entered) return
        entered = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = previousMode
        }
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
