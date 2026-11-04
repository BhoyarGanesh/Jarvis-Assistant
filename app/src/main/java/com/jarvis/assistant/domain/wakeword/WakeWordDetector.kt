package com.jarvis.assistant.domain.wakeword

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
    /** One to three words, e.g. "jarvis" or "hey maya" (see [WakePhrase]). */
    wakePhrase: String = WakePhrase.DEFAULT,
    sensitivity: Float = 0.5f,
    private val debounceMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sensitivity = sensitivity.coerceIn(0f, 1f)
    val minConfidence: Double = 0.95 - this.sensitivity * 0.5
    val triggersOnPartial: Boolean = this.sensitivity >= PARTIAL_TRIGGER_SENSITIVITY
    private var lastTrigger = Long.MIN_VALUE / 2
    val phrase: String = WakePhrase.normalize(wakePhrase).ifEmpty { WakePhrase.DEFAULT }
    private val target = phrase.split(' ')

    /** Vosk grammar: the phrase, and [unk] for everything else. */
    val grammar: String = JsonArray(listOf(JsonPrimitive(phrase), JsonPrimitive("[unk]"))).toString()

    /** Vosk partial hypothesis: `{"partial": "jarvis"}`. */
    fun onPartial(json: String): Boolean {
        if (!triggersOnPartial) return false
        val text = parse(json)?.get("partial")?.jsonPrimitive?.contentOrNull ?: return false
        return WakePhrase.containsPhrase(text.split(' '), phrase) && debounce()
    }

    /** Vosk final result with word confidences (requires `setWords(true)`). */
    fun onResult(json: String): Boolean {
        val obj = parse(json) ?: return false
        val words = obj["result"]?.let { runCatching { it.jsonArray }.getOrNull() }
        val hit = if (words != null) {
            // Every word of the phrase, side by side, each heard confidently enough.
            val heard = words.map { w ->
                val o = w.jsonObject
                (o["word"]?.jsonPrimitive?.contentOrNull ?: "") to (o["conf"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
            }
            (0..heard.size - target.size).any { start ->
                target.indices.all { i -> heard[start + i].first == target[i] && heard[start + i].second >= minConfidence }
            }
        } else {
            // No word list (e.g. setWords disabled): fall back to the plain text.
            WakePhrase.containsPhrase(obj["text"]?.jsonPrimitive?.contentOrNull?.split(' ').orEmpty(), phrase)
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
    }
}
