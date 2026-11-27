package com.dronedirector.vertexapro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.formatBytes
import com.dronedirector.vertexapro.update.Release
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val release: Release) : UpdatePhase
    data class NeedPermission(val release: Release) : UpdatePhase
    data class Downloading(val release: Release, val progress: Float, val done: Long, val speed: Long) : UpdatePhase
    data class Installing(val release: Release) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

fun UpdatePhase.isOverlay() = this is UpdatePhase.Available || this is UpdatePhase.NeedPermission ||
    this is UpdatePhase.Downloading || this is UpdatePhase.Installing || this is UpdatePhase.Failed

@Composable
fun UpdateOverlay(
    phase: UpdatePhase,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val p = LocalPalette.current
    val warping = phase is UpdatePhase.Downloading || phase is UpdatePhase.Installing
    val warpAmount by animateFloatAsState(if (warping) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "warp")

    Box(
        Modifier.fillMaxSize().background(Color(0xFF04050A).copy(alpha = 0.55f + 0.45f * warpAmount))
            .pressable { },
        contentAlignment = Alignment.Center,
    ) {
        val progress = when (phase) {
            is UpdatePhase.Downloading -> phase.progress
            is UpdatePhase.Installing -> 1f
            else -> 0f
        }
        if (warpAmount > 0.01f) WarpField(progress, phase is UpdatePhase.Installing, warpAmount, p)

        when (phase) {
            is UpdatePhase.Available, is UpdatePhase.NeedPermission -> {
                val rel = (phase as? UpdatePhase.Available)?.release ?: (phase as UpdatePhase.NeedPermission).release
                Column(
                    Modifier.padding(24.dp).widthIn(max = 400.dp).glass(p, RoundedCornerShape(30.dp), strong = true).padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WireLogo(96.dp, listOf(p.accent, p.accent2, p.accent3), speed = 6000)
                    VSpace(14.dp)
                    Text("Update available", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Version ${rel.version}" + if (rel.size > 0) " · ${formatBytes(rel.size)}" else "",
                        color = p.muted, fontSize = 13.sp,
                    )
                    if (rel.notes.isNotBlank()) {
                        VSpace(14.dp)
                        Text(
                            rel.notes, color = p.text, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(16.dp)).background(p.glass)
                                .verticalScroll(rememberScrollState()).padding(14.dp),
                        )
                    }
                    VSpace(20.dp)
                    if (phase is UpdatePhase.NeedPermission) {
                        Text(
                            "Allow Vertexa Pro to install updates, then come back here.",
                            color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                        VSpace(12.dp)
                        GradientButton("Allow installs", Icons.Outlined.SystemUpdate, p, Modifier.fillMaxWidth(), onClick = onGrantPermission)
                    } else {
                        GradientButton("Update now", Icons.Outlined.SystemUpdate, p, Modifier.fillMaxWidth(), onClick = onUpdate)
                    }
                    VSpace(6.dp)
                    Text(
                        "Later", color = p.muted, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).pressable(onLater).padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            is UpdatePhase.Downloading, is UpdatePhase.Installing -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (phase is UpdatePhase.Installing) "✦" else "${(progress * 100).toInt()}%",
                        color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black,
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (phase is UpdatePhase.Downloading) {
                        Text("Downloading v${phase.release.version}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        val sz = if (phase.release.size > 0) " of ${formatBytes(phase.release.size)}" else ""
                        val speedTxt = if (phase.speed > 0) " · ${formatBytes(phase.speed)}/s" else ""
                        Text("${formatBytes(phase.done)}$sz$speedTxt", color = Color(0xFFAEB4C6), fontSize = 13.sp)
                    } else {
                        Text("Warping to v${(phase as UpdatePhase.Installing).release.version}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Confirm the install when Android asks", color = Color(0xFFAEB4C6), fontSize = 13.sp)
                    }
                }
            }
            is UpdatePhase.Failed -> {
                Column(
                    Modifier.padding(24.dp).widthIn(max = 380.dp).glass(p, RoundedCornerShape(28.dp), strong = true).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Update failed", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    VSpace(6.dp)
                    Text(phase.message, color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    VSpace(16.dp)
                    Row {
                        GlassButton("Close", null, p, onClick = onLater)
                        Spacer(Modifier.width(10.dp))
                        GradientButton("Retry", null, p, onClick = onUpdate)
                    }
                }
            }
            else -> {}
        }
    }
}

private class Particle(val angle: Float, val radius: Float, val speed: Float, val hue: Float, val width: Float)

/** Spiral particle vortex with a glowing progress ring; bursts into hyperspace streaks while installing. */
@Composable
private fun WarpField(progress: Float, installing: Boolean, amount: Float, p: Palette) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) time += ((n - last) / 1e9f).coerceAtMost(0.05f)
                last = n
            }
        }
    }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(installing) {
        if (installing) burst.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        else burst.snapTo(0f)
    }
    val particles = remember {
        val r = Random(42)
        List(220) { Particle(r.nextFloat() * 6.283f, r.nextFloat(), 0.4f + r.nextFloat() * 0.9f, r.nextFloat(), 1f + r.nextFloat() * 2.5f) }
    }
    val shownProgress by animateFloatAsState(progress, tween(250), label = "p")
    val colors = listOf(p.accent, p.accent2, p.accent3)

    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.maxDimension * 0.75f
        val ringR = min(size.width, size.height) * 0.27f
        val b = burst.value
        val accel = 1f + 2.5f * shownProgress + 10f * b

        // nebula glow
        drawCircle(
            Brush.radialGradient(listOf(p.accent2.copy(alpha = 0.35f * amount), Color.Transparent), c, ringR * 2.6f),
            ringR * 2.6f, c,
        )

        particles.forEach { pt ->
            val col = lerp(colors[(pt.hue * 2.999f).toInt()], colors[((pt.hue * 2.999f).toInt() + 1) % 3], (pt.hue * 3f) % 1f)
            if (b < 0.02f) {
                // inward spiral
                val phase = ((pt.radius - time * 0.09f * pt.speed * accel) % 1f + 1f) % 1f
                val r = ringR * 1.05f + phase * (maxR - ringR)
                val ang = pt.angle + time * 0.6f * pt.speed * accel + (1f - phase) * 2.2f
                val ang2 = ang - 0.06f * pt.speed * accel
                val r2 = r + 14f * pt.speed * accel
                drawLine(
                    col.copy(alpha = (0.15f + 0.75f * phase) * amount),
                    Offset(c.x + cos(ang) * r, c.y + sin(ang) * r),
                    Offset(c.x + cos(ang2) * r2, c.y + sin(ang2) * r2),
                    strokeWidth = pt.width, cap = StrokeCap.Round,
                )
            } else {
                // hyperspace: streaks rush outward
                val phase = ((pt.radius + time * 0.8f * pt.speed) % 1f)
                val r = ringR * 0.3f + phase * phase * maxR * (0.6f + b)
                val len = 20f + 260f * b * phase * pt.speed
                drawLine(
                    col.copy(alpha = (0.3f + 0.7f * phase) * amount),
                    Offset(c.x + cos(pt.angle) * r, c.y + sin(pt.angle) * r),
                    Offset(c.x + cos(pt.angle) * (r + len), c.y + sin(pt.angle) * (r + len)),
                    strokeWidth = pt.width * (1f + b), cap = StrokeCap.Round,
                )
            }
        }

        // progress ring with layered glow
        val tl = Offset(c.x - ringR, c.y - ringR)
        val sz = Size(ringR * 2, ringR * 2)
        drawCircle(Color.White.copy(alpha = 0.08f * amount), ringR, c, style = Stroke(6.dp.toPx()))
        val sweep = Brush.sweepGradient(colors + colors.first(), c)
        rotate(-90f + time * 20f, c) {
            for (g in 3 downTo 1) {
                drawArc(
                    sweep, 0f, 360f * shownProgress, false, tl, sz,
                    alpha = 0.12f * amount, style = Stroke((6 + g * 7).dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawArc(sweep, 0f, 360f * shownProgress, false, tl, sz, alpha = amount, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
        // install flash
        if (b > 0f) {
            val fr = ringR * (0.2f + 3.5f * b)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = (1f - b) * 0.9f), p.accent.copy(alpha = (1f - b) * 0.4f), Color.Transparent), c, fr),
                fr, c,
            )
        }
    }
}
