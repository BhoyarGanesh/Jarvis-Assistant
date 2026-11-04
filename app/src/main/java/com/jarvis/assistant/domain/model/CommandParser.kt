package com.jarvis.assistant.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Turns the backend's normalized `{tool, args}` into an [AssistantCommand].
 * The backend already validates, but the app never trusts the network blindly:
 * anything malformed becomes an [AssistantCommand.Answer] with a gentle retry prompt.
 */
object CommandParser {
    const val FALLBACK = "Sorry, I didn't quite get that. Could you say it again?"

    fun parse(tool: String, args: JsonObject): AssistantCommand {
        fun str(key: String): String? =
            (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

        fun int(key: String): Int? =
            (args[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }

        fun bool(key: String): Boolean =
            (args[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", ignoreCase = true) } ?: false

        fun dateTime(key: String): LocalDateTime? = str(key)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

        fun date(key: String): LocalDate? = str(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        fun nonBlank(key: String) = str(key)?.takeIf { it.isNotEmpty() }

        val command: AssistantCommand? = when (tool) {
            "call_contact" -> nonBlank("name")?.let(AssistantCommand::CallContact)
            "send_whatsapp" -> {
                val name = nonBlank("contact_name")
                val message = nonBlank("message")
                if (name != null && message != null) AssistantCommand.SendWhatsApp(name, message, bool("composed")) else null
            }
            "send_sms" -> {
                val name = nonBlank("contact_name")
                val message = nonBlank("message")
                if (name != null && message != null) AssistantCommand.SendSms(name, message, bool("composed")) else null
            }
            "set_alarm" -> {
                val hour = int("hour")
                val minute = int("minute") ?: 0
                if (hour != null && hour in 0..23 && minute in 0..59) {
                    AssistantCommand.SetAlarm(hour, minute, str("label").orEmpty())
                } else null
            }
            "set_timer" -> int("seconds")?.takeIf { it in 1..86_400 }
                ?.let { AssistantCommand.SetTimer(it, str("label").orEmpty()) }
            "open_app" -> nonBlank("app_name")?.let(AssistantCommand::OpenApp)
            "web_search" -> nonBlank("query")?.let(AssistantCommand::WebSearch)
            "play_music" -> nonBlank("query")?.let(AssistantCommand::PlayMusic)
            "read_notifications" -> AssistantCommand.ReadNotifications(str("app_name").orEmpty(), str("contact_name").orEmpty())
            "reply_message" -> nonBlank("message")?.let { AssistantCommand.ReplyMessage(str("contact_name").orEmpty(), it, bool("composed")) }
            "screen_question" -> nonBlank("question")?.let(AssistantCommand::ScreenQuestion)
            "summarize_messages" -> AssistantCommand.SummarizeMessages(
                str("app_name").orEmpty(),
                str("contact_name").orEmpty(),
                str("question").orEmpty(),
            )
            "set_reminder" -> {
                val text = nonBlank("text")
                val at = dateTime("when")
                if (text != null && at != null) AssistantCommand.SetReminder(text, at) else null
            }
            "list_reminders" -> AssistantCommand.ListReminders
            "cancel_reminder" -> nonBlank("text")?.let(AssistantCommand::CancelReminder)
            "add_calendar_event" -> {
                val title = nonBlank("title")
                val start = dateTime("start")
                val minutes = (int("duration_minutes") ?: 60).takeIf { it in 5..1440 }
                if (title != null && start != null && minutes != null) {
                    AssistantCommand.AddCalendarEvent(title, start, minutes, str("location").orEmpty())
                } else null
            }
            "read_calendar" -> date("date")?.let { d ->
                (int("days") ?: 1).takeIf { it in 1..7 }?.let { AssistantCommand.ReadCalendar(d, it) }
            }
            "find_phone" -> AssistantCommand.FindPhone
            "answer" -> nonBlank("text")?.let(AssistantCommand::Answer)
            else -> null
        }
        return command ?: AssistantCommand.Answer(FALLBACK)
    }
}
