package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMeterTest {
    /** 16-bit little-endian PCM of [samples]. */
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
    }

    /** One frame of [frameSamples] samples alternating +/-[amplitude] (RMS = amplitude). */
    private fun frame(amplitude: Int, frameSamples: Int = 4) = IntArray(frameSamples) { if (it % 2 == 0) amplitude else -amplitude }

    private fun meterOf(vararg amplitudes: Int): PcmMeter {
        val meter = PcmMeter(frameSamples = 4)
        val bytes = pcm(*amplitudes.flatMap { frame(it).toList() }.toIntArray())
        meter.feed(bytes, bytes.size)
        return meter
    }

    @Test fun silenceAndBreathKeepTheMouthClosed() {
        val meter = meterOf(0, 800, 6000)
        assertEquals(0f, meter.levelAt(0), 0.001f)
        assertEquals(0f, meter.levelAt(4), 0.001f)
        assertEquals(1f, meter.levelAt(8), 0.001f)
        assertEquals(0f, meter.levelAt(100), 0.001f)
    }

    @Test fun levelIsRelativeToTheRecentPeakSoSyllablesStandOut() {
        // A loud stressed syllable, then a softer one: the mouth opens fully, then half.
        val meter = meterOf(8000, 4000, 8000)
        assertEquals(1f, meter.levelAt(0), 0.001f)
        assertEquals(0.5f, meter.levelAt(4), 0.001f)
        assertEquals(1f, meter.levelAt(8), 0.001f)
    }

    @Test fun quietSentenceIsNotShouted() {
        // Peak never drops below 2000, so a quiet voice moves the lips less.
        assertEquals(0.75f, meterOf(1500).levelAt(0), 0.001f)
    }

    @Test fun oldPeaksAreForgotten() {
        val loud = IntArray(1) { 9000 }
        val soft = IntArray(12) { 3000 }
        val meter = meterOf(*(loud + soft))
        assertEquals(1f / 3, meter.levelAt(4), 0.01f)
        assertEquals(1f, meter.levelAt(12L * 4), 0.001f) // the 9000 frame has left the window
    }

    @Test fun samplesSplitAcrossChunksAreJoined() {
        val meter = PcmMeter(frameSamples = 2)
        val bytes = pcm(-7000, 7000)
        meter.feed(bytes.copyOfRange(0, 1), 1)
        meter.feed(bytes.copyOfRange(1, 3), 2)
        meter.feed(bytes.copyOfRange(3, 4), 1)
        assertEquals(2L, meter.samples)
        assertEquals(1f, meter.levelAt(0), 0.001f)
    }

    @Test fun finishClosesAPartialFrame() {
        val meter = PcmMeter(frameSamples = 100)
        val bytes = pcm(3500, -3500)
        meter.feed(bytes, bytes.size)
        assertEquals(0, meter.frameCount)
        meter.finish()
        assertEquals(1, meter.frameCount)
        assertTrue(meter.levelAt(0) > 0.9f)
    }
}
