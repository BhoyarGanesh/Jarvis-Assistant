package com.jarvis.assistant.domain.conversation

import com.jarvis.assistant.domain.ActionStatus
import com.jarvis.assistant.domain.model.AssistantCommand

/** One exchange of a conversation, as the backend sees it: what the user said and what Jarvis did or said. */
data class Turn(val user: String, val assistant: String)

/**
 * The last few turns of the current conversation, so a follow-up like "call him" or "and tomorrow?"
 * can be resolved. Lives only as long as one conversation; nothing is stored.
 */
class ConversationHistory(private val maxTurns: Int = MAX_TURNS) {
    private val turns = ArrayDeque<Turn>()

    val recent: List<Turn> get() = turns.toList()

    fun add(user: String, assistant: String) {
        turns.addLast(Turn(user.trim().take(MAX_CHARS), assistant.trim().take(MAX_CHARS)))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    companion object {
        const val MAX_TURNS = 4
        const val MAX_CHARS = 400
    }
}

object FollowUp {
    /** How long Jarvis waits for a follow-up to start after she finishes speaking. */
    const val WINDOW_MS = 7_000L

    /** Gap after her voice ends before the microphone opens, so the room's echo of it isn't heard. */
    const val AFTER_SPEECH_GAP_MS = 300L

    private val ENDINGS = listOf(
        "that's all", "thats all", "that is all", "that's it", "thats it", "that will be all", "nothing else",
        "no thanks", "no thank you", "thank you", "thanks", "thank", "thx", "stop", "never mind", "nevermind",
        "nothing", "bye", "goodbye", "good bye", "see you", "cancel", "done", "i'm done", "im done", "i am done",
        "all good", "got it", "shukriya", "dhanyavaad", "dhanyavad", "bas", "ruko", "theek hai bas",
        "धन्यवाद", "शुक्रिया", "बस", "रुको",
    ).sortedByDescending { it.length }

    private val FILLERS = setOf("ok", "okay", "jarvis", "so", "much", "a", "lot", "very", "you", "for", "now", "all", "great", "cool", "yeah", "hey", "please")

    /**
     * True when a follow-up only closes the conversation ("thanks", "that's all", "okay stop, thank you"),
     * false when it carries a request ("thanks, and call Mom").
     */
    fun isEnd(spoken: String): Boolean {
        var text = " " + spoken.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{M}' ]+"), " ") + " "
        if (text.isBlank()) return false
        for (phrase in ENDINGS) text = text.replace(" $phrase ", " ").replace(" $phrase ", " ")
        val rest = text.split(' ').filter { it.isNotBlank() }
        return rest.size < spoken.split(Regex("\\s+")).count { it.isNotBlank() } && rest.all { it in FILLERS }
    }

    /**
     * An answer to "Shall I send it?" that is neither yes nor no but asks for a change ("make it shorter",
     * "say it in Hindi"). One-word mumbles don't count, so they still get "please say yes or no".
     */
    fun isDraftChange(spoken: String): Boolean {
        val words = spoken.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
        return words >= 2 && !isEnd(spoken)
    }

    /**
     * Actions that hand the phone over to something else (a call, another app, music, the ringing
     * find-my-phone alarm). Listening on afterwards would hear that instead of the user.
     */
    fun endsConversation(command: AssistantCommand, status: ActionStatus): Boolean {
        if (status == ActionStatus.FAILED || status == ActionStatus.CANCELLED) return false
        return when (command) {
            is AssistantCommand.CallContact, AssistantCommand.FindPhone, is AssistantCommand.PlayMusic,
            is AssistantCommand.OpenApp, is AssistantCommand.WebSearch -> true
            else -> false
        }
    }
}
