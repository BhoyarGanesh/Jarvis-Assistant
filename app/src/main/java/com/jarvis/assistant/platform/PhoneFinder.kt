package com.jarvis.assistant.platform

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Jarvis, where are you?": plays the alarm sound at full alarm volume (which rings through silent
 * and vibrate mode) until the user taps Stop, touches the Jarvis screen, or [RING_MS] passes.
 */
@Singleton
class PhoneFinder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    val isRinging: Boolean get() = job?.isActive == true

    /** Starts ringing after [startDelayMs], leaving room for Jarvis to say "I'm here!" first. */
    fun ring(startDelayMs: Long = START_DELAY_MS) {
        stop()
        job = scope.launch {
            try {
                delay(startDelayMs)
                startSound()
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
                showNotification()
                delay(RING_MS)
            } finally {
                release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun startSound() {
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            .onFailure { Log.w(TAG, "Could not raise alarm volume", it) }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sounds = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
        for (uri in sounds) {
            val candidate = MediaPlayer()
            val started = runCatching {
                candidate.setAudioAttributes(attributes)
                candidate.setDataSource(context, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
            }.isSuccess
            if (started) {
                player = candidate
                return
            }
            candidate.release()
        }
        Log.w(TAG, "No playable alarm or ringtone sound; vibrating only")
    }

    private fun showNotification() {
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, JarvisApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is ringing")
            .setContentText("Tap Stop when you've found your phone.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, MainActivity.stopRingingIntent(context), PendingIntent.FLAG_IMMUTABLE),
            )
            .setDeleteIntent(stop)
            .addAction(0, "Stop", stop)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        savedAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedAlarmVolume = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** The notification's Stop button. */
    @AndroidEntryPoint
    class StopReceiver : BroadcastReceiver() {
        @Inject lateinit var finder: PhoneFinder

        override fun onReceive(context: Context, intent: Intent) {
            finder.stop()
        }
    }

    private companion object {
        const val TAG = "PhoneFinder"
        const val NOTIFICATION_ID = 77
        const val START_DELAY_MS = 1_500L
        const val RING_MS = 45_000L
    }
}
