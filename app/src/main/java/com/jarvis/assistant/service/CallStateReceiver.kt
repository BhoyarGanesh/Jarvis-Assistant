package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.di.ApplicationScope
import com.jarvis.assistant.domain.AssistantOrchestrator
import com.jarvis.assistant.domain.calls.IncomingCall
import com.jarvis.assistant.platform.AndroidCallControl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hears the phone start and stop ringing. When "Announce callers" is on, Jarvis says who is
 * calling and waits for "answer", "reject" or "tell him ...".
 */
@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var orchestrator: AssistantOrchestrator
    @Inject lateinit var calls: AndroidCallControl
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> onRinging(intent)
            // Picked up (on screen or by us), hung up or missed.
            else -> orchestrator.callStopped()
        }
    }

    private fun onRinging(intent: Intent) {
        @Suppress("DEPRECATION") // The broadcast is still the only number source for a non-dialer app.
        val numberKey = TelephonyManager.EXTRA_INCOMING_NUMBER
        // With call log access Android sends this broadcast twice, once without and once with the number.
        if (calls.canSeeNumbers() && !intent.hasExtra(numberKey)) return
        val number = intent.getStringExtra(numberKey)
        val pending = goAsync()
        scope.launch {
            try {
                if (settings.current().announceCalls && calls.hasRequiredPermissions()) {
                    orchestrator.announceCall(IncomingCall(calls.contactNameFor(number), number))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
