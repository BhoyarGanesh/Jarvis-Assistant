package com.jarvis.assistant.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.AssistantPhase
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import com.jarvis.assistant.ui.SystemSettings
import com.jarvis.assistant.ui.components.ActionCardStack
import com.jarvis.assistant.ui.components.AuroraBackground
import com.jarvis.assistant.ui.components.CountdownCard
import com.jarvis.assistant.ui.components.DisambiguationCard
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.JarvisAvatar
import com.jarvis.assistant.ui.components.JarvisOrb
import com.jarvis.assistant.ui.components.RainEffect
import com.jarvis.assistant.ui.components.LiveTranscript
import com.jarvis.assistant.ui.theme.JarvisColors
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    listenRequests: Int,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val mouth by viewModel.mouth.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // Whatever the user answers, the orchestrator will explain a missing mic permission out loud.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onTalkPressed()
    }
    val talk = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!SystemSettings.micGranted(context) && !state.busy) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.onTalkPressed()
        }
    }

    // Widget / tile / notification "Talk" requests.
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0 && !state.busy) talk()
    }

    // Let the red error state linger briefly, then settle back to idle (the message stays visible).
    LaunchedEffect(state.phase) {
        if (state.phase == AssistantPhase.ERROR) {
            delay(2_500)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            AssistantPhase.LISTENING -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            AssistantPhase.ERROR -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }
    LaunchedEffect(state.confirmation?.secondsLeft) {
        if (state.confirmation != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    val accent by animateColorAsState(
        when (state.phase) {
            AssistantPhase.LISTENING -> JarvisColors.Cyan
            AssistantPhase.THINKING -> JarvisColors.Magenta
            AssistantPhase.ERROR -> JarvisColors.Error
            else -> JarvisColors.Violet
        },
        tween(700),
        label = "accent",
    )

    AuroraBackground(accent = accent) {
        if (settings.showAvatar) {
            // Full-screen character behind everything; tapping her starts listening.
            JarvisAvatar(
                phase = state.phase,
                listeningLevel = level,
                mouth = mouth,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = talk,
                    ),
            )
            if (settings.rainEffect) RainEffect(state.phase, Modifier.fillMaxSize())
            // Darken the top and bottom so the status bar, text and buttons stay readable over the art.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.14f to Color.Transparent,
                            0.5f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(
                engine = engine,
                wakeWordEnabled = settings.wakeWordEnabled,
                onHistory = { showHistory = true },
                onSettings = onOpenSettings,
            )

            if (!settings.backendConfigured) {
                Banner("Connect your backend", "Add the backend URL and shared secret in Settings.", onOpenSettings)
            } else if (engine is WakeWordEngineState.Unavailable && settings.wakeWordEnabled) {
                Banner("Wake word unavailable", (engine as WakeWordEngineState.Unavailable).reason, null)
            }

            if (settings.showAvatar) {
                // She fills the screen; the conversation sits below her face, like subtitles.
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(0.6f))
                JarvisOrb(
                    phase = state.phase,
                    level = level,
                    modifier = Modifier
                        .size(280.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = talk,
                        ),
                )
            }

            PhaseLabel(state.phase, settings.wakeWordEnabled && engine == WakeWordEngineState.Listening)

            Box(Modifier.heightIn(min = 96.dp).fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.transcript.isNotBlank()) {
                        LiveTranscript(state.transcript, Modifier.fillMaxWidth())
                    }
                    AnimatedContent(
                        targetState = state.reply,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                        label = "reply",
                    ) { reply ->
                        if (reply.isNotBlank()) {
                            Text(
                                reply,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (state.phase == AssistantPhase.ERROR) JarvisColors.Error else JarvisColors.TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            if (!settings.showAvatar) Spacer(Modifier.weight(1f))

            ActionCardStack(state.cards, Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))
            TalkButton(busy = state.busy, onClick = talk)
            Spacer(Modifier.height(12.dp))
        }

        // Overlays slide up over the card stack.
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = state.confirmation != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                state.confirmation?.let {
                    CountdownCard(it, onCancel = viewModel::cancel, onConfirm = viewModel::confirmNow, modifier = Modifier.padding(bottom = 88.dp))
                }
            }
            AnimatedVisibility(
                visible = state.disambiguation != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                state.disambiguation?.let {
                    DisambiguationCard(it, onChoose = viewModel::choose, onCancel = viewModel::cancel, modifier = Modifier.padding(bottom = 88.dp))
                }
            }
        }
    }

    if (showHistory) {
        HistorySheet(entries = history, onClear = viewModel::clearHistory, onDismiss = { showHistory = false })
    }
}

@Composable
private fun TopBar(
    engine: WakeWordEngineState,
    wakeWordEnabled: Boolean,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Jarvis", style = MaterialTheme.typography.displaySmall)
            StatusChip(engine, wakeWordEnabled)
        }
        IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, contentDescription = "History") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun StatusChip(engine: WakeWordEngineState, enabled: Boolean) {
    val (label, color) = when {
        !enabled -> "Wake word off" to JarvisColors.TextMuted
        engine == WakeWordEngineState.Listening -> "Listening for “Jarvis”" to JarvisColors.Success
        engine == WakeWordEngineState.Paused -> "Busy" to JarvisColors.Cyan
        engine == WakeWordEngineState.Loading -> "Loading model" to JarvisColors.Warning
        engine is WakeWordEngineState.Unavailable -> "Wake word unavailable" to JarvisColors.Error
        else -> "Starting" to JarvisColors.TextMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = JarvisColors.TextSecondary)
    }
}

@Composable
private fun Banner(title: String, body: String, onClick: (() -> Unit)?) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        tint = JarvisColors.Warning,
        contentPadding = 14.dp,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = JarvisColors.Warning)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = JarvisColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PhaseLabel(phase: AssistantPhase, wakeWordListening: Boolean) {
    val label = when (phase) {
        AssistantPhase.IDLE -> if (wakeWordListening) "Say “Jarvis” or tap to talk" else "Tap to talk"
        AssistantPhase.LISTENING -> "Listening…"
        AssistantPhase.THINKING -> "Thinking…"
        AssistantPhase.SPEAKING -> "Speaking"
        AssistantPhase.ERROR -> "Something went wrong"
    }
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (fadeIn(tween(250)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 2 })
        },
        label = "phase-label",
    ) {
        Text(it, style = MaterialTheme.typography.labelLarge, color = JarvisColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TalkButton(busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    val gradient = if (busy) {
        Brush.horizontalGradient(listOf(JarvisColors.Error.copy(alpha = 0.85f), JarvisColors.Magenta.copy(alpha = 0.85f)))
    } else {
        Brush.horizontalGradient(listOf(JarvisColors.Violet, Color(0xFF5B8CFF), JarvisColors.Cyan))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(gradient)
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (busy) "Stop" else "Push to talk" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (busy) Icons.Rounded.Close else Icons.Rounded.Mic, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(if (busy) "Stop" else "Tap to talk", style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}
