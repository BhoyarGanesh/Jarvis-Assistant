package com.jarvis.assistant.platform

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.data.reminders.ReminderDao
import com.jarvis.assistant.data.reminders.ReminderEntity
import com.jarvis.assistant.data.reminders.ReminderState
import com.jarvis.assistant.domain.reminders.Reminder
import com.jarvis.assistant.service.ReminderReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores reminders and schedules them with AlarmManager, so they fire on time even when Jarvis
 * isn't running, and survive reboots (see [rescheduleAll]). Exact alarms need the "Alarms &
 * reminders" permission on Android 12+; without it the reminder fires within about a minute.
 */
@Singleton
class ReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val canBeExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    suspend fun add(text: String, at: LocalDateTime): Reminder {
        val millis = at.atZone(zone).toInstant().toEpochMilli()
        val id = dao.insert(ReminderEntity(text = text, triggerAt = millis, createdAt = System.currentTimeMillis()))
        schedule(id, millis)
        return Reminder(id, text, at)
    }

    suspend fun pending(): List<Reminder> = dao.pending().map { it.toReminder() }

    suspend fun cancel(reminders: List<Reminder>) {
        reminders.forEach {
            alarms.cancel(fireIntent(it.id))
            dao.setState(it.id, ReminderState.CANCELLED)
        }
    }

    /** Called when the alarm goes off: shows the notification and returns the text to say, or null if stale. */
    suspend fun fire(id: Long): String? {
        val entity = dao.byId(id)?.takeIf { it.state == ReminderState.PENDING } ?: return null
        dao.setState(id, ReminderState.FIRED)
        showNotification(entity)
        return entity.text
    }

    suspend fun done(id: Long) {
        dao.setState(id, ReminderState.DONE)
        dismiss(id)
    }

    suspend fun snooze(id: Long, minutes: Long = SNOOZE_MINUTES) {
        val at = System.currentTimeMillis() + minutes * 60_000
        dao.reschedule(id, at)
        schedule(id, at)
        dismiss(id)
    }

    /** After a reboot or app update the system has forgotten our alarms; set them again. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao.pending().forEach { schedule(it.id, maxOf(it.triggerAt, now + 5_000)) }
        dao.pruneFinished(before = now - KEEP_FINISHED_MS)
    }

    // Exactness is checked through canBeExact; the inexact branch covers a denied permission.
    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun schedule(id: Long, atMillis: Long) {
        val intent = fireIntent(id)
        if (canBeExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 60_000, intent)
        }
    }

    private fun fireIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showNotification(entity: ReminderEntity) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Reminder")
            .setContentText(entity.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entity.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(entity.triggerAt)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", actionIntent(entity.id, ReminderReceiver.ACTION_DONE))
            .addAction(0, "Snooze $SNOOZE_MINUTES min", actionIntent(entity.id, ReminderReceiver.ACTION_SNOOZE))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId(entity.id), notification)
    }

    private fun dismiss(id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    private fun notificationId(id: Long) = NOTIFICATION_BASE + id.toInt()

    private fun ReminderEntity.toReminder() = Reminder(id, text, LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerAt), zone))

    companion object {
        const val SNOOZE_MINUTES = 10L
        private const val NOTIFICATION_BASE = 40_000
        private const val KEEP_FINISHED_MS = 30L * 24 * 60 * 60 * 1000
    }
}
