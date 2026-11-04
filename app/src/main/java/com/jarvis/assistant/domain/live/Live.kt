package com.jarvis.assistant.domain.live

import com.jarvis.assistant.domain.model.AssistantCommand
import kotlinx.coroutines.channels.ReceiveChannel

// Live mode: a streaming voice conversation (Gemini Live through the backend) that the user can talk
// over. The Android side streams the microphone and plays her voice; the orchestrator only sees these
// events and runs the tools she asks for, so the conversation logic stays pure and testable.

data class LiveStartRequest(
    val voice: String,
    val languageTag: String,
    val contactNames: List<String>?,
    /** Microphone loudness 0..1 for the glow, about 25 times a second. */
    val onMicLevel: (Float) -> Unit,
)

sealed interface LiveStart {
    class Started(val session: LiveSession) : LiveStart

    /**
     * Live mode can't run now, so the normal pipeline takes over. [quota] means the free tier is used up:
     * don't retry for a while. [reason] is for the screen and the logs, never spoken.
     */
    data class Unavailable(val reason: String, val quota: Boolean = false) : LiveStart
}

interface LiveSession {
    /** Closed after [LiveEvent.Ended]. */
    val events: ReceiveChannel<LiveEvent>

    fun sendToolResult(id: String, name: String, result: String)

    /** Types to her, as if spoken. */
    fun sendText(text: String)

    /** Stops the microphone and her voice at once and hangs up. Safe to call twice. */
    fun close()
}

sealed interface LiveEvent {
    /** The next piece of what the user is saying (transcript chunks arrive in order). */
    data class Heard(val text: String) : LiveEvent

    /** The next piece of what she is saying. */
    data class Said(val text: String) : LiveEvent

    /** Her voice started or finished playing on the speaker. */
    data class Speaking(val active: Boolean) : LiveEvent

    /** She wants a tool run on the phone. Reply with [LiveSession.sendToolResult]. */
    data class ToolCall(val id: String, val name: String, val command: AssistantCommand) : LiveEvent

    /** She noticed the user talking over her; her voice has already been cut off. */
    data object Interrupted : LiveEvent

    data object TurnComplete : LiveEvent

    /** She no longer wants these tool calls (the user changed their mind mid-way). */
    data class ToolCancelled(val ids: List<String>) : LiveEvent

    /** The connection ended. [spokenMessage] explains a failure; null for a normal close. */
    data class Ended(val spokenMessage: String? = null, val quota: Boolean = false) : LiveEvent
}

interface LiveVoice {
    suspend fun start(request: LiveStartRequest): LiveStart
}

object LiveRules {
    /** Silence (nobody talking, nothing playing, no tool running) that ends a live conversation. */
    const val IDLE_MS = 8_000L

    /** After "thanks" or a hand-over action, how long to wait for her last words before hanging up. */
    const val END_GRACE_MS = 3_000L

    /** Live models sometimes repeat a tool call; an identical one within this window isn't run twice. */
    const val DUPLICATE_WINDOW_MS = 15_000L

    /** After Live reports the free quota is used up, use the normal pipeline for this long. */
    const val QUOTA_COOLDOWN_MS = 5 * 60_000L
}
