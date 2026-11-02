package com.jarvis.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.AssistantOrchestrator
import com.jarvis.assistant.domain.wakeword.WakeWordConfig
import com.jarvis.assistant.domain.wakeword.WakeWordEngine
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive with a persistent notification so the wake word engine can listen
 * while the screen is off. The mic is only held by Vosk; it is released whenever a command runs.
 */
@AndroidEntryPoint
class JarvisListeningService : LifecycleService() {

    @Inject lateinit var engine: WakeWordEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var orchestrator: AssistantOrchestrator

    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { settings.setWakeWordEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground(engine.state.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            observe()
        }
        return START_STICKY
    }

    private fun goForeground(state: WakeWordEngineState): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: RuntimeException) {
            // Android 14+ refuses a microphone FGS started from the background.
            Log.w(TAG, "Could not start foreground service", e)
            false
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            settings.settings
                .map { Triple(it.wakeWordEnabled, it.wakeWordSensitivity, it.wakeWordDebounceMs) }
                .distinctUntilChanged()
                .collect { (enabled, sensitivity, debounce) ->
                    if (enabled) {
                        engine.stop()
                        engine.start(WakeWordConfig(sensitivity, debounce))
                    } else {
                        engine.stop()
                        stopSelf()
                    }
                }
        }
        lifecycleScope.launch {
            engine.detections.collect {
                if (orchestrator.isBusy) return@collect
                orchestrator.trigger()
                showAssistantUi()
            }
        }
        lifecycleScope.launch {
            engine.state.collect { state ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    /** Best effort: brings the UI forward (allowed once the accessibility service or overlay permission is granted). */
    private fun showAssistantUi() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    private fun buildNotification(state: WakeWordEngineState): Notification {
        val text = when (state) {
            WakeWordEngineState.Listening -> "Listening for \"Jarvis\""
            WakeWordEngineState.Paused -> "Handling your request"
            WakeWordEngineState.Loading -> "Loading wake word model"
            WakeWordEngineState.Stopped -> "Wake word off"
            is WakeWordEngineState.Unavailable -> state.reason
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getActivity(
            this, 1, MainActivity.listenIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, JarvisListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Talk", talk)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jarvis.assistant.action.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, JarvisListeningService::class.java))
            }.onFailure { Log.w(TAG, "startForegroundService failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisListeningService::class.java))
        }
    }
}
