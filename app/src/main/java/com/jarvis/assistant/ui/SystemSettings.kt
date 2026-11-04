package com.jarvis.assistant.ui

import android.app.AlarmManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.jarvis.assistant.service.JarvisNotificationListener

/** Small wrappers around permission checks and the system settings screens Jarvis sends you to. */
object SystemSettings {
    val contactPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
    )

    /** For caller announcements. Call log reveals the caller's number; contacts turn it into a name. */
    val callPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    /** Reading your agenda and adding events. */
    val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun calendarGranted(context: Context): Boolean = calendarPermissions.all { granted(context, it) }

    /** Reminders fire on the minute only with "Alarms & reminders" allowed (Android 12+). */
    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) ||
                open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    fun callAnnouncePermitted(context: Context): Boolean =
        granted(context, Manifest.permission.READ_PHONE_STATE) && granted(context, Manifest.permission.ANSWER_PHONE_CALLS)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun micGranted(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    fun notificationsGranted(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    // A personal, sideloaded assistant that must hear its wake word is exactly the case the exemption exists for.
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())) ||
            open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun notificationAccessGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun openNotificationAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(context, JarvisNotificationListener::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            if (open(context, detail)) return
        }
        open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAccessibilitySettings(context: Context) {
        open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
    }

    fun openAppSettings(context: Context) {
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    fun openUrl(context: Context, url: String) {
        open(context, Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    const val OEM_GUIDANCE =
        "Some phones kill background apps aggressively. If Jarvis stops hearing its wake word:\n" +
            "• Xiaomi / Redmi / POCO: Settings > Apps > Jarvis > Autostart ON, Battery saver: No restrictions.\n" +
            "• Samsung: Settings > Battery > Background usage limits > Never sleeping apps > add Jarvis.\n" +
            "• OnePlus / OPPO / Realme: Battery > Jarvis > Allow background activity; lock Jarvis in Recents.\n" +
            "• Vivo / iQOO: Battery > Background power consumption > Jarvis > Allow.\n" +
            "• Huawei / Honor: Battery > App launch > Jarvis > Manage manually, enable all three toggles."
    const val DONT_KILL_MY_APP = "https://dontkillmyapp.com"
}
