package com.jarvis.assistant.domain.wakeword

/** The word or short phrase that wakes Jarvis ("jarvis", "friday", "hey maya"), as the user chose it. */
object WakePhrase {
    const val DEFAULT = "jarvis"
    const val MAX_WORDS = 3
    const val MAX_CHARS = 30

    sealed interface Check {
        /** [phrase] is normalized and ready to save; [warning] is advice, not a blocker. */
        data class Ok(val phrase: String, val warning: String? = null) : Check
        data class Invalid(val reason: String) : Check
    }

    /** Words that come up in ordinary talk all the time: as a whole wake word they'd fire constantly. */
    private val EVERYDAY = setOf(
        "hey", "hi", "hello", "ok", "okay", "yes", "no", "yeah", "the", "a", "and", "so", "now", "go", "stop",
        "what", "how", "you", "i", "me", "it", "is", "this", "that", "please", "thanks", "phone", "google", "siri", "alexa",
    )

    /** Lowercase letters and apostrophes, single spaces: "  Hey, MAYA! " -> "hey maya". */
    fun normalize(input: String): String =
        input.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z' ]"), " ")
            .split(' ')
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * Checks a phrase the user typed. [isKnownWord] says whether the offline model can recognize a word
     * (null when that can't be checked right now, e.g. the model isn't loaded yet).
     */
    fun check(input: String, isKnownWord: (String) -> Boolean?): Check {
        val phrase = normalize(input)
        val words = phrase.split(' ').filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> Check.Invalid("Type a name or a short phrase, for example Friday or Hey Maya.")
            words.size > MAX_WORDS -> Check.Invalid("Use at most $MAX_WORDS words.")
            phrase.length > MAX_CHARS -> Check.Invalid("Keep it under $MAX_CHARS letters.")
            words.all { it in EVERYDAY } -> Check.Invalid("“${display(phrase)}” comes up in normal talk too often. Pick a name, like Friday or Nova.")
            else -> {
                val unknown = words.filter { isKnownWord(it) == false }
                if (unknown.isNotEmpty()) {
                    Check.Invalid(
                        "The offline model doesn't know “${unknown.joinToString(" ")}”, so it can't listen for it. " +
                            "Try a more common name or word.",
                    )
                } else {
                    val warning = if (syllables(phrase) < 2) {
                        "Short wake words go off by accident more often. Two or three syllables work best."
                    } else null
                    Check.Ok(phrase, warning)
                }
            }
        }
    }

    /** Rough English syllable count: groups of vowels, minus a silent final e. */
    fun syllables(phrase: String): Int = phrase.split(' ').filter { it.isNotEmpty() }.sumOf { word ->
        // A y before a vowel is a consonant ("maya"), otherwise a vowel ("hey", "sky").
        val groups = Regex("[aeiou]+y?|y(?![aeiou])").findAll(word).count()
        val silentE = word.length > 2 && word.endsWith('e') && !word.endsWith("le") && groups > 1
        maxOf(1, groups - if (silentE) 1 else 0)
    }

    /** "hey maya" -> "Hey Maya", for labels. */
    fun display(phrase: String): String =
        normalize(phrase).ifEmpty { DEFAULT }.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Drops the wake phrase (and a comma after it) from the start of a transcript: "Friday, call King" -> "call King". */
    fun stripLeading(text: String, phrase: String): String {
        val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val pattern = Regex("^\\s*" + words.joinToString("[\\s,]+") { Regex.escape(it) } + "\\b[\\s,.!?]*", RegexOption.IGNORE_CASE)
        val stripped = text.replace(pattern, "")
        return stripped.ifBlank { text }
    }

    /** True if [words] (lowercase) contain the phrase's words in order, side by side. */
    fun containsPhrase(words: List<String>, phrase: String): Boolean {
        val target = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        if (target.isEmpty() || words.size < target.size) return false
        return (0..words.size - target.size).any { start -> target.indices.all { words[start + it] == target[it] } }
    }
}
