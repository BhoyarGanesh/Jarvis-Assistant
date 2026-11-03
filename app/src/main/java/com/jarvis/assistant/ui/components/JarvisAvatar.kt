package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jarvis.assistant.R
import com.jarvis.assistant.domain.AssistantPhase
import com.jarvis.assistant.voice.Mouth
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Where the mouth and eye patches sit on the 1536 x 2660 base picture. */
private val MOUTH_AT = IntOffset(690, 950)
private val EYES_AT = IntOffset(448, 684)

private enum class MouthShape { CLOSED, HALF, OPEN }

/**
 * The anime assistant, filling the whole screen. Her lips follow the loudness of the voice that is
 * playing ([mouth]), she blinks every few seconds and breathes gently, and a coloured glow rises from
 * the edges to show the phase (cyan listening and pulsing with your voice, magenta thinking, violet
 * speaking, red error). The picture covers the screen with her face kept in view.
 */
@Composable
fun JarvisAvatar(
    phase: AssistantPhase,
    listeningLevel: Float,
    mouth: Mouth,
    modifier: Modifier = Modifier,
) {
    val base = ImageBitmap.imageResource(R.drawable.avatar_base)
    val mouthHalf = ImageBitmap.imageResource(R.drawable.avatar_mouth_half)
    val mouthOpen = ImageBitmap.imageResource(R.drawable.avatar_mouth_open)
    val eyesClosed = ImageBitmap.imageResource(R.drawable.avatar_eyes_closed)

    val palette = paletteFor(phase)
    val glowColor by animateColorAsState(palette.primary, tween(600), label = "avatar-glow")

    val loop = rememberInfiniteTransition(label = "avatar")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "breath")
    val pulse by loop.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "pulse")
    val voice by animateFloatAsState(
        targetValue = if (phase == AssistantPhase.LISTENING) listeningLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "voice",
    )
    val glowStrength by animateFloatAsState(
        targetValue = when (phase) {
            AssistantPhase.IDLE -> 0.12f
            AssistantPhase.LISTENING -> 0.35f
            AssistantPhase.THINKING -> 0.30f
            AssistantPhase.SPEAKING -> 0.25f
            AssistantPhase.ERROR -> 0.45f
        },
        animationSpec = tween(600),
        label = "glow-strength",
    )

    val shape = rememberMouthShape(mouth)
    val blinking = rememberBlink()

    Canvas(
        modifier
            .semantics { contentDescription = "Jarvis" }
            .graphicsLayer {
                // Breathing: a slow, slight swell from the shoulders.
                val b = sin(breath * 2 * Math.PI).toFloat()
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = 1f + 0.006f * b
                scaleY = 1f + 0.006f * b
            },
    ) {
        // Cover the screen; centre horizontally and pin the top so her face stays in view.
        val scale = max(size.width / base.width, size.height / base.height)
        val left = (size.width - base.width * scale) / 2
        fun place(image: ImageBitmap, x: Int, y: Int) = drawImage(
            image = image,
            dstOffset = IntOffset((left + x * scale).roundToInt(), (y * scale).roundToInt()),
            dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
            filterQuality = FilterQuality.High,
        )
        place(base, 0, 0)
        when (shape) {
            MouthShape.CLOSED -> Unit
            MouthShape.HALF -> place(mouthHalf, MOUTH_AT.x, MOUTH_AT.y)
            MouthShape.OPEN -> place(mouthOpen, MOUTH_AT.x, MOUTH_AT.y)
        }
        if (blinking) place(eyesClosed, EYES_AT.x, EYES_AT.y)

        val strength = when (phase) {
            AssistantPhase.LISTENING -> glowStrength + 0.35f * voice
            AssistantPhase.THINKING, AssistantPhase.ERROR -> glowStrength * pulse
            else -> glowStrength * (0.8f + 0.2f * pulse)
        }
        drawEdgeGlow(glowColor, strength.coerceIn(0f, 0.8f))
    }
}

/** Light rising from the bottom and sides, tinted by the phase. */
private fun DrawScope.drawEdgeGlow(color: Color, strength: Float) {
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to color.copy(alpha = strength),
        ),
    )
    val side = size.width * 0.28f
    drawRect(
        Brush.horizontalGradient(
            0f to color.copy(alpha = strength * 0.6f),
            1f to Color.Transparent,
            startX = 0f,
            endX = side,
        ),
        size = size.copy(width = side),
    )
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to color.copy(alpha = strength * 0.6f),
            startX = size.width - side,
            endX = size.width,
        ),
        topLeft = Offset(size.width - side, 0f),
        size = size.copy(width = side),
    )
}

/** Maps voice loudness (or a talking rhythm for the phone voice) to one of the three mouth pictures. */
@Composable
private fun rememberMouthShape(mouth: Mouth): MouthShape {
    // Previous shape, for hysteresis; plain memory so composition never writes to state it reads.
    val previous = remember { arrayOf(MouthShape.CLOSED) }
    var talkingShape by remember { mutableStateOf(MouthShape.CLOSED) }
    LaunchedEffect(mouth is Mouth.Talking) {
        if (mouth !is Mouth.Talking) return@LaunchedEffect
        // Phone text-to-speech has no audio levels: syllable-like open/close with natural variation.
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now ->
                val t = (now - start) / 1000.0
                val syllable = abs(sin(t * 2 * Math.PI * 4.2))
                val phrase = 0.55 + 0.45 * sin(t * 2 * Math.PI * 0.7)
                talkingShape = shapeFor((syllable * phrase).toFloat(), talkingShape)
            }
        }
    }
    val shape = when (mouth) {
        is Mouth.Talking -> talkingShape
        is Mouth.Level -> shapeFor(mouth.level, previous[0])
        Mouth.Silent -> MouthShape.CLOSED
    }
    previous[0] = shape
    return shape
}

/** Thresholds with a little hysteresis so the mouth doesn't flicker between two pictures. */
private fun shapeFor(level: Float, current: MouthShape): MouthShape = when (current) {
    MouthShape.CLOSED -> when {
        level > 0.80f -> MouthShape.OPEN
        level > 0.35f -> MouthShape.HALF
        else -> MouthShape.CLOSED
    }
    MouthShape.HALF -> when {
        level > 0.80f -> MouthShape.OPEN
        level < 0.30f -> MouthShape.CLOSED
        else -> MouthShape.HALF
    }
    MouthShape.OPEN -> when {
        level < 0.30f -> MouthShape.CLOSED
        level < 0.70f -> MouthShape.HALF
        else -> MouthShape.OPEN
    }
}

/** Natural blinking: every 2.5-6 seconds, sometimes a double blink. */
@Composable
private fun rememberBlink(): Boolean {
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2_500, 6_000))
            repeat(if (Random.nextFloat() < 0.2f) 2 else 1) {
                closed = true
                delay(120)
                closed = false
                delay(140)
            }
        }
    }
    return closed
}
