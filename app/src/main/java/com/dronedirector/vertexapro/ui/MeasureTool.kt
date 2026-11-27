package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.viewer.ViewerController
import kotlin.math.roundToInt

private val unitNames = listOf("m", "cm", "mm", "in", "ft")

/** Line between the two measure points with the distance shown at its middle. */
@Composable
fun MeasureOverlay(c: ViewerController, p: Palette) {
    var pts by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(c.measureActive) {
        while (c.measureActive) {
            withFrameNanos { }
            pts = c.projectMeasurePoints()
        }
        pts = FloatArray(0)
    }
    val n = pts.size / 2
    Canvas(Modifier.fillMaxSize()) {
        fun ok(i: Int) = i < n && !pts[i * 2].isNaN()
        fun pt(i: Int) = Offset(pts[i * 2], pts[i * 2 + 1])
        if (ok(0) && ok(1)) {
            drawLine(Color.Black.copy(alpha = 0.4f), pt(0), pt(1), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color(0xFFFFD54A), pt(0), pt(1), strokeWidth = 2.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f)))
        }
        for (i in 0 until n) {
            if (!ok(i)) continue
            drawCircle(Color.Black.copy(alpha = 0.4f), 9.dp.toPx(), pt(i))
            drawCircle(Color(0xFFFFD54A), 6.dp.toPx(), pt(i))
            drawCircle(Color.White, 2.5.dp.toPx(), pt(i))
        }
    }
    val d = c.measureDelta()
    if (d != null && n >= 2 && !pts[0].isNaN() && !pts[2].isNaN()) {
        val mx = (pts[0] + pts[2]) / 2
        val my = (pts[1] + pts[3]) / 2
        Box(Modifier.offset { IntOffset(mx.roundToInt() - 70, my.roundToInt() - 52) }) {
            Text(
                c.formatLength(d[0]), color = Color(0xFF1B1300), fontWeight = FontWeight.Black, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFD54A)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun MeasureToolbar(c: ViewerController, p: Palette) {
    val d = c.measureDelta()
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).glass(p, RoundedCornerShape(22.dp), strong = true).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFFFD54A)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Straighten, null, tint = Color(0xFF1B1300), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Measure", color = p.text, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    when {
                        d != null -> "${c.formatLength(d[0])}  ·  X ${c.formatLength(d[1])}  Y ${c.formatLength(d[2])}  Z ${c.formatLength(d[3])}"
                        c.measurePoints.size == 1 -> "Tap the second point."
                        else -> "Tap two points on the model."
                    },
                    color = p.muted, fontSize = 12.sp,
                )
            }
            PillChip("Done", false, p) { c.toggleMeasure() }
        }
        Spacer(Modifier.size(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("1 model unit =", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            unitNames.forEachIndexed { i, u -> PillChip(u, c.unit == i, p) { c.unit = i } }
            val s = c.stats.size
            if (s.any { it > 0 }) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "Model: ${c.formatLength(s[0])} × ${c.formatLength(if (c.upZ) s[2] else s[1])} × ${c.formatLength(if (c.upZ) s[1] else s[2])}",
                    color = p.muted, fontSize = 12.sp,
                )
            }
        }
    }
}
