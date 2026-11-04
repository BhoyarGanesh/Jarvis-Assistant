package com.jarvis.assistant.platform

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.domain.reminders.CalendarEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and adds events in the phone's own calendars (Google Calendar or whatever is synced). */
@Singleton
class CalendarAccess @Inject constructor(@ApplicationContext private val context: Context) {

    val canRead: Boolean get() = granted(Manifest.permission.READ_CALENDAR)
    val canWrite: Boolean get() = granted(Manifest.permission.WRITE_CALENDAR) && canRead

    /** Event occurrences overlapping [from, to), soonest first. Recurring events are expanded. */
    suspend fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = mutableListOf<CalendarEvent>()
        runCatching {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        val allDay = c.getInt(3) == 1
                        // All-day events are stored at UTC midnight.
                        val eventZone = if (allDay) ZoneOffset.UTC else zone
                        events += CalendarEvent(
                            title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Untitled event",
                            start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), eventZone),
                            end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), eventZone),
                            allDay = allDay,
                            location = c.getString(4).orEmpty(),
                        )
                    }
                }
        }.onFailure { Log.w(TAG, "calendar query failed", it) }
        events.sortedWith(compareBy({ !it.allDay }, { it.start }))
    }

    /** Adds the event to the primary writable calendar. Returns false if there is none. */
    suspend fun add(title: String, start: LocalDateTime, end: LocalDateTime, location: String): Boolean = withContext(Dispatchers.IO) {
        val calendarId = writableCalendarId() ?: return@withContext false
        val zone = ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null }
            .onFailure { Log.w(TAG, "calendar insert failed", it) }
            .getOrDefault(false)
    }

    private fun writableCalendarId(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CalendarAccess"
    }
}
