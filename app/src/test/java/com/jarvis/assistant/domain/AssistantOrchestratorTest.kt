package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.calls.IncomingCall
import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.conversation.FollowUp
import com.jarvis.assistant.domain.conversation.Turn
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
        followUpMode = false,
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
        /** Results for the next interpret calls, in order; [interpretResult] once it runs dry. */
        val interpretQueue = ArrayDeque<InterpretResult>()
        val interpreted = mutableListOf<Pair<String, List<Turn>>>()
        val listenTimeouts = mutableListOf<Long>()
        var chimes = 0
        /** "listen" and "speak" in the order they happened. */
        val events = mutableListOf<String>()
        var pauses = 0
        var resumes = 0
        val callActions = mutableListOf<String>()
        var callControlWorks = true
        private val speechQueue = ArrayDeque(speech)

        val orchestrator = AssistantOrchestrator(
            settingsSource = object : SettingsSource {
                override val settings: Flow<AppSettings> = flowOf(settings)
                override suspend fun current() = settings
            },
            interpreter = object : CommandInterpreter {
                override suspend fun interpret(
                    text: String,
                    languageTag: String,
                    contactNames: List<String>?,
                    history: List<Turn>,
                ): InterpretResult {
                    interpretCalls++
                    lastContactNames = contactNames
                    interpreted += text to history
                    return interpretQueue.removeFirstOrNull() ?: interpretResult
                }
            },
            contacts = object : ContactsSource {
                override fun hasPermission() = contactsPermission
                override suspend fun loadContacts() = contactList
            },
            speechInput = object : SpeechInput {
                override suspend fun listen(
                    languageTag: String,
                    onPartial: (String) -> Unit,
                    onLevel: (Float) -> Unit,
                    startTimeoutMs: Long,
                ): SpeechResult {
                    listenTimeouts += startTimeoutMs
                    events += "listen"
                    val next = speechQueue.removeFirstOrNull() ?: return SpeechResult.NoMatch
                    return next()
                }

                override fun cancel() = Unit
            },
            speechOutput = object : SpeechOutput {
                override suspend fun speak(text: String, languageTag: String) {
                    spoken += text
                    events += "speak"
                }

                override fun stop() = Unit
            },
            chime = object : Chime {
                override suspend fun play() {
                    chimes++
                }
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
            calls = object : CallControl {
                override fun answer() = callControlWorks.also { callActions += "answer" }
                override fun reject() = callControlWorks.also { callActions += "reject" }
                override fun silenceRinger() {
                    callActions += "silence"
                }

                override fun restoreRinger() {
                    callActions += "restore"
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

    @Test fun replyGetsCountdownWithoutContactLookup() = runTest {
        val h = harness(
            command = AssistantCommand.ReplyMessage("", "on my way"),
            contacts = emptyList(),
            settings = baseSettings.copy(confirmationCountdownEnabled = true),
        )
        h.orchestrator.trigger()
        runCurrent()
        val confirmation = h.orchestrator.state.value.confirmation
        assertEquals("Reply to latest message", confirmation?.title)
        assertEquals("on my way", confirmation?.subtitle)
        advanceUntilIdle()
        assertEquals(AssistantCommand.ReplyMessage("", "on my way") to null, h.executed.single())
    }

    @Test fun newCommandsRunWithoutCountdownOrContactLookup() = runTest {
        for (command in listOf(
            AssistantCommand.ScreenQuestion("is this a scam?"),
            AssistantCommand.SummarizeMessages("", "Family", "summarise my family group"),
            AssistantCommand.FindPhone,
        )) {
            val h = harness(command = command, settings = baseSettings.copy(confirmationCountdownEnabled = true))
            h.orchestrator.trigger()
            advanceUntilIdle()
            assertEquals(command to null, h.executed.single())
            assertEquals(command.toolName, h.logged.single().tool)
        }
    }

    private val kingCall = IncomingCall("King", "+911111111111")

    @Test fun callAnnouncedThenAnswered() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Text("answer it") }))
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()

        assertEquals(listOf("King is calling. Answer, reject, or tell me what to text them?"), h.spoken)
        assertEquals(listOf("silence", "answer", "restore"), h.callActions)
        assertTrue(h.executed.isEmpty())
        assertEquals(AssistantOrchestrator.CALL_TOOL, h.logged.single().tool)
        assertEquals(1, h.pauses)
        assertEquals(1, h.resumes)
    }

    @Test fun callRejected() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Text("reject") }))
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()
        assertEquals(listOf("silence", "reject", "restore"), h.callActions)
        assertEquals("Declined.", h.spoken.last())
    }

    @Test fun callDeclinedWithTextMessage() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Text("tell him I'll call back in 10 minutes") }))
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()

        assertEquals(listOf("silence", "reject", "restore"), h.callActions)
        val (command, contact) = h.executed.single()
        assertEquals(AssistantCommand.SendSms("King", "I'll call back in 10 minutes"), command)
        assertEquals("+911111111111", contact?.phoneNumber)
        assertTrue(h.spoken.last().startsWith("Declined."))
    }

    @Test fun callFromUnknownNumberCannotBeTexted() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Text("text them I'm driving") }))
        h.orchestrator.announceCall(IncomingCall(null, null))
        advanceUntilIdle()

        assertTrue(h.spoken.first().startsWith("Someone is calling"))
        assertTrue(h.executed.isEmpty())
        assertEquals(ActionStatus.FAILED, h.logged.single().status)
    }

    @Test fun callAsksAgainOnceThenLetsItRing() = runTest {
        val h = harness(speech = listOf({ SpeechResult.NoMatch }, { SpeechResult.Text("hmm what") }))
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()

        assertEquals(3, h.spoken.size)
        assertEquals("OK, I'll let it ring.", h.spoken.last())
        assertEquals(listOf("silence", "restore"), h.callActions)
    }

    @Test fun callEndingStopsTheAnnouncement() = runTest {
        val reply = CompletableDeferred<SpeechResult>()
        val h = harness(speech = listOf({ reply.await() }))
        h.orchestrator.announceCall(kingCall)
        runCurrent()
        assertTrue(h.orchestrator.isBusy)

        h.orchestrator.callStopped()
        advanceUntilIdle()
        assertFalse(h.orchestrator.isBusy)
        assertEquals(listOf("silence", "restore"), h.callActions)
        assertTrue(h.logged.isEmpty())
        assertEquals(1, h.resumes)
    }

    @Test fun callTakesOverARunningInteraction() = runTest {
        val first = CompletableDeferred<SpeechResult>()
        val h = harness(speech = listOf({ first.await() }, { SpeechResult.Text("answer") }))
        h.orchestrator.trigger()
        runCurrent()
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()

        assertEquals(0, h.interpretCalls)
        assertEquals(listOf("silence", "answer", "restore"), h.callActions)
        assertEquals(2, h.pauses)
        assertEquals(2, h.resumes)
    }

    @Test fun duplicateRingingBroadcastIsIgnored() = runTest {
        val h = harness(speech = listOf({ SpeechResult.Text("reject") }))
        h.orchestrator.announceCall(kingCall)
        h.orchestrator.announceCall(kingCall)
        advanceUntilIdle()
        assertEquals(1, h.spoken.count { "is calling" in it })
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

    private val draft = AssistantCommand.SendWhatsApp("junior", "Sorry, I'm running late.", composed = true)

    @Test fun composedMessageIsReadBackAndSentOnYes() = runTest {
        val h = harness(
            command = draft,
            speech = listOf({ SpeechResult.Text("message junior saying sorry I'm late, politely") }, { SpeechResult.Text("yes send it") }),
        )
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertEquals("Here's your message to Rahul: Sorry, I'm running late. Shall I send it?", h.spoken.first())
        assertEquals(listOf(draft to junior), h.executed)
    }

    @Test fun composedMessageNotSentOnNo() = runTest {
        val h = harness(command = draft, speech = listOf({ SpeechResult.Text("text junior") }, { SpeechResult.Text("no, don't") }))
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertTrue(h.executed.isEmpty())
        assertEquals("Okay, I won't send it.", h.spoken.last())
        assertEquals(ActionStatus.CANCELLED, h.logged.single().status)
    }

    @Test fun composedMessageAsksOnceMoreThenStaysUnsent() = runTest {
        val h = harness(command = draft, speech = listOf({ SpeechResult.Text("text junior") }, { SpeechResult.Text("hmm") }))
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertTrue(h.executed.isEmpty())
        assertTrue("Should I send it? Say yes or no." in h.spoken)
    }

    @Test fun composedMessageSkipsCountdownAndAcceptsTap() = runTest {
        val never = CompletableDeferred<SpeechResult>()
        val h = harness(
            command = draft,
            settings = baseSettings.copy(confirmationCountdownEnabled = true, confirmationSeconds = 3),
            speech = listOf({ SpeechResult.Text("text junior") }, { never.await() }),
        )
        h.orchestrator.trigger()
        runCurrent()

        val pending = h.orchestrator.state.value.confirmation
        assertTrue(pending!!.awaitingYes)
        assertEquals("Sorry, I'm running late.", pending.subtitle)
        h.orchestrator.confirmNow()
        advanceUntilIdle()
        assertEquals(1, h.executed.size)
        assertNull(h.orchestrator.state.value.confirmation)
    }

    @Test fun dictatedMessageIsNotReadBack() = runTest {
        val h = harness(command = AssistantCommand.SendWhatsApp("junior", "hi"))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertTrue(h.spoken.none { "Shall I send it" in it })
        assertEquals(1, h.executed.size)
    }

    @Test fun dueReminderIsSpokenEvenWithVoiceRepliesOff() = runTest {
        val h = harness(settings = baseSettings.copy(voiceReplyEnabled = false), speech = emptyList())
        h.orchestrator.announceReminder("call King")
        advanceUntilIdle()

        assertEquals(listOf("Reminder: call King."), h.spoken)
        assertEquals(ActionKind.REMINDER, h.orchestrator.state.value.cards.first().kind)
        assertEquals(1, h.pauses)
        assertEquals(1, h.resumes)
        assertFalse(h.orchestrator.isBusy)
    }

    @Test fun dueReminderDoesNotInterruptAConversation() = runTest {
        val never = CompletableDeferred<SpeechResult>()
        val h = harness(speech = listOf({ never.await() }))
        h.orchestrator.trigger()
        runCurrent()
        h.orchestrator.announceReminder("call King")
        runCurrent()
        assertTrue(h.spoken.none { "Reminder" in it })
        h.orchestrator.cancel()
        advanceUntilIdle()
    }

    // --- Follow-up mode ---

    private val followUpSettings = baseSettings.copy(followUpMode = true)

    @Test fun followUpKeepsContextSoHimResolves() = runTest {
        val h = harness(
            settings = followUpSettings,
            speech = listOf({ SpeechResult.Text("what did king say") }, { SpeechResult.Text("call him") }),
        )
        h.interpretQueue += InterpretResult.Success(AssistantCommand.Answer("King says he's outside"))
        h.interpretQueue += InterpretResult.Success(AssistantCommand.CallContact("King"))
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertEquals(listOf("what did king say", "call him"), h.interpreted.map { it.first })
        assertTrue(h.interpreted[0].second.isEmpty())
        val earlier = h.interpreted[1].second.single()
        assertEquals("what did king say", earlier.user)
        assertTrue(earlier.assistant.contains("King says he's outside"))
        assertEquals(listOf(AssistantCommand.CallContact("King") to king), h.executed)
        // The call hands the phone over, so Jarvis stops listening after it.
        assertEquals(listOf(0L, FollowUp.WINDOW_MS), h.listenTimeouts)
        assertEquals("only the first listen chimes", 1, h.chimes)
        assertEquals(1, h.resumes)
        assertEquals(AssistantPhase.IDLE, h.orchestrator.state.value.phase)
        assertFalse(h.orchestrator.state.value.followUp)
    }

    @Test fun followUpListensOnlyAfterSpeechEnds() = runTest {
        val h = harness(settings = followUpSettings, command = AssistantCommand.Answer("It's sunny"), speech = listOf({ SpeechResult.Text("weather") }))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf("listen", "speak", "listen"), h.events)
    }

    @Test fun silenceEndsTheConversationQuietly() = runTest {
        val h = harness(settings = followUpSettings, command = AssistantCommand.Answer("It's sunny"), speech = listOf({ SpeechResult.Text("weather") }))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf("It's sunny"), h.spoken)
        assertEquals(1, h.interpretCalls)
        assertEquals(1, h.resumes)
    }

    @Test fun thanksEndsTheConversation() = runTest {
        val h = harness(
            settings = followUpSettings,
            command = AssistantCommand.Answer("It's sunny"),
            speech = listOf({ SpeechResult.Text("weather") }, { SpeechResult.Text("okay thanks, that's all") }),
        )
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(1, h.interpretCalls)
        assertEquals(listOf("It's sunny"), h.spoken)
    }

    @Test fun waitingShowsStillListeningAndTapEndsIt() = runTest {
        val never = CompletableDeferred<SpeechResult>()
        val h = harness(
            settings = followUpSettings,
            command = AssistantCommand.Answer("It's sunny"),
            speech = listOf({ SpeechResult.Text("weather") }, { never.await() }),
        )
        h.orchestrator.trigger()
        advanceTimeBy(FollowUp.AFTER_SPEECH_GAP_MS + 10)
        val waiting = h.orchestrator.state.value
        assertTrue(waiting.followUp)
        assertEquals(AssistantPhase.LISTENING, waiting.phase)
        assertEquals("It's sunny", waiting.reply)

        h.orchestrator.cancel()
        advanceUntilIdle()
        assertFalse(h.orchestrator.state.value.followUp)
        assertEquals(AssistantPhase.IDLE, h.orchestrator.state.value.phase)
        assertEquals(1, h.resumes)
    }

    @Test fun typedTextBecomesTheFollowUp() = runTest {
        val never = CompletableDeferred<SpeechResult>()
        val h = harness(settings = followUpSettings, command = AssistantCommand.Answer("Sunny"), speech = listOf({ never.await() }))
        h.orchestrator.triggerText("weather today")
        advanceTimeBy(FollowUp.AFTER_SPEECH_GAP_MS + 10)
        assertTrue(h.orchestrator.state.value.followUp)
        h.orchestrator.triggerText("and tomorrow?")
        advanceUntilIdle()
        assertEquals(listOf("weather today", "and tomorrow?"), h.interpreted.map { it.first })
        assertEquals(1, h.interpreted[1].second.size)
    }

    @Test fun followUpModeOffListensOnce() = runTest {
        val h = harness(command = AssistantCommand.Answer("Sunny"), speech = listOf({ SpeechResult.Text("weather") }, { SpeechResult.Text("and tomorrow") }))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf(0L), h.listenTimeouts)
        assertEquals(1, h.interpretCalls)
    }

    @Test fun noFollowUpAfterABackendError() = runTest {
        val h = harness(settings = followUpSettings, interpretResult = InterpretResult.Failure("The backend is down."))
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(listOf(0L), h.listenTimeouts)
        assertEquals(AssistantPhase.ERROR, h.orchestrator.state.value.phase)
    }

    @Test fun countdownStillWorksInAFollowUp() = runTest {
        val h = harness(
            settings = followUpSettings.copy(confirmationCountdownEnabled = true, confirmationSeconds = 3),
            speech = listOf({ SpeechResult.Text("hi") }, { SpeechResult.Text("call king") }),
        )
        h.interpretQueue += InterpretResult.Success(AssistantCommand.Answer("Hello"))
        h.interpretQueue += InterpretResult.Success(AssistantCommand.CallContact("King"))
        h.orchestrator.trigger()
        advanceTimeBy(FollowUp.AFTER_SPEECH_GAP_MS + 10)
        assertEquals(3, h.orchestrator.state.value.confirmation?.secondsLeft)
        h.orchestrator.cancel()
        advanceUntilIdle()
        assertTrue(h.executed.isEmpty())
        assertEquals(listOf("Hello", "Cancelled"), h.spoken)
        // A cancelled countdown leaves the conversation open for "no, call Mom instead".
        assertEquals(listOf(0L, FollowUp.WINDOW_MS, FollowUp.WINDOW_MS), h.listenTimeouts)
    }

    @Test fun spokenChangeRewritesTheDraft() = runTest {
        val shorter = AssistantCommand.SendWhatsApp("Rahul", "Sorry, late!", composed = true)
        val h = harness(
            settings = followUpSettings,
            command = draft,
            speech = listOf(
                { SpeechResult.Text("text junior sorry I'm late, politely") },
                { SpeechResult.Text("make it shorter") },
                { SpeechResult.Text("yes send it") },
            ),
        )
        h.interpretQueue += InterpretResult.Success(draft)
        h.interpretQueue += InterpretResult.Success(shorter)
        h.orchestrator.trigger()
        advanceUntilIdle()

        assertEquals(listOf(shorter to junior), h.executed)
        assertEquals("make it shorter", h.interpreted[1].first)
        val earlier = h.interpreted[1].second.single().assistant
        assertTrue(earlier, earlier.contains("not sent yet") && earlier.contains(draft.message))
    }

    @Test fun draftChangeNeedsFollowUpMode() = runTest {
        val h = harness(
            command = draft,
            speech = listOf({ SpeechResult.Text("text junior") }, { SpeechResult.Text("make it shorter") }, { SpeechResult.Text("no") }),
        )
        h.orchestrator.trigger()
        advanceUntilIdle()
        assertEquals(1, h.interpretCalls)
        assertTrue(h.executed.isEmpty())
    }
}
