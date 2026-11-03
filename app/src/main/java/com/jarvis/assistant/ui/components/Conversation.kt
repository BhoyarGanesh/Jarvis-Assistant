package com.jarvis.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MobileFriendly
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.domain.ActionCard
import com.jarvis.assistant.domain.ActionKind
import com.jarvis.assistant.domain.ActionStatus
import com.jarvis.assistant.domain.Disambiguation
import com.jarvis.assistant.domain.PendingConfirmation
import com.jarvis.assistant.ui.theme.JarvisColors

fun ActionKind.icon(): ImageVector = when (this) {
    ActionKind.CALL -> Icons.Rounded.Call
    ActionKind.WHATSAPP -> Icons.AutoMirrored.Rounded.Chat
    ActionKind.SMS -> Icons.Rounded.Sms
    ActionKind.ALARM -> Icons.Rounded.Alarm
    ActionKind.TIMER -> Icons.Rounded.Timer
    ActionKind.APP -> Icons.Rounded.Apps
    ActionKind.SEARCH -> Icons.Rounded.Search
    ActionKind.MUSIC -> Icons.Rounded.MusicNote
    ActionKind.MESSAGES -> Icons.Rounded.Forum
    ActionKind.SCREEN -> Icons.Rounded.ScreenSearchDesktop
    ActionKind.FIND_PHONE -> Icons.Rounded.MobileFriendly
    ActionKind.ANSWER -> Icons.Rounded.AutoAwesome
    ActionKind.INFO -> Icons.Rounded.Info
}

fun ActionKind.accent(): Color = when (this) {
    ActionKind.CALL -> JarvisColors.Success
    ActionKind.WHATSAPP -> Color(0xFF25D366)
    ActionKind.SMS -> JarvisColors.Cyan
    ActionKind.ALARM, ActionKind.TIMER -> JarvisColors.Warning
    ActionKind.APP, ActionKind.SEARCH -> JarvisColors.Violet
    ActionKind.MUSIC -> JarvisColors.Magenta
    ActionKind.MESSAGES -> JarvisColors.Cyan
    ActionKind.SCREEN -> JarvisColors.Violet
    ActionKind.FIND_PHONE -> JarvisColors.Warning
    ActionKind.ANSWER -> Color(0xFF9D7BFF)
    ActionKind.INFO -> JarvisColors.TextSecondary
}

fun ActionStatus.icon(): ImageVector = when (this) {
    ActionStatus.SUCCESS -> Icons.Rounded.CheckCircle
    ActionStatus.NEEDS_USER -> Icons.Rounded.TouchApp
    ActionStatus.FAILED -> Icons.Rounded.ErrorOutline
    ActionStatus.CANCELLED -> Icons.Rounded.Block
}

fun ActionStatus.color(): Color = when (this) {
    ActionStatus.SUCCESS -> JarvisColors.Success
    ActionStatus.NEEDS_USER -> JarvisColors.Warning
    ActionStatus.FAILED -> JarvisColors.Error
    ActionStatus.CANCELLED -> JarvisColors.TextMuted
}

/** Transcript that reveals each new word with a fade-and-rise. */
@Composable
fun LiveTranscript(text: String, modifier: Modifier = Modifier) {
    val words = remember(text) { text.split(' ').filter { it.isNotBlank() } }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        words.forEachIndexed { index, word ->
            key(index, word) {
                val anim = remember { Animatable(0f) }
                LaunchedEffect(Unit) { anim.animateTo(1f, spring(stiffness = Spring.StiffnessLow)) }
                Text(
                    text = "$word ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = JarvisColors.TextPrimary,
                    modifier = Modifier.graphicsLayer {
                        alpha = anim.value
                        translationY = (1f - anim.value) * 18.dp.toPx()
                    },
                )
            }
        }
    }
}

@Composable
fun IconBubble(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), tint.copy(alpha = 0.12f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
fun ActionCardView(card: ActionCard, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(card.kind.icon(), card.kind.accent())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                card.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(card.status.icon(), contentDescription = card.status.name, tint = card.status.color())
        }
    }
}

/** Newest card slides up from below; older ones stay put. */
@Composable
fun ActionCardStack(cards: List<ActionCard>, modifier: Modifier = Modifier, max: Int = 3) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.take(max).forEach { card ->
            key(card.id) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = visible,
                    enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    ActionCardView(card)
                }
            }
        }
    }
}

@Composable
fun CountdownCard(
    confirmation: PendingConfirmation,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (confirmation.awaitingYes) 1f else (confirmation.secondsLeft - 1).toFloat() / confirmation.totalSeconds,
        animationSpec = tween(1_000),
        label = "countdown",
    )
    GlassCard(modifier.fillMaxWidth(), strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(64.dp),
                    color = confirmation.kind.accent(),
                    trackColor = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 5.dp,
                    strokeCap = StrokeCap.Round,
                )
                Text(if (confirmation.awaitingYes) "?" else "${confirmation.secondsLeft}", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(confirmation.kind.icon(), null, tint = confirmation.kind.accent(), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(confirmation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                confirmation.subtitle?.let {
                    Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = JarvisColors.TextSecondary, maxLines = if (confirmation.awaitingYes) 6 else 2)
                }
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(if (confirmation.awaitingYes) "Don't send" else "Cancel", color = JarvisColors.Error) }
            TextButton(onClick = onConfirm) { Text(if (confirmation.awaitingYes) "Send" else "Do it now", color = JarvisColors.Cyan) }
        }
    }
}

@Composable
fun DisambiguationCard(
    disambiguation: Disambiguation,
    onChoose: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth(), strong = true) {
        Text("Which one?", style = MaterialTheme.typography.titleLarge)
        Text("Say the name or \"the first one\", or tap.", style = MaterialTheme.typography.bodySmall, color = JarvisColors.TextSecondary)
        Spacer(Modifier.padding(top = 10.dp))
        disambiguation.options.forEachIndexed { index, name ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = 12.dp,
                onClick = { onChoose(index) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Call, JarvisColors.Violet, size = 32)
                    Spacer(Modifier.width(12.dp))
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
            Text("Cancel", color = JarvisColors.Error)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = JarvisColors.TextMuted,
        textAlign = TextAlign.Start,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 20.dp),
    )
}
