package com.jarvis.assistant.domain.contacts

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class Contact(
    val id: Long,
    val displayName: String,
    val phoneNumber: String,
    val nicknames: List<String> = emptyList(),
)

sealed interface ContactMatch {
    data class Single(val contact: Contact) : ContactMatch
    data class Ambiguous(val options: List<Contact>) : ContactMatch
    data object NotFound : ContactMatch
}

sealed interface ChoiceResult {
    data class Picked(val contact: Contact) : ChoiceResult
    data object Cancelled : ChoiceResult
    data object Unrecognized : ChoiceResult
}

/**
 * Fuzzy, case-insensitive matching of a spoken name ("king", "junior") against contacts.
 * Exact name/nickname hits win outright; otherwise whole-word hits beat prefix hits,
 * which beat Jaro-Winkler similarity (to absorb speech recognition typos).
 */
object ContactMatcher {
    private const val MIN_SCORE = 0.78
    private const val TIE_MARGIN = 0.03
    private const val MAX_OPTIONS = 4

    fun match(spoken: String, contacts: List<Contact>): ContactMatch {
        val query = normalize(spoken)
        if (query.isEmpty()) return ContactMatch.NotFound

        val scored = contacts
            .map { it to score(query, it) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return ContactMatch.NotFound

        val best = scored.first().second
        val top = scored.takeWhile { best - it.second <= TIE_MARGIN }.map { it.first }
            .distinctBy { it.id }
        return if (top.size == 1) ContactMatch.Single(top.first())
        else ContactMatch.Ambiguous(top.take(MAX_OPTIONS))
    }

    fun score(query: String, contact: Contact): Double =
        (listOf(contact.displayName) + contact.nicknames)
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .maxOfOrNull { nameScore(query, it) } ?: 0.0

    private fun nameScore(query: String, name: String): Double {
        if (query == name) return 1.0
        val nameTokens = name.split(' ')
        val queryTokens = query.split(' ')
        if (queryTokens.size == 1 && query in nameTokens) return 0.92
        if (nameTokens.size > queryTokens.size && nameTokens.take(queryTokens.size) == queryTokens) return 0.9
        if (queryTokens.all { it in nameTokens }) return 0.88

        val whole = jaroWinkler(query, name)
        val bestToken = if (queryTokens.size == 1) nameTokens.maxOf { jaroWinkler(query, it) } else 0.0
        return max(whole * 0.95, bestToken * 0.9)
    }

    /** Resolves the user's spoken answer to "which one?" against the offered options. */
    fun pickChoice(spoken: String, options: List<Contact>): ChoiceResult {
        val answer = normalize(spoken)
        if (answer.isEmpty()) return ChoiceResult.Unrecognized
        val words = answer.split(' ').toSet()
        if (words.any { it in CANCEL_WORDS } || answer in CANCEL_PHRASES) return ChoiceResult.Cancelled

        // "one" doubles as filler ("the second one"), so it only counts when nothing else matched.
        val ordinalHits = ORDINALS.entries.filter { (word, _) -> word in words }
        (ordinalHits.firstOrNull { it.key != "one" } ?: ordinalHits.firstOrNull())?.let { (_, index) ->
            val i = if (index == LAST) options.lastIndex else index
            options.getOrNull(i)?.let { return ChoiceResult.Picked(it) }
        }

        return when (val m = match(answer, options)) {
            is ContactMatch.Single -> ChoiceResult.Picked(m.contact)
            // Every option shares the spoken word (e.g. "King"); try the distinguishing words only.
            is ContactMatch.Ambiguous -> options.firstOrNull { option ->
                val tokens = normalize(option.displayName).split(' ')
                words.any { it in tokens && options.count { o -> it in normalize(o.displayName).split(' ') } == 1 }
            }?.let(ChoiceResult::Picked) ?: ChoiceResult.Unrecognized
            ContactMatch.NotFound -> ChoiceResult.Unrecognized
        }
    }

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .replace(NON_WORD, " ")
            .trim()
            .replace(SPACES, " ")
        return decomposed.removePrefix("my ").removePrefix("the ")
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window) until min(b.length, i + window + 1)) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        val prefix = a.zip(b).take(4).takeWhile { (x, y) -> x == y }.size
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private const val LAST = -1
    private val ORDINALS = linkedMapOf(
        "first" to 0, "one" to 0, "1" to 0, "1st" to 0, "pehla" to 0, "pehle" to 0,
        "second" to 1, "two" to 1, "2" to 1, "2nd" to 1, "doosra" to 1, "dusra" to 1,
        "third" to 2, "three" to 2, "3" to 2, "3rd" to 2, "teesra" to 2,
        "fourth" to 3, "four" to 3, "4" to 3, "4th" to 3,
        "last" to LAST,
    )
    private val CANCEL_WORDS = setOf("cancel", "none", "neither", "stop", "nobody")
    private val CANCEL_PHRASES = setOf("never mind", "nevermind", "forget it")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val SPACES = Regex("\\s+")
}
