package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.wakeword.WakePhrase
import com.jarvis.assistant.domain.wakeword.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    private val dictionary = setOf("jarvis", "friday", "hey", "maya", "nova", "computer", "sam")
    private val known: (String) -> Boolean? = { it in dictionary }

    @Test fun normalizesWhatWasTyped() {
        assertEquals("hey maya", WakePhrase.normalize("  Hey, MAYA! "))
        assertEquals("friday", WakePhrase.normalize("Friday’"))
        assertEquals("", WakePhrase.normalize("123 !!"))
    }

    @Test fun acceptsKnownNames() {
        assertEquals(WakePhrase.Check.Ok("friday"), WakePhrase.check("Friday", known))
        assertEquals(WakePhrase.Check.Ok("hey maya"), WakePhrase.check("Hey Maya", known))
    }

    @Test fun rejectsWordsTheModelDoesNotKnow() {
        val check = WakePhrase.check("Hey Zorbulax", known)
        assertTrue(check is WakePhrase.Check.Invalid)
        assertTrue((check as WakePhrase.Check.Invalid).reason.contains("zorbulax"))
    }

    @Test fun acceptsWhenTheDictionaryCannotBeChecked() {
        assertEquals(WakePhrase.Check.Ok("zorbulax"), WakePhrase.check("Zorbulax") { null })
    }

    @Test fun rejectsEmptyLongAndEverydayPhrases() {
        assertTrue(WakePhrase.check("", known) is WakePhrase.Check.Invalid)
        assertTrue(WakePhrase.check("hey there you computer", known) is WakePhrase.Check.Invalid)
        assertTrue(WakePhrase.check("okay", known) is WakePhrase.Check.Invalid)
        assertTrue(WakePhrase.check("hey you", known) is WakePhrase.Check.Invalid)
        assertTrue(WakePhrase.check("supercalifragilistic expialidocious", { true }) is WakePhrase.Check.Invalid)
    }

    @Test fun warnsAboutShortWords() {
        val short = WakePhrase.check("Sam", known) as WakePhrase.Check.Ok
        assertNotNull(short.warning)
        assertNull((WakePhrase.check("Computer", known) as WakePhrase.Check.Ok).warning)
        assertNull((WakePhrase.check("Jarvis", known) as WakePhrase.Check.Ok).warning)
    }

    @Test fun countsSyllablesRoughly() {
        assertEquals(2, WakePhrase.syllables("jarvis"))
        assertEquals(3, WakePhrase.syllables("computer"))
        assertEquals(1, WakePhrase.syllables("sam"))
        assertEquals(1, WakePhrase.syllables("kate"))
        assertEquals(3, WakePhrase.syllables("hey maya"))
    }

    @Test fun stripsThePhraseFromTheStartOfARequest() {
        assertEquals("call King", WakePhrase.stripLeading("Friday, call King", "friday"))
        assertEquals("what time is it", WakePhrase.stripLeading("hey maya what time is it", "hey maya"))
        assertEquals("call Friday's office", WakePhrase.stripLeading("call Friday's office", "friday"))
        assertEquals("Friday", WakePhrase.stripLeading("Friday", "friday"))
    }

    @Test fun displaysTitleCase() {
        assertEquals("Hey Maya", WakePhrase.display("hey maya"))
        assertEquals("Jarvis", WakePhrase.display(""))
    }
}

class CustomWakeWordDetectorTest {
    private var now = 0L
    private fun word(w: String, conf: Double) = """{"conf":$conf,"word":"$w"}"""

    @Test fun grammarHoldsThePhraseAndUnknown() {
        assertEquals("""["hey maya","[unk]"]""", WakeWordDetector("Hey Maya").grammar)
        assertEquals("""["jarvis","[unk]"]""", WakeWordDetector().grammar)
    }

    @Test fun multiWordPhraseNeedsEveryWordInOrder() {
        val d = WakeWordDetector("hey maya", sensitivity = 0.5f, debounceMs = 0, clock = { now })
        assertTrue(d.onResult("""{"result":[${word("[unk]", 1.0)},${word("hey", 0.9)},${word("maya", 0.8)}]}"""))
        assertFalse(d.onResult("""{"result":[${word("maya", 0.9)},${word("hey", 0.9)}]}"""))
        assertFalse("one word heard too weakly", d.onResult("""{"result":[${word("hey", 0.9)},${word("maya", 0.3)}]}"""))
        assertFalse(d.onResult("""{"result":[${word("hey", 0.9)}]}"""))
    }

    @Test fun singleCustomWordAndPartials() {
        val d = WakeWordDetector("Friday", sensitivity = 0.9f, debounceMs = 0, clock = { now })
        assertTrue(d.onResult("""{"result":[${word("friday", 0.9)}]}"""))
        assertFalse(d.onResult("""{"result":[${word("jarvis", 0.99)}]}"""))
        assertTrue(d.onPartial("""{"partial":"[unk] friday"}"""))
        assertTrue(d.onResult("""{"text":"friday"}"""))
    }
}
