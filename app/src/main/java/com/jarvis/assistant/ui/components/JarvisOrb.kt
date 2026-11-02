package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jarvis.assistant.domain.AssistantPhase
import com.jarvis.assistant.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class OrbPalette(val primary: Color, val secondary: Color, val deep: Color)

private fun paletteFor(phase: AssistantPhase) = when (phase) {
    AssistantPhase.IDLE -> OrbPalette(JarvisColors.Violet, JarvisColors.Cyan, Color(0xFF241063))
    AssistantPhase.LISTENING -> OrbPalette(JarvisColors.Cyan, JarvisColors.Violet, Color(0xFF0B3A66))
    AssistantPhase.THINKING -> OrbPalette(JarvisColors.Magenta, JarvisColors.Violet, Color(0xFF4A0F5E))
    AssistantPhase.SPEAKING -> OrbPalette(Color(0xFF9D7BFF), JarvisColors.Cyan, Color(0xFF2A1478))
    AssistantPhase.ERROR -> OrbPalette(JarvisColors.Error, Color(0xFFFF9A6A), Color(0xFF5E0F1F))
}

/**
 * The assistant's face.
 * idle: slow breathing; listening: ripples + size driven by mic level; thinking: rotating
 * gradient ring; speaking: undulating waveform halo; error: red flash.
 */
@Composable
fun JarvisOrb(
    phase: AssistantPhase,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val palette = paletteFor(phase)
    val primary by animateColorAsState(palette.primary, tween(500), label = "orb-primary")
    val secondary by animateColorAsState(palette.secondary, tween(500), label = "orb-secondary")
    val deep by animateColorAsState(palette.deep, tween(500), label = "orb-deep")
    val smoothLevel by animateFloatAsState(
        targetValue = if (phase == AssistantPhase.LISTENING) level else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "orb-level",
    )

    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val rotation by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "rotation",
    )
    val wave by transition.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "wave",
    )
    val ripple by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_000, easing = LinearEasing)), label = "ripple",
    )

    val flash = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == AssistantPhase.ERROR) {
            repeat(2) {
                flash.animateTo(1f, tween(120))
                flash.animateTo(0f, tween(260))
            }
        } else {
            flash.snapTo(0f)
        }
    }

    Canvas(modifier.semantics { contentDescription = "Jarvis orb, ${phase.name.lowercase()}" }) {
        val center = this.center
        val base = size.minDimension * 0.26f
        val scale = when (phase) {
            AssistantPhase.IDLE -> 1f + 0.05f * breathe
            AssistantPhase.LISTENING -> 1.04f + 0.22f * smoothLevel
            AssistantPhase.THINKING -> 0.96f + 0.03f * breathe
            AssistantPhase.SPEAKING -> 1.02f + 0.04f * sin(wave * 2)
            AssistantPhase.ERROR -> 1f
        }
        val r = base * scale

        // Ambient glow.
        drawCircle(
            Brush.radialGradient(listOf(primary.copy(alpha = 0.42f), primary.copy(alpha = 0.08f), Color.Transparent), center, r * 2.4f),
            radius = r * 2.4f,
        )

        when (phase) {
            AssistantPhase.LISTENING -> drawRipples(center, r, ripple, secondary)
            AssistantPhase.THINKING -> drawThinkingRing(center, r, rotation, primary, secondary)
            AssistantPhase.SPEAKING -> drawWaveform(center, r, wave, primary, secondary)
            AssistantPhase.IDLE -> drawCircle(
                secondary.copy(alpha = 0.10f + 0.12f * breathe), radius = r * (1.18f + 0.06f * breathe), style = Stroke(r * 0.02f),
            )
            AssistantPhase.ERROR -> Unit
        }

        // Core sphere with an off-center highlight for depth.
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), secondary, primary, deep),
                center = center + Offset(-r * 0.35f, -r * 0.4f),
                radius = r * 1.55f,
            ),
            radius = r,
        )
        // Inner swirl.
        rotate(rotation * 0.25f, center) {
            drawCircle(
                Brush.sweepGradient(listOf(Color.Transparent, secondary.copy(alpha = 0.35f), Color.Transparent), center),
                radius = r * 0.92f,
            )
        }
        // Specular gloss.
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center + Offset(-r * 0.32f, -r * 0.42f), r * 0.45f),
            radius = r * 0.45f,
            center = center + Offset(-r * 0.32f, -r * 0.42f),
        )

        if (flash.value > 0f) {
            drawCircle(
                Brush.radialGradient(listOf(JarvisColors.Error.copy(alpha = 0.7f * flash.value), Color.Transparent), center, r * 2f),
                radius = r * 2f,
            )
        }
    }
}

private fun DrawScope.drawRipples(center: Offset, r: Float, progress: Float, color: Color) {
    for (i in 0 until 3) {
        val p = (progress + i / 3f) % 1f
        drawCircle(
            color = color.copy(alpha = (1f - p) * 0.55f),
            radius = r * (1.05f + p * 0.95f),
            center = center,
            style = Stroke(width = r * 0.025f * (1f - p) + 1f),
        )
    }
}

private fun DrawScope.drawThinkingRing(center: Offset, r: Float, rotation: Float, a: Color, b: Color) {
    val ringR = r * 1.28f
    val stroke = r * 0.07f
    rotate(rotation, center) {
        drawArc(
            brush = Brush.sweepGradient(listOf(Color.Transparent, a, b, Color.Transparent), center),
            startAngle = 0f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(center.x - ringR, center.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
    rotate(-rotation * 0.6f, center) {
        val r2 = r * 1.45f
        drawArc(
            color = b.copy(alpha = 0.35f),
            startAngle = 40f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(center.x - r2, center.y - r2),
            size = Size(r2 * 2, r2 * 2),
            style = Stroke(width = stroke * 0.4f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawWaveform(center: Offset, r: Float, phase: Float, a: Color, b: Color) {
    for (layer in 0 until 3) {
        val path = Path()
        val ringR = r * (1.14f + layer * 0.1f)
        val amp = r * (0.09f - layer * 0.02f)
        val steps = 120
        for (s in 0..steps) {
            val theta = (s / steps.toFloat()) * 2f * PI.toFloat()
            val offset = amp * sin(6 * theta + phase * (1 + layer * 0.5f)) * (0.6f + 0.4f * sin(3 * theta - phase))
            val x = center.x + (ringR + offset) * cos(theta)
            val y = center.y + (ringR + offset) * sin(theta)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path,
            brush = Brush.sweepGradient(listOf(a, b, a), center),
            alpha = 0.8f - layer * 0.22f,
            style = Stroke(width = r * 0.03f),
        )
    }
}
