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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.R
import com.jarvis.assistant.domain.AssistantPhase
import com.jarvis.assistant.voice.Mouth
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Where the mouth and eye patches sit on the 510 px base picture. */
private const val BASE_SIZE = 510f
private val MOUTH_AT = IntOffset(246, 251)
private val EYES_AT = IntOffset(188, 170)

private enum class MouthShape { CLOSED, HALF, OPEN }

/**
 * The anime assistant. Her lips follow the loudness of the voice that is playing ([mouth]),
 * she blinks every few seconds and breathes gently; a glowing ring shows the phase
 * (cyan listening and pulsing with your voice, magenta thinking, violet speaking, red error).
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
    val primary by animateColorAsState(palette.primary, tween(500), label = "avatar-primary")
    val secondary by animateColorAsState(palette.secondary, tween(500), label = "avatar-secondary")

    val loop = rememberInfiniteTransition(label = "avatar")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3_600, easing = LinearEasing)), label = "breath")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "spin")
    val glow by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "glow")
    val ring by animateFloatAsState(
        targetValue = if (phase == AssistantPhase.LISTENING) listeningLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ring",
    )

    val shape = rememberMouthShape(mouth)
    val blinking = rememberBlink()

    Box(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Jarvis" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawGlow(phase, primary, secondary, glow, ring, spin)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(18.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    // Breathing: a slow, slight rise and swell.
                    val b = sin(breath * 2 * Math.PI).toFloat()
                    scaleX = 1.04f + 0.008f * b
                    scaleY = 1.04f + 0.008f * b
                    translationY = -3f * b
                },
        ) {
            val scale = size.width / BASE_SIZE
            fun patch(image: ImageBitmap, at: IntOffset) = drawImage(
                image = image,
                dstOffset = IntOffset((at.x * scale).roundToInt(), (at.y * scale).roundToInt()),
                dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
                filterQuality = FilterQuality.High,
            )
            drawImage(
                image = base,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            when (shape) {
                MouthShape.CLOSED -> Unit
                MouthShape.HALF -> patch(mouthHalf, MOUTH_AT)
                MouthShape.OPEN -> patch(mouthOpen, MOUTH_AT)
            }
            if (blinking) patch(eyesClosed, EYES_AT)
        }
    }
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
                val level = (syllable * phrase).toFloat()
                talkingShape = shapeFor(level, talkingShape)
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

private fun DrawScope.drawGlow(
    phase: AssistantPhase,
    primary: Color,
    secondary: Color,
    glow: Float,
    ring: Float,
    spin: Float,
) {
    val radius = size.minDimension / 2 - 10.dp.toPx()
    // Soft halo behind the portrait.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(primary.copy(alpha = 0.45f * glow + 0.25f * ring), Color.Transparent),
            center = center,
            radius = radius * (1.18f + 0.12f * ring),
        ),
        radius = radius * (1.18f + 0.12f * ring),
    )
    val stroke = Stroke(width = (3.dp.toPx() + 5.dp.toPx() * ring))
    if (phase == AssistantPhase.THINKING) {
        rotate(spin) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(primary, secondary, Color.Transparent, primary), center),
                radius = radius,
                style = stroke,
            )
        }
    } else {
        drawCircle(
            brush = Brush.linearGradient(
                listOf(primary, secondary),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            radius = radius,
            alpha = if (phase == AssistantPhase.IDLE) 0.55f + 0.25f * glow else 0.9f,
            style = stroke,
        )
    }
}
