package com.jarvis.assistant.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.domain.messages.IncomingMessage
import com.jarvis.assistant.platform.NotificationStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Mirrors visible notifications into [NotificationStore] so Jarvis can read messages aloud and
 * reply through each notification's reply action. Because the system keeps this service bound,
 * it also brings Jarvis back after the process is killed, and restarts wake word listening.
 */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {

    @Inject lateinit var store: NotificationStore

    override fun onListenerConnected() {
        store.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach(::capture)
        JarvisListeningService.ensureRunning(this)
    }

    override fun onListenerDisconnected() {
        store.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        store.remove(sbn.key)
    }

    private fun capture(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in IGNORED_CATEGORIES) return

        val reply = n.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val (sender, text) = if (style != null && style.messages.isNotEmpty()) {
            val recent = style.messages.takeLast(3)
            val person = recent.last().person?.name?.toString().orEmpty()
            val title = style.conversationTitle?.toString()
            val who = if (style.isGroupConversation && title != null) "$person in $title" else person.ifBlank { title.orEmpty() }
            who to recent.mapNotNull { it.text?.toString() }.joinToString(". ")
        } else {
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
            title to body
        }
        if (text.isBlank()) return

        store.put(
            IncomingMessage(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = appLabel(sbn.packageName),
                sender = sender,
                text = text,
                postedAt = sbn.postTime,
                canReply = reply != null,
                isConversation = style != null || n.category == Notification.CATEGORY_MESSAGE || reply != null,
            ),
            reply,
        )
    }

    private fun appLabel(pkg: String): String = KNOWN_APPS[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.')
    }

    private companion object {
        val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_CALL,
        )
        val KNOWN_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.google.android.apps.messaging" to "Messages",
            "org.telegram.messenger" to "Telegram",
            "com.instagram.android" to "Instagram",
            "com.google.android.gm" to "Gmail",
        )
    }
}
