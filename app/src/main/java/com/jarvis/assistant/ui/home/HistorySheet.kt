package com.jarvis.assistant.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.data.history.ActionLogEntity
import com.jarvis.assistant.data.history.actionStatus
import com.jarvis.assistant.domain.ActionKind
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.IconBubble
import com.jarvis.assistant.ui.components.accent
import com.jarvis.assistant.ui.components.color
import com.jarvis.assistant.ui.components.icon
import com.jarvis.assistant.ui.theme.JarvisColors

private fun kindForTool(tool: String) = when (tool) {
    "call_contact" -> ActionKind.CALL
    "send_whatsapp" -> ActionKind.WHATSAPP
    "send_sms" -> ActionKind.SMS
    "set_alarm" -> ActionKind.ALARM
    "set_timer" -> ActionKind.TIMER
    "open_app" -> ActionKind.APP
    "web_search" -> ActionKind.SEARCH
    "play_music" -> ActionKind.MUSIC
    "read_notifications", "reply_message", "summarize_messages" -> ActionKind.MESSAGES
    "screen_question" -> ActionKind.SCREEN
    "find_phone" -> ActionKind.FIND_PHONE
    "incoming_call" -> ActionKind.CALL
    "answer" -> ActionKind.ANSWER
    else -> ActionKind.INFO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(entries: List<ActionLogEntity>, onClear: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear", color = JarvisColors.Error) }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Nothing yet. Try “Jarvis, set a timer for 5 minutes”.", color = JarvisColors.TextMuted)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    items(entries, key = { it.id }) { entry -> HistoryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ActionLogEntity) {
    val kind = kindForTool(entry.tool)
    val status = entry.actionStatus
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(kind.icon(), kind.accent(), size = 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.summary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "“${entry.transcript}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(entry.timestamp).toString() + (entry.detail?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(status.icon(), contentDescription = status.name, tint = status.color())
        }
    }
}
