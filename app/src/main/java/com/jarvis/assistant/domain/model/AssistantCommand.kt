package com.jarvis.assistant.domain.model

/** A tool call returned by the backend, already validated into a typed command. */
sealed interface AssistantCommand {
    val toolName: String

    data class CallContact(val name: String) : AssistantCommand {
        override val toolName = "call_contact"
    }

    data class SendWhatsApp(val contactName: String, val message: String) : AssistantCommand {
        override val toolName = "send_whatsapp"
    }

    data class SendSms(val contactName: String, val message: String) : AssistantCommand {
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

    data class Answer(val text: String) : AssistantCommand {
        override val toolName = "answer"
    }
}

/** Commands that reach another person and therefore get the cancel countdown. */
val AssistantCommand.needsConfirmation: Boolean
    get() = this is AssistantCommand.CallContact ||
        this is AssistantCommand.SendWhatsApp ||
        this is AssistantCommand.SendSms

val AssistantCommand.contactName: String?
    get() = when (this) {
        is AssistantCommand.CallContact -> name
        is AssistantCommand.SendWhatsApp -> contactName
        is AssistantCommand.SendSms -> contactName
        else -> null
    }
