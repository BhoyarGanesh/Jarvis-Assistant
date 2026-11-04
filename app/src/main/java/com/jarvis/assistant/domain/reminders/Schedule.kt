package com.jarvis.assistant.domain.reminders

import com.jarvis.assistant.domain.Phrases
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** An upcoming reminder. [at] is local wall-clock time. */
data class Reminder(val id: Long, val text: String, val at: LocalDateTime)

/** One calendar entry, in local time. */
data class CalendarEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String = "",
)

sealed interface ReminderTime {
    /** [movedToNextDay] when the requested time had just passed and was pushed forward. */
    data class At(val time: LocalDateTime, val movedToNextDay: Boolean = false) : ReminderTime
    data object Past : ReminderTime
}

object ReminderTimes {
    /**
     * The model works out "at 6" itself, but if the time it returns has already passed today
     * (wrong AM/PM or day), the next occurrence is almost always what was meant. Anything more
     * than a day old is a mistake to ask about, not to guess.
     */
    fun resolve(requested: LocalDateTime, now: LocalDateTime): ReminderTime = when {
        requested.isAfter(now) -> ReminderTime.At(requested)
        requested.isAfter(now.minusHours(24)) -> {
            var time = requested
            while (!time.isAfter(now)) time = time.plusDays(1)
            ReminderTime.At(time, movedToNextDay = true)
        }
        else -> ReminderTime.Past
    }
}

/** Says when something happens, relative to [now]: "in 20 minutes", "today at 6 PM", "on Friday at 3 PM". */
object SpokenWhen {
    fun of(time: LocalDateTime, now: LocalDateTime, hindi: Boolean): String {
        val minutes = Duration.between(now, time).toMinutes()
        val clock = Phrases.formatTime(time.hour, time.minute)
        if (minutes in 1..59) return if (hindi) "$minutes मिनट में" else "in $minutes minute${if (minutes == 1L) "" else "s"}"
        return "${day(time.toLocalDate(), now.toLocalDate(), hindi)} ${if (hindi) "" else "at "}$clock".trim()
    }

    /** "today", "tomorrow", "on Friday" (within a week) or "on 12 November". */
    fun day(date: LocalDate, today: LocalDate, hindi: Boolean): String {
        val locale = if (hindi) Locale.forLanguageTag("hi-IN") else Locale.ENGLISH
        val days = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays()
        return when {
            days == 0L -> if (hindi) "आज" else "today"
            days == 1L -> if (hindi) "कल" else "tomorrow"
            days in 2..6 -> {
                val name = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                if (hindi) name else "on $name"
            }
            else -> {
                val month = date.month.getDisplayName(TextStyle.FULL, locale)
                if (hindi) "${date.dayOfMonth} $month" else "on ${date.dayOfMonth} $month"
            }
        }
    }
}

object ReminderMatcher {
    private val STOP_WORDS = setOf("the", "a", "an", "my", "to", "about", "for", "reminder", "reminders", "remind", "me", "of")

    /**
     * Which reminders "cancel the reminder about King" means: every one for "all", otherwise the
     * soonest reminder sharing a meaningful word with [query].
     */
    fun match(reminders: List<Reminder>, query: String): List<Reminder> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        if (words.all { it == "all" || it == "every" || it == "everything" }) return reminders
        return reminders
            .filter { r -> words(r.text).any { w -> words.any { q -> w.startsWith(q) || q.startsWith(w) } } }
            .sortedBy { it.at }
            .take(1)
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP_WORDS }.toSet()
}
