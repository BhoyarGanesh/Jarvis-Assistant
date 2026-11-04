package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.di.ApplicationScope
import com.jarvis.assistant.domain.AssistantOrchestrator
import com.jarvis.assistant.platform.ReminderManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A reminder's alarm went off, or its notification's Done / Snooze was tapped. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var reminders: ReminderManager
    @Inject lateinit var orchestrator: AssistantOrchestrator
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> reminders.fire(id)?.let(orchestrator::announceReminder)
                    ACTION_DONE -> reminders.done(id)
                    ACTION_SNOOZE -> reminders.snooze(id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.jarvis.assistant.action.REMINDER_FIRE"
        const val ACTION_DONE = "com.jarvis.assistant.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.jarvis.assistant.action.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"
    }
}
