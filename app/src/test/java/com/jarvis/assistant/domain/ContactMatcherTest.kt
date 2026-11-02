package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.contacts.ChoiceResult
import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.contacts.ContactMatch
import com.jarvis.assistant.domain.contacts.ContactMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    private val king = Contact(1, "King", "+911111111111")
    private val junior = Contact(2, "Rahul Sharma", "+912222222222", nicknames = listOf("Junior"))
    private val mom = Contact(3, "Mom", "+913333333333")
    private val kingKumar = Contact(4, "King Kumar", "+914444444444")
    private val kingSingh = Contact(5, "King Singh", "+915555555555")
    private val priya = Contact(6, "Priya Reddy", "+916666666666")
    private val josé = Contact(7, "José Álvarez", "+917777777777")

    private fun single(spoken: String, contacts: List<Contact>): Contact {
        val m = ContactMatcher.match(spoken, contacts)
        assertTrue("expected single match for '$spoken' but was $m", m is ContactMatch.Single)
        return (m as ContactMatch.Single).contact
    }

    @Test fun exactNameIsCaseInsensitive() {
        assertEquals(king, single("KING", listOf(king, junior, mom)))
    }

    @Test fun nicknameMatches() {
        assertEquals(junior, single("junior", listOf(king, junior, mom)))
    }

    @Test fun exactNameBeatsPartialName() {
        assertEquals(king, single("king", listOf(king, kingKumar, kingSingh)))
    }

    @Test fun firstNameMatchesFullName() {
        assertEquals(priya, single("priya", listOf(king, priya, mom)))
    }

    @Test fun sharedFirstNameIsAmbiguous() {
        val m = ContactMatcher.match("king", listOf(kingKumar, kingSingh, mom))
        assertTrue(m is ContactMatch.Ambiguous)
        assertEquals(setOf(kingKumar, kingSingh), (m as ContactMatch.Ambiguous).options.toSet())
    }

    @Test fun fullNameDisambiguates() {
        assertEquals(kingSingh, single("King Singh", listOf(kingKumar, kingSingh)))
    }

    @Test fun recognitionTypoStillMatches() {
        assertEquals(priya, single("pria", listOf(king, priya, mom)))
    }

    @Test fun diacriticsAndPossessivesAreIgnored() {
        assertEquals(josé, single("jose alvarez", listOf(josé, king)))
        assertEquals(mom, single("my mom", listOf(josé, mom)))
    }

    @Test fun unknownNameIsNotFound() {
        assertEquals(ContactMatch.NotFound, ContactMatcher.match("zebra", listOf(king, junior, mom)))
        assertEquals(ContactMatch.NotFound, ContactMatcher.match("  ", listOf(king)))
        assertEquals(ContactMatch.NotFound, ContactMatcher.match("king", emptyList()))
    }

    @Test fun pickChoiceByOrdinal() {
        val options = listOf(kingKumar, kingSingh)
        assertEquals(ChoiceResult.Picked(kingKumar), ContactMatcher.pickChoice("the first one", options))
        assertEquals(ChoiceResult.Picked(kingSingh), ContactMatcher.pickChoice("second one", options))
        assertEquals(ChoiceResult.Picked(kingSingh), ContactMatcher.pickChoice("last", options))
        assertEquals(ChoiceResult.Picked(kingSingh), ContactMatcher.pickChoice("2", options))
    }

    @Test fun pickChoiceByName() {
        val options = listOf(kingKumar, kingSingh)
        assertEquals(ChoiceResult.Picked(kingSingh), ContactMatcher.pickChoice("Singh", options))
        assertEquals(ChoiceResult.Picked(kingKumar), ContactMatcher.pickChoice("king kumar", options))
    }

    @Test fun pickChoiceCancelAndGarbage() {
        val options = listOf(kingKumar, kingSingh)
        assertEquals(ChoiceResult.Cancelled, ContactMatcher.pickChoice("never mind", options))
        assertEquals(ChoiceResult.Cancelled, ContactMatcher.pickChoice("cancel", options))
        assertEquals(ChoiceResult.Unrecognized, ContactMatcher.pickChoice("banana", options))
    }

    @Test fun jaroWinklerSanity() {
        assertEquals(1.0, ContactMatcher.jaroWinkler("abc", "abc"), 0.0)
        assertEquals(0.0, ContactMatcher.jaroWinkler("abc", ""), 0.0)
        assertTrue(ContactMatcher.jaroWinkler("martha", "marhta") > 0.95)
    }
}
