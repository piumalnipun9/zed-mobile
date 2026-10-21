package dev.zed.mobile

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

internal enum class MascotMood { Idle, Working, Sleeping, Done }

/**
 * Zeb, the app's mascot: a flat purple block with hands, legs and dark eyes. It appears only at
 * moments that would otherwise be blank or uncertain: a chat loading for the first time, an empty
 * thread, pairing, and a sleeping desktop.
 *
 * Drawn on a 196 x 134 unit grid: the body spans 14..182, each hand is a 14-unit block outside it.
 */
private const val GridWidth = 196f
private const val GridHeight = 134f
private val Eye = Color(0xFF1C1336)

@Composable
internal fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    mood: MascotMood = MascotMood.Idle,
) {
    // While idle, now and then it gives a short happy cheer.
    var cheering by remember { mutableStateOf(false) }
    LaunchedEffect(mood) {
        cheering = false
        if (mood != MascotMood.Idle) return@LaunchedEffect
        while (true) {
            delay((8_000L..20_000L).random())
            cheering = true
            delay(1_800L)
            cheering = false
        }
    }
    val mood = if (cheering) MascotMood.Done else mood
    val transition = rememberInfiniteTransition(label = "mascot")
    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3600
                0f at 0
                0f at 3300
                1f at 3380
                0f at 3480
            },
        ),
        label = "blink",
    )
    // Looking around: centre, left, centre, right, back to centre.
    val look by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 6000
                0f at 0
                0f at 1200
                -1f at 1500 using FastOutSlowInEasing
                -1f at 2600
                0f at 2900 using FastOutSlowInEasing
                0f at 3800
                1f at 4100 using FastOutSlowInEasing
                1f at 5300
                0f at 5600 using FastOutSlowInEasing
            },
        ),
        label = "look",
    )
    // Working reads: eyes sweep slowly left to right, then jump back to the start of the next line.
    val scan by transition.animateFloat(
        initialValue = -1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1800
                -1f at 0
                1f at 1450 using LinearEasing
                -1f at 1800 using FastOutSlowInEasing
            },
        ),
        label = "scan",
    )
    // A 0..1 phase driving bobbing, hand swings and steps.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (mood) {
                    MascotMood.Working -> 600
                    MascotMood.Done -> 900
                    else -> 2400
                },
                easing = LinearEasing,
            ),
        ),
        label = "phase",
    )
    Canvas(
        modifier = modifier
            .size(size, size * GridHeight / GridWidth)
            .semantics { contentDescription = "Zeb, the Zed Mobile mascot" },
    ) {
        val unit = this.size.width / GridWidth
        val wave = sin(phase * 2 * Math.PI).toFloat()
        val bodyColor = ZedColors.Brand

        // Body lift: idle breathes, working hops on each step, done jumps, sleeping stays down.
        val lift = when (mood) {
            MascotMood.Idle -> 1.5f * (wave + 1)
            MascotMood.Working -> 4f * kotlin.math.abs(wave)
            MascotMood.Done -> 8f * kotlin.math.abs(wave)
            MascotMood.Sleeping -> 0f
        }
        // Sleeping sinks the top of the body and breathes slowly.
        val bodyTop = if (mood == MascotMood.Sleeping) 14f + 2f * wave else 0f
        fun rect(x: Float, y: Float, w: Float, h: Float, color: Color = bodyColor) = drawRect(
            color = color,
            topLeft = Offset(x * unit, y * unit),
            size = Size(w * unit, h * unit),
        )

        // Legs: working walks by lifting them in turn; done kicks both up mid-jump.
        val legX = if (mood == MascotMood.Working) listOf(52f, 130f) else listOf(42f, 140f)
        legX.forEachIndexed { index, x ->
            val step = when (mood) {
                MascotMood.Working -> if ((index == 0) == (wave > 0)) 6f * kotlin.math.abs(wave) else 0f
                else -> 0f
            }
            val top = 112f - lift
            rect(x, top, 14f, 22f + lift - step)
        }

        // Body.
        rect(14f, bodyTop - lift, 168f, 112f - bodyTop)

        // Hands: idle sways gently, working pumps them in turn, done waves them overhead.
        listOf(0f, 182f).forEachIndexed { index, x ->
            val side = if (index == 0) 1f else -1f
            val handY = when (mood) {
                MascotMood.Idle -> 52f + 3f * wave * side
                MascotMood.Working -> 52f + 8f * wave * side
                MascotMood.Done -> 22f + 8f * wave * side
                MascotMood.Sleeping -> 62f
            }
            rect(x, handY - lift, 14f, 28f)
        }

        // Eyes.
        val shift = when (mood) {
            MascotMood.Idle -> 10f * look
            MascotMood.Working -> 12f * scan
            else -> 0f
        }
        // Working looks down, as if reading the code in front of it.
        val drop = if (mood == MascotMood.Working) 14f else 0f
        listOf(66f, 130f).forEach { cx ->
            drawEye(mood, (cx + shift) * unit, (53f + drop - lift) * unit + bodyTop / 2 * unit, unit, blink)
        }
    }
}

private fun DrawScope.drawEye(mood: MascotMood, cx: Float, cy: Float, unit: Float, blink: Float) {
    when (mood) {
        MascotMood.Idle, MascotMood.Working -> {
            val width = 18f * unit
            val open = (if (mood == MascotMood.Idle) 26f else 22f) * unit
            val height = maxOf(open * (1f - blink), 4f * unit)
            drawRoundRect(
                color = Eye,
                topLeft = Offset(cx - width / 2, cy - height / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(minOf(width, height) / 2),
            )
        }
        // Sleeping shows closed lids (a downward curve), done a happy squint (an upward one).
        MascotMood.Sleeping, MascotMood.Done -> {
            val bend = if (mood == MascotMood.Sleeping) 11f else -14f
            val path = Path().apply {
                moveTo(cx - 12f * unit, cy)
                quadraticTo(cx, cy + bend * unit, cx + 12f * unit, cy)
            }
            drawPath(path, Eye, style = Stroke(width = 6f * unit, cap = StrokeCap.Round))
        }
    }
}

/** The mascot with a short title and line of detail, for blank or waiting states. */
@Composable
internal fun MascotMessage(
    title: String,
    detail: String? = null,
    mood: MascotMood = MascotMood.Idle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mascot(size = 64.dp, mood = mood, modifier = Modifier.padding(bottom = 6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = ZedColors.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
