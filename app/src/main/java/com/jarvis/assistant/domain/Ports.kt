package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.contacts.Contact
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
) {
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
    suspend fun interpret(text: String, languageTag: String, contactNames: List<String>?): InterpretResult
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
    suspend fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
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
