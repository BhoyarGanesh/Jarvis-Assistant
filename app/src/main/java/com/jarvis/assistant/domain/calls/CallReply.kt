package com.jarvis.assistant.domain.calls

/** Who is ringing. Either part may be missing (no call log access, private number). */
data class IncomingCall(val callerName: String?, val number: String?)

sealed interface CallDecision {
    data object Answer : CallDecision
    data object Reject : CallDecision
    /** Decline and text the caller [text]. */
    data class DeclineWithMessage(val text: String) : CallDecision
    data object Unclear : CallDecision
}

/**
 * Understands the short reply to "King is calling": "answer", "reject",
 * "tell him I'll call back in 10 minutes". Parsed on the phone because a ringing call
 * can't wait for a backend round trip.
 */
object CallReplyParser {
    private val MESSAGE = Regex(
        "^(?:(?:tell|text|message|reply to|say to)\\s+(?:him|her|them|it)\\b|(?:say|reply|text|message)\\b)" +
            "(?:\\s+(?:that|saying|ki))?[\\s,:]+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val NEGATED_ANSWER = Regex("\\b(?:don'?t|do not|dont|mat)\\s+(?:answer|pick|receive|uthao|utha)", RegexOption.IGNORE_CASE)
    private val REJECT = Regex(
        "\\b(?:reject|decline|ignore|cut|hang up|end it|not now|no|nope|later|busy|kaat|kat do|mana)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ANSWER = Regex(
        "\\b(?:answer|pick(?: it)? up|accept|take it|attend|receive|yes|yeah|ok(?:ay)?|sure|haan|han|uthao|utha lo)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(spoken: String): CallDecision {
        val text = spoken.trim().replace(Regex("^jarvis[,\\s]*", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', '!')
        if (text.isEmpty()) return CallDecision.Unclear
        MESSAGE.find(text)?.let { match ->
            val message = match.groupValues[1].trim()
            if (message.isNotEmpty()) return CallDecision.DeclineWithMessage(message.replaceFirstChar { it.uppercase() })
        }
        return when {
            NEGATED_ANSWER.containsMatchIn(text) -> CallDecision.Reject
            REJECT.containsMatchIn(text) -> CallDecision.Reject
            ANSWER.containsMatchIn(text) -> CallDecision.Answer
            else -> CallDecision.Unclear
        }
    }
}
