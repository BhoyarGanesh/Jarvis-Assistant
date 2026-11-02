package com.jarvis.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Quick Settings tile: opens Jarvis already listening. */
class JarvisTileService : TileService() {
    // The Intent overload is only used below Android 14, where it is still the supported API.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MainActivity.listenIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Home-screen widget: one tap to talk. */
class JarvisWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = PendingIntent.getActivity(
            context, 11, MainActivity.listenIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_jarvis).apply {
            setOnClickPendingIntent(R.id.widget_root, pending)
        }
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }
}

/**
 * Restarts wake word listening after a reboot when enabled. Android 14+ forbids starting a
 * microphone foreground service from BOOT_COMPLETED, so there we post a one-tap notification instead.
 */
class BootReceiver : BroadcastReceiver() {

    // Field injection into receivers needs a super.onReceive() call Kotlin can't make on the
    // abstract method, so pull dependencies through an entry point instead.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settings(): SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java).settings()
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = settings.current()
                if (!s.startOnBoot || !s.wakeWordEnabled) return@launch
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    JarvisListeningService.start(context)
                } else {
                    notifyTapToStart(context)
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun notifyTapToStart(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 12, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, JarvisApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is ready")
            .setContentText("Tap to turn on wake word listening")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(BOOT_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val BOOT_NOTIFICATION_ID = 43
    }
}
