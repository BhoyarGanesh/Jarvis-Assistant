package com.jarvis.assistant.domain.model

/** A tool call returned by the backend, already validated into a typed command. */
sealed interface AssistantCommand {
    val toolName: String

    data class CallContact(val name: String) : AssistantCommand {
        override val toolName = "call_contact"
    }

    /** [composed] = Jarvis wrote or reworded the text (smart compose), so the user hears it before it is sent. */
    data class SendWhatsApp(val contactName: String, val message: String, val composed: Boolean = false) : AssistantCommand {
        override val toolName = "send_whatsapp"
    }

    data class SendSms(val contactName: String, val message: String, val composed: Boolean = false) : AssistantCommand {
        override val toolName = "send_sms"
    }

    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AssistantCommand {
        override val toolName = "set_alarm"
    }

    data class SetTimer(val seconds: Int, val label: String) : AssistantCommand {
        override val toolName = "set_timer"
    }

    data class OpenApp(val appName: String) : AssistantCommand {
        override val toolName = "open_app"
    }

    data class WebSearch(val query: String) : AssistantCommand {
        override val toolName = "web_search"
    }

    data class PlayMusic(val query: String) : AssistantCommand {
        override val toolName = "play_music"
    }

    /** Blank filters mean "any". */
    data class ReadNotifications(val appName: String, val contactName: String) : AssistantCommand {
        override val toolName = "read_notifications"
    }

    /** Blank [contactName] replies to the most recent (or last read) message. */
    data class ReplyMessage(val contactName: String, val message: String, val composed: Boolean = false) : AssistantCommand {
        override val toolName = "reply_message"
    }

    /** A question about whatever was on screen when the user said "Jarvis". */
    data class ScreenQuestion(val question: String) : AssistantCommand {
        override val toolName = "screen_question"
    }

    /** Blank filters mean "any"; [contactName] may be a group name like "Family". */
    data class SummarizeMessages(val appName: String, val contactName: String, val question: String) : AssistantCommand {
        override val toolName = "summarize_messages"
    }

    data object FindPhone : AssistantCommand {
        override val toolName = "find_phone"
    }

    data class Answer(val text: String) : AssistantCommand {
        override val toolName = "answer"
    }
}

/** Commands that reach another person and therefore get the cancel countdown. */
val AssistantCommand.needsConfirmation: Boolean
    get() = this is AssistantCommand.CallContact ||
        this is AssistantCommand.SendWhatsApp ||
        this is AssistantCommand.SendSms ||
        this is AssistantCommand.ReplyMessage

/** True when the message text was written by Jarvis rather than dictated word for word. */
val AssistantCommand.isComposed: Boolean
    get() = when (this) {
        is AssistantCommand.SendWhatsApp -> composed
        is AssistantCommand.SendSms -> composed
        is AssistantCommand.ReplyMessage -> composed
        else -> false
    }

val AssistantCommand.contactName: String?
    get() = when (this) {
        is AssistantCommand.CallContact -> name
        is AssistantCommand.SendWhatsApp -> contactName
        is AssistantCommand.SendSms -> contactName
        else -> null
    }
