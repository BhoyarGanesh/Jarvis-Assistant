package com.jarvis.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** How Jarvis is talking right now; drives the avatar's mouth. */
sealed interface Mouth {
    data object Silent : Mouth

    /** Streamed voice: [level] 0..1 is the loudness of the audio playing this instant. */
    data class Level(val level: Float) : Mouth

    /** Phone text-to-speech gives no audio levels, so the avatar animates speech on its own. */
    data object Talking : Mouth
}

@Singleton
class MouthLevel @Inject constructor() {
    private val _mouth = MutableStateFlow<Mouth>(Mouth.Silent)
    val mouth: StateFlow<Mouth> = _mouth.asStateFlow()

    fun level(value: Float) {
        _mouth.value = Mouth.Level(value.coerceIn(0f, 1f))
    }

    fun talking() {
        _mouth.value = Mouth.Talking
    }

    fun silent() {
        _mouth.value = Mouth.Silent
    }
}
