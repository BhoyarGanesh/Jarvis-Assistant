package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.model.AssistantCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantOrchestratorTest {

    private val king = Contact(1, "King", "+911111111111")
    private val kingKumar = Contact(2, "King Kumar", "+912222222222")
    private val kingSingh = Contact(3, "King Singh", "+913333333333")
    private val junior = Contact(4, "Rahul", "+914444444444", nicknames = listOf("Junior"))

    private val baseSettings = AppSettings(
        backendUrl = "http://10.0.0.2:8787",
        sharedSecret = "s",
        confirmationCountdownEnabled = false,
    )

    private class Harness(
        scope: TestScope,
        settings: AppSettings,
        var interpretResult: InterpretResult,
        val contactList: List<Contact>,
        val contactsPermission: Boolean = true,
        speech: List<suspend () -> SpeechResult>,
    ) {
        val spoken = mutableListOf<String>()
        val executed = mutableListOf<Pair<AssistantCommand, Contact?>>()
        val logged = mutableListOf<ActionLogEntry>()
        var interpretCalls = 0
        var lastContactNames: List<String>? = null
        var pauses = 0
        var resumes = 0
        private val speechQueue = ArrayDeque(speech)

        val orchestrator = AssistantOrchestrator(
            settingsSource = object : SettingsSource {
                override val settings: Flow<AppSettings> = flowOf(settings)
                override suspend fun current() = settings
            },
            interpreter = object : CommandInterpreter {
                override suspend fun interpret(text: String, languageTag: String, contactNames: List<String>?): InterpretResult {
                    interpretCalls++
                    lastContactNames = contactNames
                    return interpretResult
                }
            },
            contacts = object : ContactsSource {
                override fun hasPermission() = contactsPermission
                override suspend fun loadContacts() = contactList
            },
            speechInput = object : SpeechInput {
                override suspend fun listen(languageTag: String, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): SpeechResult {
                    val next = speechQueue.removeFirstOrNull() ?: return SpeechResult.NoMatch
                    return next()
                }

                override fun cancel() = Unit
            },
            speechOutput = object : SpeechOutput {
                override suspend fun speak(text: String, languageTag: String) {
                    spoken += text
                }

                override fun stop() = Unit
            },
            chime = object : Chime {
                override suspend fun play() = Unit
            },
            wakeWord = object : WakeWordControl {
                override suspend fun pause() {
                    pauses++
                }

                override fun resume() {
                    resumes++
                }
            },
            executor = object : ActionExecutor {
                override suspend fun execute(command: AssistantCommand, contact: Contact?, phrases: Phrases): ActionOutcome {
                    executed += command to contact
                    return ActionOutcome(ActionStatus.SUCCESS, if (contact != null) phrases.calling(contact.displayName) else "Done")
                }
            },
            actionLog = object : ActionLog {
                override suspend fun record(entry: ActionLogEntry) {
                    logged += entry
                }
            },
            scope = scope,
            clock = { 1_000L },
        )
    }

    private fun TestScope.harness(
        command: AssistantCommand = AssistantCommand.CallContact("King"),
        contacts: List<Contact> = listOf(king, junior),
        settings: AppSettings = baseSettings,
        contactsPermission: Boolean = true,
        speech: List<suspend () -> SpeechResult> = listOf({ SpeechResult.Text("call king") }),
        interpretResult: InterpretResult = InterpretResult.Success(command),
    ) = Harness(this, settings, interpretResult, contacts, contactsPermission, speech)

    @Test fun callKingHappyPath() = runTest {
        val h = harness()
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertEquals(listOf(AssistantCommand.CallContact("King") to king), h.executed)
        assertEquals(listOf("Calling King"), h.spoken)
        assertEquals(1, h.logged.size)
        assertEquals(ActionStatus.SUCCESS, h.logged[0].status)
        assertEquals("call king", h.logged[0].transcript)
        assertEquals(1, h.pauses)
        assertEquals(1, h.resumes)
        val state = h.orchestrator.state.value
        assertEquals(AssistantPhase.IDLE, state.phase)
        assertEquals("call king", state.transcript)
        assertEquals(ActionKind.CALL, state.cards.first().kind)
    }

    @Test fun whatsappToNicknameUsesResolvedContact() = runTest {
        val h = harness(command = AssistantCommand.SendWhatsApp("junior", "hi"))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(junior, h.executed.single().second)
    }

    @Test fun countdownRunsThenExecutes() = runTest {
        val h = harness(settings = baseSettings.copy(confirmationCountdownEnabled = true, confirmationSeconds = 3))
        h.orchestrator.trigger()
        runCurrent()

        val confirmation = h.orchestrator.state.value.confirmation
        assertNotNull(confirmation)
        assertEquals(3, confirmation!!.secondsLeft)
        assertEquals("Calling King", confirmation.title)
        assertTrue(h.executed.isEmpty())

        advanceTimeBy(1_500)
        assertEquals(2, h.orchestrator.state.value.confirmation?.secondsLeft)

        advanceUntilIdle()
        assertEquals(1, h.executed.size)
        assertNull(h.orchestrator.state.value.confirmation)
    }

    @Test fun cancelDuringCountdownSkipsAction() = runTest {
        val h = harness(settings = baseSettings.copy(confirmationCountdownEnabled = true))
        h.orchestrator.trigger()
        runCurrent()
        h.orchestrator.cancel()
        advanceUntilIdle()

        assertTrue(h.executed.isEmpty())
        assertEquals(listOf("Cancelled"), h.spoken)
        assertEquals(ActionStatus.CANCELLED, h.logged.single().status)
        assertEquals(1, h.resumes)
    }

    @Test fun confirmNowSkipsRemainingCountdown() = runTest {
        val h = harness(settings = baseSettings.copy(confirmationCountdownEnabled = true, confirmationSeconds = 10))
        h.orchestrator.trigger()
        runCurrent()
        h.orchestrator.confirmNow()
        runCurrent()
        assertEquals(1, h.executed.size)
    }

    @Test fun ambiguousContactAsksAndAcceptsVoiceAnswer() = runTest {
        val h = harness(
            contacts = listOf(kingKumar, kingSingh),
            speech = listOf({ SpeechResult.Text("call king") }, { SpeechResult.Text("the second one") }),
        )
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertEquals("I found King Kumar and King Singh. Which one?", h.spoken.first())
        assertEquals(kingSingh, h.executed.single().second)
        assertNull(h.orchestrator.state.value.disambiguation)
    }

    @Test fun ambiguousContactAcceptsTap() = runTest {
        val never = CompletableDeferred<SpeechResult>()
        val h = harness(
            contacts = listOf(kingKumar, kingSingh),
            speech = listOf({ SpeechResult.Text("call king") }, { never.await() }),
        )
        h.orchestrator.trigger()
        runCurrent()
        assertEquals(listOf("King Kumar", "King Singh"), h.orchestrator.state.value.disambiguation?.options)

        h.orchestrator.chooseOption(0)
        advanceUntilIdle()
        assertEquals(kingKumar, h.executed.single().second)
    }

    @Test fun unrecognizedChoiceCancels() = runTest {
        val h = harness(
            contacts = listOf(kingKumar, kingSingh),
            speech = listOf({ SpeechResult.Text("call king") }, { SpeechResult.Text("banana") }),
        )
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.executed.isEmpty())
        assertTrue(h.spoken.last().contains("didn't catch"))
        assertEquals(ActionStatus.FAILED, h.logged.single().status)
    }

    @Test fun unknownContactIsReportedNotInvented() = runTest {
        val h = harness(command = AssistantCommand.CallContact("Zebra"))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.executed.isEmpty())
        assertEquals("I couldn't find Zebra in your contacts.", h.spoken.single())
        assertEquals(ActionStatus.FAILED, h.logged.single().status)
    }

    @Test fun missingContactsPermissionIsSpokenAndShown() = runTest {
        val h = harness(contactsPermission = false)
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.executed.isEmpty())
        assertTrue(h.spoken.single().contains("contacts permission"))
        assertEquals(AssistantPhase.ERROR, h.orchestrator.state.value.phase)
        assertNotNull(h.orchestrator.state.value.errorMessage)
    }

    @Test fun backendFailureIsSpoken() = runTest {
        val h = harness(interpretResult = InterpretResult.Failure("I'm getting too many requests right now."))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf("I'm getting too many requests right now."), h.spoken)
        assertEquals(AssistantPhase.ERROR, h.orchestrator.state.value.phase)
        assertEquals(1, h.resumes)
    }

    @Test fun silenceSaysDidNotHear() = runTest {
        val h = harness(speech = listOf({ SpeechResult.NoMatch }))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(0, h.interpretCalls)
        assertEquals(listOf("I didn't hear anything."), h.spoken)
    }

    @Test fun micPermissionErrorIsSpoken() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Error("I need microphone permission to hear you.", permissionMissing = true) }))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(AssistantPhase.ERROR, h.orchestrator.state.value.phase)
        assertEquals(listOf("I need microphone permission to hear you."), h.spoken)
    }

    @Test fun unconfiguredBackendNeverListens() = runTest {
        val h = harness(settings = baseSettings.copy(backendUrl = ""))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(0, h.interpretCalls)
        assertTrue(h.spoken.single().contains("backend"))
    }

    @Test fun answerIsSpokenWithoutExecutor() = runTest {
        val h = harness(command = AssistantCommand.Answer("Paris."))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.executed.isEmpty())
        assertEquals(listOf("Paris."), h.spoken)
        assertEquals(ActionKind.ANSWER, h.orchestrator.state.value.cards.first().kind)
    }

    @Test fun nonContactCommandExecutesDirectly() = runTest {
        val h = harness(
            command = AssistantCommand.SetTimer(300, ""),
            settings = baseSettings.copy(confirmationCountdownEnabled = true),
        )
        h.orchestrator.trigger()
        runCurrent()
        assertNull(h.orchestrator.state.value.confirmation)
        advanceUntilIdle()
        assertEquals(AssistantCommand.SetTimer(300, "") to null, h.executed.single())
    }

    @Test fun voiceReplyOffStillShowsReply() = runTest {
        val h = harness(settings = baseSettings.copy(voiceReplyEnabled = false))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.spoken.isEmpty())
        assertEquals("Calling King", h.orchestrator.state.value.reply)
    }

    @Test fun contactNamesSharedOnlyWhenEnabled() = runTest {
        val off = harness()
        off.orchestrator.trigger()
        advanceUntilIdle()
        assertNull(off.lastContactNames)

        val on = harness(settings = baseSettings.copy(shareContactNames = true))
        on.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf("King", "Rahul"), on.lastContactNames)
    }

    @Test fun triggerIgnoredWhileBusy() = runTest {
        val h = harness(settings = baseSettings.copy(confirmationCountdownEnabled = true))
        h.orchestrator.trigger()
        runCurrent()
        assertTrue(h.orchestrator.isBusy)
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(1, h.interpretCalls)
        assertFalse(h.orchestrator.isBusy)
    }
}
