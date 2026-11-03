package com.jarvis.assistant.domain

enum class YesNo { YES, NO, UNCLEAR }

/** Understands a spoken yes/no ("yes send it", "haan bhejo", "no don't"). Negatives win over positives. */
object YesNoParser {
    private val NO = Regex(
        "\\b(?:no|nope|nah|don'?t|do not|cancel|stop|wait|never ?mind|nahi|nahin|na|mat|ruko)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val YES = Regex(
        "\\b(?:yes|yeah|yep|yup|sure|ok(?:ay)?|send(?: it)?|go ahead|do it|correct|perfect|fine|haan|han|ha|ji|bhejo|bhej do|theek hai|thik hai)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val WAKE_WORD = Regex("^jarvis[,\\s]*", RegexOption.IGNORE_CASE)

    fun parse(spoken: String): YesNo {
        val text = spoken.trim().replace(WAKE_WORD, "")
        return when {
            text.isBlank() -> YesNo.UNCLEAR
            NO.containsMatchIn(text) || text.contains("नहीं") || text.contains("मत") -> YesNo.NO
            YES.containsMatchIn(text) || text.contains("हाँ") || text.contains("हां") || text.contains("भेज") -> YesNo.YES
            else -> YesNo.UNCLEAR
        }
    }
}
