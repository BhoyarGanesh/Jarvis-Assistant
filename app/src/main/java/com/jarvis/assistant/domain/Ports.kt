package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.conversation.Turn
import com.jarvis.assistant.domain.model.AssistantCommand
import kotlinx.coroutines.flow.Flow

// Boundaries between the assistant flow and Android/network. Implemented in the data and
// platform packages and faked in unit tests.

data class AppSettings(
    val wakeWordEnabled: Boolean = true,
    /** 0 = strict (fewest false triggers) .. 1 = very sensitive. */
    val wakeWordSensitivity: Float = 0.5f,
    val wakeWordDebounceMs: Long = 2_000,
    val backendUrl: String = "",
    val sharedSecret: String = "",
    val voiceReplyEnabled: Boolean = true,
    val confirmationCountdownEnabled: Boolean = true,
    val confirmationSeconds: Int = 3,
    val languageTag: String = "en-US",
    val startOnBoot: Boolean = false,
    val shareContactNames: Boolean = false,
    val onboardingComplete: Boolean = false,
    /** Say who is calling and take "answer" / "reject" / "tell him ..." by voice. */
    val announceCalls: Boolean = false,
    /** Keep a screenshot in memory when the wake word fires, for "what's on my screen?". */
    val screenAwareness: Boolean = true,
    /** Gemini voice for spoken replies, or [PHONE_VOICE] for the phone's own text-to-speech. */
    val voiceName: String = DEFAULT_VOICE,
    /** Show the animated character instead of the orb. */
    val showAvatar: Boolean = true,
    /** Live rain over the animated character. */
    val rainEffect: Boolean = true,
    /** After a reply, keep listening a few seconds for a follow-up without the wake word. */
    val followUpMode: Boolean = true,
) {
    companion object {
        const val PHONE_VOICE = ""
        const val DEFAULT_VOICE = "Despina"
    }

    val isHindi: Boolean get() = languageTag.startsWith("hi")
    val backendConfigured: Boolean get() = backendUrl.isNotBlank() && sharedSecret.isNotBlank()
}

interface SettingsSource {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
}

sealed interface InterpretResult {
    data class Success(val command: AssistantCommand) : InterpretResult
    /** [spokenMessage] is safe to read aloud. */
    data class Failure(val spokenMessage: String) : InterpretResult
}

interface CommandInterpreter {
    /** [history] holds the earlier turns of this conversation, oldest first, so follow-ups like "call him" resolve. */
    suspend fun interpret(
        text: String,
        languageTag: String,
        contactNames: List<String>?,
        history: List<Turn> = emptyList(),
    ): InterpretResult
}

interface ContactsSource {
    fun hasPermission(): Boolean
    suspend fun loadContacts(): List<Contact>
}

sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult
    data object NoMatch : SpeechResult
    data class Error(val message: String, val permissionMissing: Boolean = false) : SpeechResult
}

interface SpeechInput {
    /**
     * Listens for one utterance. With [startTimeoutMs] > 0, gives up with [SpeechResult.NoMatch] if
     * no speech has begun by then; an utterance already under way is never cut off.
     */
    suspend fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        startTimeoutMs: Long = 0,
    ): SpeechResult

    fun cancel()
}

interface SpeechOutput {
    /** Suspends until the utterance finishes (or immediately if speech is unavailable). */
    suspend fun speak(text: String, languageTag: String)
    fun stop()
}

interface Chime {
    suspend fun play()
}

/** Lets the assistant borrow the microphone from the always-on wake word engine. */
interface WakeWordControl {
    suspend fun pause()
    fun resume()
}

enum class ActionStatus { SUCCESS, NEEDS_USER, FAILED, CANCELLED }

data class ActionOutcome(
    val status: ActionStatus,
    val speech: String,
    val detail: String? = null,
)

interface ActionExecutor {
    /** [contact] is the resolved contact for call/message commands, null otherwise. */
    suspend fun execute(command: AssistantCommand, contact: Contact?, phrases: Phrases): ActionOutcome
}

/** Controls the currently ringing call. */
interface CallControl {
    fun answer(): Boolean
    fun reject(): Boolean
    /** Mutes the ringtone so Jarvis can be heard and can hear the reply. */
    fun silenceRinger()
    fun restoreRinger()
}

data class ActionLogEntry(
    val timestamp: Long,
    val transcript: String,
    val tool: String,
    val summary: String,
    val status: ActionStatus,
    val detail: String? = null,
)

interface ActionLog {
    suspend fun record(entry: ActionLogEntry)
}
