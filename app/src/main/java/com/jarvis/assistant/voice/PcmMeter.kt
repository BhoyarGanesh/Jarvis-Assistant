package com.jarvis.assistant.voice

import kotlin.math.sqrt

/**
 * Turns streamed 16-bit little-endian mono PCM into one mouth level (0..1) per [frameSamples]
 * samples, so the avatar can look up the level for whatever the speaker is playing right now.
 * Bytes may arrive in any chunk size, including odd ones.
 *
 * The level is each frame's loudness relative to the peak of the last ~quarter second, not an
 * absolute volume: loud voices would otherwise hold the mouth wide open for a whole word. Relative
 * loudness rises on stressed syllables and dips between them, which reads as natural speech.
 */
class PcmMeter(private val frameSamples: Int, maxFrames: Int = 6_000) {
    private val levels = FloatArray(maxFrames)
    private val recent = DoubleArray(PEAK_WINDOW)

    @Volatile
    var frameCount = 0
        private set

    private var carry = -1
    private var sumSquares = 0.0
    private var samplesInFrame = 0

    /** Total complete samples seen so far. */
    var samples = 0L
        private set

    /** Feeds the first [length] bytes of [bytes]. */
    fun feed(bytes: ByteArray, length: Int) {
        var i = 0
        if (carry >= 0 && length > 0) {
            addSample(carry or (bytes[0].toInt() shl 8))
            carry = -1
            i = 1
        }
        while (i + 1 < length) {
            addSample((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8))
            i += 2
        }
        if (i < length) carry = bytes[i].toInt() and 0xFF
    }

    /** Ends the stream, closing a partial last frame. */
    fun finish() {
        if (samplesInFrame > 0) closeFrame()
    }

    /** Level of the frame containing [samplePosition], or 0 past the end. */
    fun levelAt(samplePosition: Long): Float {
        val index = (samplePosition / frameSamples).toInt()
        return if (index in 0 until minOf(frameCount, levels.size)) levels[index] else 0f
    }

    private fun addSample(value: Int) {
        val sample = value.toShort().toDouble()
        sumSquares += sample * sample
        samplesInFrame++
        samples++
        if (samplesInFrame == frameSamples) closeFrame()
    }

    private fun closeFrame() {
        val rms = sqrt(sumSquares / samplesInFrame)
        recent[frameCount % PEAK_WINDOW] = rms
        val peak = maxOf(MIN_PEAK, recent.max())
        val level = if (rms < VOICED) 0f else (rms / peak).toFloat().coerceIn(0f, 1f)
        if (frameCount < levels.size) levels[frameCount] = level
        frameCount++
        sumSquares = 0.0
        samplesInFrame = 0
    }

    private companion object {
        /** RMS below this is breath or silence: mouth closed. */
        const val VOICED = 1_200.0
        /** Keeps a quiet sentence from looking shouted. */
        const val MIN_PEAK = 2_000.0
        /** Frames (20 ms each) the peak is taken over. */
        const val PEAK_WINDOW = 12
    }
}
