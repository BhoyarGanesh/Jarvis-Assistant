package com.jarvis.assistant.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.domain.messages.IncomingMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's currently visible notifications, as reported by [com.jarvis.assistant.service.JarvisNotificationListener].
 * Kept in memory only: nothing is written to disk, and message text reaches the backend only when
 * the user asks for a summary ("what did I miss?").
 */
@Singleton
class NotificationStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private class Entry(val message: IncomingMessage, val reply: Notification.Action?)

    private val entries = LinkedHashMap<String, Entry>()

    /** The most recent message read aloud, so "reply ..." without a name targets it. */
    @Volatile var lastRead: IncomingMessage? = null

    fun isAccessGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    @Synchronized
    fun put(message: IncomingMessage, reply: Notification.Action?) {
        entries.remove(message.key)
        entries[message.key] = Entry(message, reply)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun messages(): List<IncomingMessage> = entries.values.map { it.message }

    /** Sends [text] through the notification's own reply action (works for WhatsApp, SMS, Telegram...). */
    fun reply(message: IncomingMessage, text: String): Boolean {
        val action = synchronized(this) { entries[message.key]?.reply } ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        return try {
            action.actionIntent.send(context, 0, fillIn)
            true
        } catch (_: PendingIntent.CanceledException) {
            remove(message.key)
            false
        }
    }

    private companion object {
        const val MAX_ENTRIES = 60
    }
}
