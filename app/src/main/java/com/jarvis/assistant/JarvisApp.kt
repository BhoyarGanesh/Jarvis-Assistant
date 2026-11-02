package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_LISTENING,
                    getString(R.string.notification_channel_listening),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    CHANNEL_ALERTS,
                    getString(R.string.notification_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_ALERTS = "alerts"
    }
}
