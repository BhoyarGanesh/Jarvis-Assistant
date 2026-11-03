package com.jarvis.assistant.domain.messages

import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.contacts.ContactMatcher

/** A message/notification currently shown on the phone, as captured by the notification listener. */
data class IncomingMessage(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val postedAt: Long,
    val canReply: Boolean,
    val isConversation: Boolean,
    /** Chat or group title ("Family"); blank when the app doesn't provide one. */
    val conversation: String = "",
    /** Recent lines as "Sender: text", oldest first, for summaries. Empty when the app only shows one line. */
    val transcript: List<String> = emptyList(),
)

/** Choosing which notifications to read out or reply to. Pure so it can be unit-tested. */
object MessageSelector {
    private const val MIN_SCORE = 0.8

    /** Newest first; conversations (chats, SMS) before other notifications. */
    fun select(
        messages: List<IncomingMessage>,
        appName: String,
        contactName: String,
        limit: Int = 5,
    ): List<IncomingMessage> = messages
        .filter { appName.isBlank() || matchesApp(it, appName) }
        .filter { contactName.isBlank() || matchesSender(it.sender, contactName) || matchesSender(it.conversation, contactName) }
        .sortedWith(compareByDescending<IncomingMessage> { it.isConversation }.thenByDescending { it.postedAt })
        .take(limit)

    /**
     * Reply target: the named sender's latest repliable message, else the last message the user
     * heard, else the newest repliable message.
     */
    fun replyTarget(messages: List<IncomingMessage>, contactName: String, lastRead: IncomingMessage?): IncomingMessage? {
        val repliable = messages.filter { it.canReply }.sortedByDescending { it.postedAt }
        if (contactName.isNotBlank()) return repliable.firstOrNull { matchesSender(it.sender, contactName) }
        lastRead?.let { read -> repliable.firstOrNull { it.key == read.key }?.let { return it } }
        return repliable.firstOrNull()
    }

    fun matchesSender(sender: String, spoken: String): Boolean =
        ContactMatcher.score(ContactMatcher.normalize(spoken), Contact(0, sender, "")) >= MIN_SCORE

    private fun matchesApp(message: IncomingMessage, appName: String): Boolean {
        val wanted = ContactMatcher.normalize(appName).replace(" ", "")
        val label = ContactMatcher.normalize(message.appLabel).replace(" ", "")
        return label.contains(wanted) || wanted.contains(label) ||
            (wanted in setOf("sms", "text", "texts", "messages") && message.packageName.contains("messag"))
    }
}
