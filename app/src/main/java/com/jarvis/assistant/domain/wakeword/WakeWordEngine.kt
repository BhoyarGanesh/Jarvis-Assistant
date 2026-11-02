package com.jarvis.assistant.domain.wakeword

import com.jarvis.assistant.domain.WakeWordControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class WakeWordConfig(val sensitivity: Float, val debounceMs: Long)

sealed interface WakeWordEngineState {
    data object Stopped : WakeWordEngineState
    data object Loading : WakeWordEngineState
    data object Listening : WakeWordEngineState
    data object Paused : WakeWordEngineState
    data class Unavailable(val reason: String) : WakeWordEngineState
}

/**
 * Always-on hotword detector. The Vosk implementation is the default; anything else
 * (Porcupine, openWakeWord, ...) can be dropped in by binding a different implementation.
 */
interface WakeWordEngine : WakeWordControl {
    val state: StateFlow<WakeWordEngineState>

    /** Emits once per detected wake word. */
    val detections: Flow<Unit>

    fun start(config: WakeWordConfig)
    fun stop()
}
