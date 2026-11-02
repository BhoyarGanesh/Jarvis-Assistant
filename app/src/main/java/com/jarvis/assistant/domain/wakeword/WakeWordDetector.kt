package com.jarvis.assistant.domain.wakeword

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decides whether Vosk output contains the wake word, applying the sensitivity setting and a
 * debounce window so one utterance (or background chatter) can't fire repeatedly.
 *
 * Sensitivity 0..1 maps to a minimum per-word confidence (0.95 .. 0.45). At high sensitivity
 * (>= [PARTIAL_TRIGGER_SENSITIVITY]) partial results also trigger, which is faster but noisier.
 */
class WakeWordDetector(
    private val wakeWord: String = "jarvis",
    sensitivity: Float = 0.5f,
    private val debounceMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sensitivity = sensitivity.coerceIn(0f, 1f)
    val minConfidence: Double = 0.95 - this.sensitivity * 0.5
    val triggersOnPartial: Boolean = this.sensitivity >= PARTIAL_TRIGGER_SENSITIVITY
    private var lastTrigger = Long.MIN_VALUE / 2

    /** Vosk partial hypothesis: `{"partial": "jarvis"}`. */
    fun onPartial(json: String): Boolean {
        if (!triggersOnPartial) return false
        val text = parse(json)?.get("partial")?.jsonPrimitive?.contentOrNull ?: return false
        return wakeWord in text.split(' ') && debounce()
    }

    /** Vosk final result with word confidences (requires `setWords(true)`). */
    fun onResult(json: String): Boolean {
        val obj = parse(json) ?: return false
        val words = obj["result"]?.let { runCatching { it.jsonArray }.getOrNull() }
        val hit = if (words != null) {
            words.any { w ->
                val o = w.jsonObject
                o["word"]?.jsonPrimitive?.contentOrNull == wakeWord &&
                    (o["conf"]?.jsonPrimitive?.doubleOrNull ?: 0.0) >= minConfidence
            }
        } else {
            // No word list (e.g. setWords disabled): fall back to the plain text.
            obj["text"]?.jsonPrimitive?.contentOrNull?.split(' ')?.contains(wakeWord) == true
        }
        return hit && debounce()
    }

    private fun debounce(): Boolean {
        val now = clock()
        if (now - lastTrigger < debounceMs) return false
        lastTrigger = now
        return true
    }

    private fun parse(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()

    companion object {
        const val PARTIAL_TRIGGER_SENSITIVITY = 0.8f
        const val GRAMMAR = "[\"jarvis\", \"[unk]\"]"
    }
}
