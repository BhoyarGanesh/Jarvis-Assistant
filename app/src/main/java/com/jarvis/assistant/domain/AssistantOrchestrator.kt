package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.contacts.ChoiceResult
import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.contacts.ContactMatch
import com.jarvis.assistant.domain.contacts.ContactMatcher
import com.jarvis.assistant.domain.model.AssistantCommand
import com.jarvis.assistant.domain.model.contactName
import com.jarvis.assistant.domain.model.needsConfirmation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one voice interaction end to end:
 * wake/tap -> chime -> speech-to-text -> backend -> resolve contact -> countdown -> execute -> speak -> idle.
 * Pure Kotlin on top of the ports in Ports.kt so the whole flow is unit-testable.
 */
class AssistantOrchestrator(
    private val settingsSource: SettingsSource,
    private val interpreter: CommandInterpreter,
    private val contacts: ContactsSource,
    private val speechInput: SpeechInput,
    private val speechOutput: SpeechOutput,
    private val chime: Chime,
    private val wakeWord: WakeWordControl,
    private val executor: ActionExecutor,
    private val actionLog: ActionLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    /** Normalized microphone level 0..1 for the orb. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var session: Job? = null
    private var confirmDecision: CompletableDeferred<Boolean>? = null
    private var choiceTap: CompletableDeferred<Int>? = null
    private var cardIds = 0L

    val isBusy: Boolean get() = session?.isActive == true

    /** Starts listening for a command. Ignored while an interaction is already running. */
    fun trigger() {
        if (isBusy) return
        session = scope.launch { runSession() }
    }

    /** Cancels a pending countdown, or the whole interaction if nothing is pending. */
    fun cancel() {
        val pending = confirmDecision
        if (pending != null && pending.isActive) {
            pending.complete(false)
            return
        }
        session?.cancel()
    }

    /** Skips the remaining countdown and runs the action now. */
    fun confirmNow() {
        confirmDecision?.complete(true)
    }

    /** On-screen answer to "which one?". */
    fun chooseOption(index: Int) {
        choiceTap?.complete(index)
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null, phase = if (it.phase == AssistantPhase.ERROR) AssistantPhase.IDLE else it.phase) }
    }

    private suspend fun runSession() {
        val settings = settingsSource.current()
        val phrases = Phrases(settings.isHindi)
        try {
            _state.update { it.copy(transcript = "", reply = "", errorMessage = null, confirmation = null, disambiguation = null) }
            wakeWord.pause()

            if (!settings.backendConfigured) {
                fail(phrases.backendNotSet(), settings)
                return
            }

            val transcript = when (val heard = listen(settings)) {
                is SpeechResult.Text -> heard.text
                SpeechResult.NoMatch -> {
                    speak(phrases.didNotHear(), settings)
                    return
                }
                is SpeechResult.Error -> {
                    fail(heard.message, settings)
                    return
                }
            }

            _state.update { it.copy(phase = AssistantPhase.THINKING, transcript = transcript) }
            val contactNames = if (settings.shareContactNames && contacts.hasPermission()) {
                contacts.loadContacts().map { it.displayName }.distinct()
            } else null

            when (val result = interpreter.interpret(transcript, settings.languageTag, contactNames)) {
                is InterpretResult.Failure -> fail(result.spokenMessage, settings)
                is InterpretResult.Success -> handle(result.command, transcript, settings, phrases)
            }
        } catch (e: CancellationException) {
            speechInput.cancel()
            speechOutput.stop()
            throw e
        } finally {
            confirmDecision = null
            choiceTap = null
            _level.value = 0f
            withContext(NonCancellable) {
                _state.update {
                    it.copy(
                        phase = if (it.phase == AssistantPhase.ERROR) AssistantPhase.ERROR else AssistantPhase.IDLE,
                        confirmation = null,
                        disambiguation = null,
                    )
                }
                wakeWord.resume()
            }
        }
    }

    private suspend fun listen(settings: AppSettings): SpeechResult {
        _state.update { it.copy(phase = AssistantPhase.LISTENING) }
        chime.play()
        return speechInput.listen(
            languageTag = settings.languageTag,
            onPartial = { partial -> _state.update { it.copy(transcript = partial) } },
            onLevel = { _level.value = it.coerceIn(0f, 1f) },
        ).also { _level.value = 0f }
    }

    private suspend fun handle(command: AssistantCommand, transcript: String, settings: AppSettings, phrases: Phrases) {
        if (command is AssistantCommand.Answer) {
            addCard(ActionKind.ANSWER, command.text)
            log(transcript, command, command.text, ActionStatus.SUCCESS)
            speak(command.text, settings)
            return
        }

        var contact: Contact? = null
        val spokenName = command.contactName
        if (spokenName != null) {
            contact = resolveContact(spokenName, settings, phrases) ?: run {
                log(transcript, command, "Contact not resolved: $spokenName", ActionStatus.FAILED)
                return
            }
            if (contact.phoneNumber.isBlank()) {
                val msg = phrases.noPhoneNumber(contact.displayName)
                addCard(ActionKind.INFO, msg, status = ActionStatus.FAILED)
                log(transcript, command, msg, ActionStatus.FAILED)
                speak(msg, settings)
                return
            }
        }

        if (command.needsConfirmation && settings.confirmationCountdownEnabled) {
            val confirmed = countdown(command, contact, settings)
            if (!confirmed) {
                addCard(kindOf(command), phrases.cancelled(), describe(command, contact), ActionStatus.CANCELLED)
                log(transcript, command, "Cancelled: ${describe(command, contact)}", ActionStatus.CANCELLED)
                speak(phrases.cancelled(), settings)
                return
            }
        }

        _state.update { it.copy(phase = AssistantPhase.THINKING) }
        val outcome = try {
            executor.execute(command, contact, phrases)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionOutcome(ActionStatus.FAILED, "Something went wrong while doing that.", e.message)
        }
        addCard(kindOf(command), outcome.speech, describe(command, contact), outcome.status)
        log(transcript, command, describe(command, contact), outcome.status, outcome.detail)
        speak(outcome.speech, settings)
    }

    private suspend fun resolveContact(spokenName: String, settings: AppSettings, phrases: Phrases): Contact? {
        if (!contacts.hasPermission()) {
            val msg = phrases.needPermission("contacts")
            fail(msg, settings)
            return null
        }
        return when (val match = ContactMatcher.match(spokenName, contacts.loadContacts())) {
            is ContactMatch.Single -> match.contact
            ContactMatch.NotFound -> {
                val msg = phrases.notFound(spokenName)
                addCard(ActionKind.INFO, msg, status = ActionStatus.FAILED)
                speak(msg, settings)
                null
            }
            is ContactMatch.Ambiguous -> askWhichOne(match.options, settings, phrases)
        }
    }

    private suspend fun askWhichOne(options: List<Contact>, settings: AppSettings, phrases: Phrases): Contact? {
        val names = options.map { it.displayName }
        val prompt = phrases.whichOne(names)
        val tap = CompletableDeferred<Int>().also { choiceTap = it }
        _state.update { it.copy(disambiguation = Disambiguation(prompt, names)) }
        speak(prompt, settings)

        val choice: ChoiceResult = coroutineScope {
            val voice = async {
                when (val heard = listen(settings)) {
                    is SpeechResult.Text -> ContactMatcher.pickChoice(heard.text, options)
                    else -> ChoiceResult.Unrecognized
                }
            }
            val picked = select {
                tap.onAwait { index -> options.getOrNull(index)?.let(ChoiceResult::Picked) ?: ChoiceResult.Unrecognized }
                voice.onAwait { it }
            }
            if (voice.isActive) {
                speechInput.cancel()
                voice.cancel()
            }
            picked
        }
        choiceTap = null
        _state.update { it.copy(disambiguation = null) }

        return when (choice) {
            is ChoiceResult.Picked -> choice.contact
            ChoiceResult.Cancelled -> {
                speak(phrases.cancelled(), settings)
                null
            }
            ChoiceResult.Unrecognized -> {
                speak(phrases.didNotCatchChoice(), settings)
                null
            }
        }
    }

    /** Returns true to proceed, false if the user cancelled. */
    private suspend fun countdown(command: AssistantCommand, contact: Contact?, settings: AppSettings): Boolean {
        val decision = CompletableDeferred<Boolean>().also { confirmDecision = it }
        val total = settings.confirmationSeconds.coerceIn(1, 10)
        val title = when (command) {
            is AssistantCommand.CallContact -> "Calling ${contact?.displayName}"
            is AssistantCommand.SendWhatsApp -> "WhatsApp to ${contact?.displayName}"
            is AssistantCommand.SendSms -> "Text to ${contact?.displayName}"
            else -> command.toolName
        }
        val subtitle = (command as? AssistantCommand.SendWhatsApp)?.message ?: (command as? AssistantCommand.SendSms)?.message
        try {
            for (left in total downTo 1) {
                _state.update {
                    it.copy(confirmation = PendingConfirmation(title, subtitle, kindOf(command), total, left))
                }
                val early = withTimeoutOrNull(1_000) { decision.await() }
                if (early != null) return early
            }
            return true
        } finally {
            confirmDecision = null
            _state.update { it.copy(confirmation = null) }
        }
    }

    private suspend fun speak(text: String, settings: AppSettings) {
        _state.update { it.copy(reply = text) }
        if (!settings.voiceReplyEnabled) return
        _state.update { it.copy(phase = AssistantPhase.SPEAKING) }
        speechOutput.speak(text, settings.languageTag)
    }

    private suspend fun fail(message: String, settings: AppSettings) {
        _state.update { it.copy(phase = AssistantPhase.ERROR, errorMessage = message, reply = message) }
        if (settings.voiceReplyEnabled) speechOutput.speak(message, settings.languageTag)
        delay(ERROR_FLASH_MS)
        _state.update { it.copy(phase = AssistantPhase.ERROR) }
    }

    private fun addCard(kind: ActionKind, title: String, subtitle: String? = null, status: ActionStatus = ActionStatus.SUCCESS) {
        val card = ActionCard(++cardIds, kind, title, subtitle, status)
        _state.update { it.copy(cards = (listOf(card) + it.cards).take(MAX_CARDS)) }
    }

    private suspend fun log(
        transcript: String,
        command: AssistantCommand,
        summary: String,
        status: ActionStatus,
        detail: String? = null,
    ) {
        runCatching {
            actionLog.record(ActionLogEntry(clock(), transcript, command.toolName, summary, status, detail))
        }
    }

    companion object {
        const val MAX_CARDS = 4
        private const val ERROR_FLASH_MS = 600L

        fun kindOf(command: AssistantCommand): ActionKind = when (command) {
            is AssistantCommand.CallContact -> ActionKind.CALL
            is AssistantCommand.SendWhatsApp -> ActionKind.WHATSAPP
            is AssistantCommand.SendSms -> ActionKind.SMS
            is AssistantCommand.SetAlarm -> ActionKind.ALARM
            is AssistantCommand.SetTimer -> ActionKind.TIMER
            is AssistantCommand.OpenApp -> ActionKind.APP
            is AssistantCommand.WebSearch -> ActionKind.SEARCH
            is AssistantCommand.PlayMusic -> ActionKind.MUSIC
            is AssistantCommand.Answer -> ActionKind.ANSWER
        }

        fun describe(command: AssistantCommand, contact: Contact?): String = when (command) {
            is AssistantCommand.CallContact -> "Call ${contact?.displayName ?: command.name}"
            is AssistantCommand.SendWhatsApp -> "WhatsApp to ${contact?.displayName ?: command.contactName}: ${command.message}"
            is AssistantCommand.SendSms -> "SMS to ${contact?.displayName ?: command.contactName}: ${command.message}"
            is AssistantCommand.SetAlarm -> "Alarm ${Phrases.formatTime(command.hour, command.minute)}" +
                command.label.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            is AssistantCommand.SetTimer -> "Timer ${Phrases.formatDuration(command.seconds)}"
            is AssistantCommand.OpenApp -> "Open ${command.appName}"
            is AssistantCommand.WebSearch -> "Search: ${command.query}"
            is AssistantCommand.PlayMusic -> "Play: ${command.query}"
            is AssistantCommand.Answer -> command.text
        }
    }
}
