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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.viewer.ViewerController

val cutModes = listOf(
    Triple("Cut through", "Everything inside the outline, all the way through — flaps, ailerons, rudders", 0),
    Triple("One wall", "Outer and inner skin only, never the far side — doors, hatches, panels", 2),
    Triple("Surface", "Only the surface you outlined — a single skin or panel", 1),
)

/** Draws the outline the user is tapping out, following the model as the view rotates. */
@Composable
fun CutOverlay(c: ViewerController, p: Palette) {
    var pts by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(c.cutActive) {
        while (c.cutActive) {
            withFrameNanos { }
            pts = c.projectCutPoints()
        }
        pts = FloatArray(0)
    }
    val t = rememberInfiniteTransition(label = "cut")
    val pulse by t.animateFloat(0.6f, 1.25f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val dash by t.animateFloat(0f, 40f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dash")
    Canvas(Modifier.fillMaxSize()) {
        val n = pts.size / 2
        if (n == 0) return@Canvas
        fun pt(i: Int) = Offset(pts[i * 2], pts[i * 2 + 1])
        fun ok(i: Int) = !pts[i * 2].isNaN()
        val glow = Color(0xFFFF9E26)
        val brush = Brush.linearGradient(listOf(p.accent, glow))
        for (i in 0 until n - 1) {
            if (!ok(i) || !ok(i + 1)) continue
            drawLine(Color.Black.copy(alpha = 0.35f), pt(i), pt(i + 1), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            drawLine(brush, pt(i), pt(i + 1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        if (n >= 3 && ok(n - 1) && ok(0)) {
            drawLine(
                glow.copy(alpha = 0.8f), pt(n - 1), pt(0), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f), dash),
            )
        }
        for (i in 0 until n) {
            if (!ok(i)) continue
            if (i == 0) {
                drawCircle(glow.copy(alpha = 0.25f), 18.dp.toPx() * (if (n >= 3) pulse else 1f), pt(0))
                drawCircle(glow, 8.dp.toPx(), pt(0))
                drawCircle(Color.White, 4.dp.toPx(), pt(0))
            } else {
                drawCircle(Color.Black.copy(alpha = 0.35f), 7.dp.toPx(), pt(i))
                drawCircle(p.accent, 5.dp.toPx(), pt(i))
                drawCircle(Color.White, 2.dp.toPx(), pt(i))
            }
        }
    }
}

@Composable
fun CutToolbar(c: ViewerController, p: Palette) {
    Column(
        Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).glass(p, RoundedCornerShape(22.dp), strong = true).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(p.accentBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ContentCut, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Cut tool", color = p.text, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(c.cutHint ?: "", color = p.muted, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
        VSpace(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillChip("Undo point", false, p, trailing = null) { c.undoCutPoint() }
            if (c.cutPoints.size >= 3) PillChip("Close outline", false, p) { c.askConnect = true }
            Spacer(Modifier.weight(1f))
            PillChip("Cancel", false, p) { c.cancelCut() }
        }
    }
}

/** "Connect your last point with the start point?" */
@Composable
fun ConnectPrompt(c: ViewerController, p: Palette) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pressable { c.askConnect = false }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 360.dp).glass(p, RoundedCornerShape(30.dp), strong = true).pressable { }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.accent, Color(0xFFFF9E26)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Link, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            VSpace(16.dp)
            Text("Close the outline?", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            VSpace(6.dp)
            Text(
                "Connect your last point to the start point to finish the shape (${c.cutPoints.size} points).",
                color = p.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            VSpace(20.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Keep adding", null, p) { c.askConnect = false }
                GradientButton("Connect", null, p) { c.connectOutline() }
            }
        }
    }
}

@Composable
fun CreatePartCard(c: ViewerController, p: Palette) {
    var name by remember { mutableStateOf(c.nextCutName()) }
    var mode by remember { mutableStateOf(c.cutMode) }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 420.dp).glass(p, RoundedCornerShape(30.dp), strong = true).padding(22.dp),
        ) {
            Text("Create separate part", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("The new part stays exactly in place.", color = p.muted, fontSize = 13.sp)
            VSpace(16.dp)
            Text("Name", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            VSpace(6.dp)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.glass).border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(12.dp)) {
                BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
            }
            VSpace(14.dp)
            Text("How to cut", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            VSpace(6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cutModes.forEach { (label, desc, m) ->
                    val sel = mode == m
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .then(if (sel) Modifier.background(p.accentBrush) else Modifier.background(p.glass).border(1.dp, p.border, RoundedCornerShape(16.dp)))
                            .pressable { mode = m }.padding(12.dp),
                    ) {
                        Text(label, color = if (sel) Color.White else p.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(desc, color = if (sel) Color.White.copy(alpha = 0.88f) else p.muted, fontSize = 12.sp)
                    }
                }
            }
            VSpace(18.dp)
            if (c.cutBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = p.accent, strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Cutting and sealing…", color = p.text, fontSize = 14.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Back", null, p) { c.createPartOpen = false }
                    GradientButton("Create part", Icons.Outlined.ContentCut, p) {
                        c.cutMode = mode
                        c.createPart(name.ifBlank { c.nextCutName() }, mode)
                    }
                }
            }
        }
    }
}

@Composable
fun LastCutBar(c: ViewerController, p: Palette) {
    val msg = c.lastCut ?: return
    Row(
        Modifier.padding(bottom = 10.dp).widthIn(max = 560.dp).glass(p, RoundedCornerShape(20.dp), strong = true)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ContentCut, null, tint = p.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(msg, color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        if (c.hasUndo) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).pressable { c.undoLastCut() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = p.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        PillChip("OK", false, p) { c.lastCut = null }
    }
}

@Composable
fun RenameDialog(initial: String, p: Palette, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pressable { onDone(null) }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 380.dp).glass(p, RoundedCornerShape(26.dp), strong = true).pressable { }.padding(22.dp),
        ) {
            Text("Rename part", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
            VSpace(12.dp)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.glass).border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(12.dp)) {
                BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
            }
            VSpace(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Cancel", null, p) { onDone(null) }
                GradientButton("Save", null, p) { onDone(name) }
            }
        }
    }
}
