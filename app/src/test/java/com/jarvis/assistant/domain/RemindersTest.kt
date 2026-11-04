package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.model.AssistantCommand
import com.jarvis.assistant.domain.model.CommandParser
import com.jarvis.assistant.domain.model.needsConfirmation
import com.jarvis.assistant.domain.reminders.CalendarEvent
import com.jarvis.assistant.domain.reminders.Reminder
import com.jarvis.assistant.domain.reminders.ReminderMatcher
import com.jarvis.assistant.domain.reminders.ReminderTime
import com.jarvis.assistant.domain.reminders.ReminderTimes
import com.jarvis.assistant.domain.reminders.SpokenWhen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RemindersTest {
    // Wednesday 4 November 2026, 2:10 PM.
    private val now = LocalDateTime.of(2026, 11, 4, 14, 10)
    private val en = Phrases(hindi = false)

    private fun parse(tool: String, args: String) = CommandParser.parse(tool, Json.parseToJsonElement(args).jsonObject)

    @Test fun parsesReminderAndCalendarTools() {
        assertEquals(
            AssistantCommand.SetReminder("call King", LocalDateTime.of(2026, 11, 4, 18, 0)),
            parse("set_reminder", """{"text":"call King","when":"2026-11-04T18:00"}"""),
        )
        assertEquals(AssistantCommand.ListReminders, parse("list_reminders", "{}"))
        assertEquals(AssistantCommand.CancelReminder("King"), parse("cancel_reminder", """{"text":"King"}"""))
        assertEquals(
            AssistantCommand.AddCalendarEvent("Meeting with Ravi", LocalDateTime.of(2026, 11, 6, 15, 0), 60, ""),
            parse("add_calendar_event", """{"title":"Meeting with Ravi","start":"2026-11-06T15:00","duration_minutes":60,"location":""}"""),
        )
        assertEquals(
            AssistantCommand.ReadCalendar(LocalDate.of(2026, 11, 4), 7),
            parse("read_calendar", """{"date":"2026-11-04","days":7}"""),
        )
    }

    @Test fun badDatesFallBackToAnswer() {
        val fallback = AssistantCommand.Answer(CommandParser.FALLBACK)
        assertEquals(fallback, parse("set_reminder", """{"text":"call King","when":"tomorrow"}"""))
        assertEquals(fallback, parse("set_reminder", """{"text":"call King","when":"2026-13-40T25:00"}"""))
        assertEquals(fallback, parse("add_calendar_event", """{"title":"x","start":"2026-11-06T15:00","duration_minutes":2}"""))
        assertEquals(fallback, parse("read_calendar", """{"date":"2026-11-04","days":30}"""))
        assertFalse(AssistantCommand.SetReminder("x", now).needsConfirmation)
    }

    @Test fun futureTimesAreKeptAndJustPassedOnesMoveToTheNextDay() {
        assertEquals(ReminderTime.At(now.plusHours(4)), ReminderTimes.resolve(now.plusHours(4), now))
        // "at 6" heard as 6 AM today at 2 PM: tomorrow 6 AM.
        assertEquals(ReminderTime.At(LocalDateTime.of(2026, 11, 5, 6, 0), movedToNextDay = true), ReminderTimes.resolve(LocalDateTime.of(2026, 11, 4, 6, 0), now))
        assertEquals(ReminderTime.Past, ReminderTimes.resolve(now.minusDays(3), now))
    }

    @Test fun spokenWhenIsRelative() {
        assertEquals("in 20 minutes", SpokenWhen.of(now.plusMinutes(20), now, hindi = false))
        assertEquals("in 1 minute", SpokenWhen.of(now.plusMinutes(1), now, hindi = false))
        assertEquals("today at 6 PM", SpokenWhen.of(LocalDateTime.of(2026, 11, 4, 18, 0), now, hindi = false))
        assertEquals("tomorrow at 10 AM", SpokenWhen.of(LocalDateTime.of(2026, 11, 5, 10, 0), now, hindi = false))
        assertEquals("on Friday at 3 PM", SpokenWhen.of(LocalDateTime.of(2026, 11, 6, 15, 0), now, hindi = false))
        assertEquals("on 20 November at 9:30 AM", SpokenWhen.of(LocalDateTime.of(2026, 11, 20, 9, 30), now, hindi = false))
        assertEquals("कल 10 AM", SpokenWhen.of(LocalDateTime.of(2026, 11, 5, 10, 0), now, hindi = true))
        assertEquals("20 मिनट में", SpokenWhen.of(now.plusMinutes(20), now, hindi = true))
    }

    @Test fun cancelMatchesByWordsOrAll() {
        val king = Reminder(1, "call King", now.plusHours(1))
        val oven = Reminder(2, "check the oven", now.plusMinutes(20))
        val kingLater = Reminder(3, "text King happy birthday", now.plusDays(2))
        val all = listOf(king, oven, kingLater)
        assertEquals(listOf(king), ReminderMatcher.match(all, "King"))
        assertEquals(listOf(oven), ReminderMatcher.match(all, "the oven reminder"))
        assertEquals(all, ReminderMatcher.match(all, "all"))
        assertTrue(ReminderMatcher.match(all, "dentist").isEmpty())
        assertTrue(ReminderMatcher.match(all, "the reminder").isEmpty())
    }

    @Test fun reminderPhrases() {
        assertEquals("Okay, I'll remind you today at 6 PM: call King.", en.reminderSet("call King", LocalDateTime.of(2026, 11, 4, 18, 0), now, exact = true))
        assertTrue(en.reminderSet("call King", now.plusHours(1), now, exact = false).contains("exact alarms"))
        assertEquals("Reminder: call King.", en.reminderDue("call King"))
        assertEquals(
            "You have 2 reminders. check the oven, in 20 minutes. call King, today at 6 PM.",
            en.reminderList(listOf(Reminder(2, "check the oven", now.plusMinutes(20)), Reminder(1, "call King", LocalDateTime.of(2026, 11, 4, 18, 0))), now),
        )
        assertEquals("Cancelled the reminder: call King.", en.remindersCancelled(listOf(Reminder(1, "call King", now))))
    }

    @Test fun agendaPhrases() {
        val today = now.toLocalDate()
        assertEquals("Nothing on your calendar tomorrow.", en.agenda(emptyList(), today.plusDays(1), 1, today))
        val standup = CalendarEvent("Standup", LocalDateTime.of(2026, 11, 5, 10, 0), LocalDateTime.of(2026, 11, 5, 10, 15), allDay = false)
        val holiday = CalendarEvent("Holiday", LocalDateTime.of(2026, 11, 5, 0, 0), LocalDateTime.of(2026, 11, 6, 0, 0), allDay = true)
        assertEquals("You have 2 events tomorrow: Holiday all day, Standup at 10 AM.", en.agenda(listOf(holiday, standup), today.plusDays(1), 1, today))
        val friday = CalendarEvent("Meeting with Ravi", LocalDateTime.of(2026, 11, 6, 15, 0), LocalDateTime.of(2026, 11, 6, 16, 0), allDay = false)
        assertEquals("You have one event in the next 7 days: Meeting with Ravi on Friday at 3 PM.", en.agenda(listOf(friday), today, 7, today))
    }
}
