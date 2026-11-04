package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.conversation.ConversationHistory
import com.jarvis.assistant.domain.conversation.FollowUp
import com.jarvis.assistant.domain.model.AssistantCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpTest {

    @Test fun closingPhrasesEndTheConversation() {
        listOf(
            "thanks", "Thank you!", "that's all", "That’s it, thanks", "okay stop", "no thanks", "never mind",
            "bye Jarvis", "thank you so much", "बस", "shukriya", "ok thanks a lot",
        ).forEach { assertTrue(it, FollowUp.isEnd(it)) }
    }

    @Test fun requestsDoNotEndIt() {
        listOf(
            "and tomorrow?", "call him", "thanks, and call Mom", "make it shorter", "stop the timer", "okay", "",
            "what's the weather",
        ).forEach { assertFalse(it, FollowUp.isEnd(it)) }
    }

    @Test fun draftChangesNeedARealRequest() {
        assertTrue(FollowUp.isDraftChange("make it shorter"))
        assertTrue(FollowUp.isDraftChange("say it in Hindi"))
        assertFalse(FollowUp.isDraftChange("hmm"))
        assertFalse(FollowUp.isDraftChange("thank you"))
        assertFalse(FollowUp.isDraftChange(""))
    }

    @Test fun handingThePhoneOverEndsTheConversation() {
        assertTrue(FollowUp.endsConversation(AssistantCommand.CallContact("King"), ActionStatus.SUCCESS))
        assertTrue(FollowUp.endsConversation(AssistantCommand.PlayMusic("lofi"), ActionStatus.SUCCESS))
        assertTrue(FollowUp.endsConversation(AssistantCommand.FindPhone, ActionStatus.SUCCESS))
        assertFalse("a failed call leaves room for a retry", FollowUp.endsConversation(AssistantCommand.CallContact("King"), ActionStatus.FAILED))
        assertFalse(FollowUp.endsConversation(AssistantCommand.SetTimer(60, ""), ActionStatus.SUCCESS))
        assertFalse(FollowUp.endsConversation(AssistantCommand.SendWhatsApp("King", "hi"), ActionStatus.SUCCESS))
    }

    @Test fun historyKeepsTheLastTurnsTrimmed() {
        val history = ConversationHistory(maxTurns = 2)
        history.add("one", "1")
        history.add("two", "2")
        history.add("  three  ", "x".repeat(1_000))
        assertEquals(listOf("two", "three"), history.recent.map { it.user })
        assertEquals(ConversationHistory.MAX_CHARS, history.recent.last().assistant.length)
    }
}
