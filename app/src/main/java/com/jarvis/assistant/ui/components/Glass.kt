package com.jarvis.assistant.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisColors
import kotlin.math.cos
import kotlin.math.sin

val GlassShape = RoundedCornerShape(24.dp)

/**
 * Frosted-glass surface: translucent fill, diagonal sheen and a hairline gradient border.
 * Real backdrop blur isn't available for arbitrary Compose content, so the background
 * itself is kept soft (see [AuroraBackground]) to sell the effect.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    tint: Color = Color.White,
    strong: Boolean = false,
    contentPadding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fillAlpha = if (strong) 0.14f else 0.07f
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = fillAlpha + 0.05f), tint.copy(alpha = fillAlpha * 0.5f)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f))),
                shape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Deep gradient with slowly drifting, blurred color blobs. [accent] tints the blobs by assistant state. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    accent: Color = JarvisColors.Violet,
    content: @Composable BoxScope.() -> Unit,
) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "aurora-t",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JarvisColors.Deep, JarvisColors.Night, Color(0xFF040309)))),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(60.dp) else Modifier),
        ) {
            val w = size.width
            val h = size.height
            fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy),
            )
            blob(accent.copy(alpha = 0.45f), w * (0.2f + 0.1f * cos(t)), h * (0.18f + 0.05f * sin(t)), w * 0.7f)
            blob(JarvisColors.Cyan.copy(alpha = 0.18f), w * (0.85f + 0.08f * sin(t * 2)), h * (0.45f + 0.06f * cos(t)), w * 0.6f)
            blob(JarvisColors.Magenta.copy(alpha = 0.16f), w * (0.3f + 0.1f * sin(t)), h * (0.9f + 0.04f * cos(t * 2)), w * 0.65f)
        }
        content()
    }
}
