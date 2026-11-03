package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jarvis.assistant.domain.AssistantPhase
import kotlin.math.floor
import kotlin.random.Random

/** One raindrop. Positions and sizes are fractions of the screen height so the rain fits any screen. */
private class Drop(
    val x: Float,
    val offset: Float,
    val speed: Float,
    val length: Float,
    val widthDp: Float,
    val alpha: Float,
    val color: Color,
    val ground: Float,
    val splashes: Boolean,
)

private const val SLANT = 0.12f
private const val SPLASH_SECONDS = 0.28f

private val RainWhite = Color(0xFFE6E9FF)
private val RainCyan = Color(0xFF7FE7FF)
private val RainViolet = Color(0xFFC9A7FF)

private fun makeDrops(random: Random): List<Drop> {
    fun range(from: Float, to: Float) = from + random.nextFloat() * (to - from)
    fun layer(count: Int, speed: ClosedFloatingPointRange<Float>, length: ClosedFloatingPointRange<Float>, width: Float,
              alpha: ClosedFloatingPointRange<Float>, tint: Color, near: Boolean) = List(count) {
        Drop(
            x = range(-SLANT, 1f),
            offset = random.nextFloat(),
            speed = range(speed.start, speed.endInclusive),
            length = range(length.start, length.endInclusive),
            widthDp = width,
            alpha = range(alpha.start, alpha.endInclusive),
            color = lerp(RainWhite, tint, random.nextFloat() * 0.8f),
            ground = if (near) range(0.80f, 0.98f) else 1.08f,
            splashes = near,
        )
    }
    // Far drops are faint, short and slow; near ones bright, long and fast: the speed difference reads as depth.
    return layer(70, 0.85f..1.15f, 0.022f..0.038f, 1f, 0.14f..0.24f, RainViolet, near = false) +
        layer(45, 1.45f..1.85f, 0.045f..0.07f, 1.4f, 0.24f..0.36f, RainCyan, near = false) +
        layer(22, 2.2f..2.8f, 0.08f..0.12f, 2.2f, 0.38f..0.52f, RainViolet, near = true)
}

/**
 * Neon-tinted rain in three depth layers, with splashes from the nearest drops and a rare, soft
 * lightning flash. It slows almost to a stop while Jarvis listens, as if time holds its breath.
 * Animates only while the screen is resumed, so it costs nothing in the background.
 */
@Composable
fun RainEffect(phase: AssistantPhase, modifier: Modifier = Modifier) {
    val drops = remember { makeDrops(Random(7)) }
    val pace by animateFloatAsState(if (phase == AssistantPhase.LISTENING) 0.25f else 1f, tween(900), label = "rain-pace")
    val currentPace by rememberUpdatedState(pace)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val rainTime = remember { mutableFloatStateOf(0f) }
    val flash = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        var last = withFrameNanos { it }
        var clock = 0f
        var nextStrike = 18f + Random.nextFloat() * 25f
        var strikeAt = -1f
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                clock += dt
                rainTime.floatValue += dt * currentPace
                // Lightning: a bright flicker, a dimmer second one, then a slow fade.
                if (clock >= nextStrike) {
                    strikeAt = clock
                    nextStrike = clock + 20f + Random.nextFloat() * 30f
                }
                val since = clock - strikeAt
                flash.floatValue = when {
                    strikeAt < 0f || since > 1.2f -> 0f
                    since < 0.08f -> 1f
                    since < 0.16f -> 0.25f
                    since < 0.24f -> 0.7f
                    else -> 0.7f * (1f - (since - 0.24f) / 0.96f)
                }
            }
        }
    }

    Canvas(modifier) {
        val h = size.height
        val w = size.width
        val t = rainTime.floatValue
        for (d in drops) {
            val travel = (d.ground + d.length) * h
            val distance = d.offset * travel + d.speed * h * t
            val cycles = distance / travel
            val progress = cycles - floor(cycles)
            val len = d.length * h
            // Each cycle the drop enters fully above the screen and lands at its ground line.
            val headY = progress * travel - len
            val x = d.x * w + SLANT * headY
            val strokeWidth = d.widthDp * density
            if (d.splashes) {
                drawLine(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, d.color.copy(alpha = d.alpha)),
                        start = Offset(x - SLANT * len, headY - len),
                        end = Offset(x, headY),
                    ),
                    start = Offset(x - SLANT * len, headY - len),
                    end = Offset(x, headY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                // Splash ring where the drop just landed.
                val age = progress * travel / (d.speed * h)
                if (age < SPLASH_SECONDS) {
                    val k = age / SPLASH_SECONDS
                    val groundY = d.ground * h
                    val gx = d.x * w + SLANT * groundY
                    val radius = (3f + 16f * k) * density
                    drawOval(
                        color = d.color.copy(alpha = 0.5f * (1f - k)),
                        topLeft = Offset(gx - radius, groundY - radius * 0.28f),
                        size = Size(radius * 2, radius * 0.56f),
                        style = Stroke(width = 1.2f * density),
                    )
                    val hop = 10f * density * k * (1f - k) * 4f
                    drawCircle(d.color.copy(alpha = 0.6f * (1f - k)), 1.3f * density, Offset(gx - radius * 0.6f, groundY - hop))
                    drawCircle(d.color.copy(alpha = 0.6f * (1f - k)), 1.1f * density, Offset(gx + radius * 0.5f, groundY - hop * 0.8f))
                }
            } else {
                drawLine(
                    color = d.color.copy(alpha = d.alpha),
                    start = Offset(x - SLANT * len, headY - len),
                    end = Offset(x, headY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        if (flash.floatValue > 0f) drawRect(Color(0xFFC8D4FF).copy(alpha = 0.13f * flash.floatValue))
    }
}
