package com.dronedirector.vertexapro.ui

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun Modifier.glass(p: Palette, shape: Shape = RoundedCornerShape(22.dp), strong: Boolean = false): Modifier =
    this
        .clip(shape)
        .background(if (strong) p.glassStrong else p.glass, shape)
        .border(1.dp, p.border, shape)

@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = ripple(),
    onClick = onClick,
)

/** Animated aurora background used on home / settings. */
@Composable
fun AuroraBackground(p: Palette, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "a")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(p.bg2, p.bg)))
        val w = size.width
        val h = size.height
        val c1 = Offset(w * (0.25f + 0.18f * cos(a)), h * (0.18f + 0.06f * sin(a * 2)))
        val c2 = Offset(w * (0.80f + 0.12f * sin(a)), h * (0.35f + 0.08f * cos(a)))
        val c3 = Offset(w * (0.5f + 0.2f * sin(a + 1.3f)), h * (0.85f + 0.04f * cos(a * 1.5f)))
        drawCircle(Brush.radialGradient(listOf(p.auroraA, Color.Transparent), c1, w * 0.75f), w * 0.75f, c1)
        drawCircle(Brush.radialGradient(listOf(p.auroraB, Color.Transparent), c2, w * 0.7f), w * 0.7f, c2)
        drawCircle(Brush.radialGradient(listOf(p.auroraA.copy(alpha = p.auroraA.alpha * 0.6f), Color.Transparent), c3, w * 0.6f), w * 0.6f, c3)
        // faint dot grid
        val step = 28.dp.toPx()
        val dot = if (p.isLight) Color(0x140E1220) else Color(0x10FFFFFF)
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                drawCircle(dot, 1.1.dp.toPx(), Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

private val ico: Pair<List<FloatArray>, List<Pair<Int, Int>>> by lazy {
    val f = ((1 + sqrt(5.0)) / 2).toFloat()
    val v = listOf(
        floatArrayOf(-1f, f, 0f), floatArrayOf(1f, f, 0f), floatArrayOf(-1f, -f, 0f), floatArrayOf(1f, -f, 0f),
        floatArrayOf(0f, -1f, f), floatArrayOf(0f, 1f, f), floatArrayOf(0f, -1f, -f), floatArrayOf(0f, 1f, -f),
        floatArrayOf(f, 0f, -1f), floatArrayOf(f, 0f, 1f), floatArrayOf(-f, 0f, -1f), floatArrayOf(-f, 0f, 1f),
    )
    val e = mutableListOf<Pair<Int, Int>>()
    for (i in v.indices) for (j in i + 1 until v.size) {
        val dx = v[i][0] - v[j][0]; val dy = v[i][1] - v[j][1]; val dz = v[i][2] - v[j][2]
        if (dx * dx + dy * dy + dz * dz < 4.1f) e += i to j
    }
    v to e
}

/** Rotating glowing wireframe icosahedron — the app's emblem. */
@Composable
fun WireLogo(
    size: Dp,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    speed: Int = 14000,
    reveal: Float = 1f,
    glow: Boolean = true,
) {
    val t = rememberInfiniteTransition(label = "logo")
    val a by t.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "spin")
    Canvas(modifier.size(size)) {
        val (verts, edges) = ico
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2
        val cy = this.size.height / 2
        val ry = a
        val rx = 0.45f + 0.15f * sin(a * 2)
        val pts = verts.map { v ->
            // rotate Y then X
            var x = v[0] * cos(ry) + v[2] * sin(ry)
            var z = -v[0] * sin(ry) + v[2] * cos(ry)
            val y = v[1] * cos(rx) - z * sin(rx)
            z = v[1] * sin(rx) + z * cos(rx)
            val persp = 4.2f / (4.2f - z)
            x *= persp
            Triple(Offset(cx + x * r * 0.5f, cy - y * persp * r * 0.5f), z, persp)
        }
        val brush = Brush.linearGradient(colors, Offset(0f, 0f), Offset(this.size.width, this.size.height))
        if (glow) {
            drawCircle(
                Brush.radialGradient(listOf(colors.first().copy(alpha = 0.28f * reveal), Color.Transparent), Offset(cx, cy), r),
                r, Offset(cx, cy),
            )
        }
        val shown = (edges.size * reveal).toInt().coerceIn(0, edges.size)
        edges.take(shown).forEach { (i, j) ->
            val zA = (pts[i].second + pts[j].second) / 2
            val alpha = (0.35f + 0.65f * ((zA + 1.9f) / 3.8f)).coerceIn(0.15f, 1f)
            drawLine(brush, pts[i].first, pts[j].first, strokeWidth = r * 0.028f, cap = StrokeCap.Round, alpha = alpha * reveal)
        }
        pts.forEach { (o, z, _) ->
            val al = ((z + 1.9f) / 3.8f).coerceIn(0.2f, 1f) * reveal
            if (glow) drawCircle(colors.last().copy(alpha = 0.25f * al), r * 0.07f, o)
            drawCircle(Color.White.copy(alpha = al), r * 0.028f, o)
        }
    }
}

@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    p: Palette,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .shadow(24.dp, shape, ambientColor = p.accent, spotColor = p.accent2)
            .clip(shape)
            .background(p.accentBrush)
            .pressable(onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    icon: ImageVector?,
    p: Palette,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Box(modifier.glass(p).pressable(onClick).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(p.accentBrush, alpha = 0.18f),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = p.accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Text(text, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (subtitle != null) Text(subtitle, color = p.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    p: Palette,
    active: Boolean = false,
    size: Dp = 46.dp,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (active) Modifier.background(p.accentBrush)
                else Modifier.background(p.glassStrong).border(1.dp, p.border, CircleShape),
            )
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (active) Color.White else p.text, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun FormatBadge(ext: String, p: Palette, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(p.accentBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext.uppercase().take(5),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (ext.length > 3) 10.sp else 13.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
fun Chip(text: String, p: Palette) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(p.glass)
            .border(1.dp, p.border, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp) }
}

@Composable
fun SectionTitle(text: String, p: Palette) {
    Text(
        text.uppercase(), color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

