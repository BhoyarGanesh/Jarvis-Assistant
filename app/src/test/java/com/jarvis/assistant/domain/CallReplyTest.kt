package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.calls.CallDecision
import com.jarvis.assistant.domain.calls.CallReplyParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CallReplyTest {
    private fun parse(text: String) = CallReplyParser.parse(text)

    @Test fun answers() {
        for (text in listOf("answer", "Answer it", "pick up", "pick it up", "yes", "accept", "haan utha lo", "Jarvis, answer.")) {
            assertEquals(text, CallDecision.Answer, parse(text))
        }
    }

    @Test fun rejects() {
        for (text in listOf("reject", "decline it", "ignore", "cut the call", "not now", "no", "don't answer", "do not pick up", "kaat do")) {
            assertEquals(text, CallDecision.Reject, parse(text))
        }
    }

    @Test fun declinesWithAMessage() {
        val cases = mapOf(
            "tell him I'll call back in 10 minutes" to "I'll call back in 10 minutes",
            "Tell her that I'm driving" to "I'm driving",
            "text them I'm in a meeting" to "I'm in a meeting",
            "message him saying call you later" to "Call you later",
            "say I'm busy" to "I'm busy",
            "reply on my way" to "On my way",
            "Jarvis tell him I'm busy right now" to "I'm busy right now",
        )
        for ((spoken, message) in cases) {
            assertEquals(spoken, CallDecision.DeclineWithMessage(message), parse(spoken))
        }
    }

    @Test fun unclear() {
        for (text in listOf("", "   ", "who is it", "hmm")) {
            assertEquals(text, CallDecision.Unclear, parse(text))
        }
    }
}
