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
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
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
    Triple("Through the first object", "The nearest object only, top and bottom — flaps and ailerons without touching what's behind", 3),
    Triple("Cut through everything", "Everything inside the outline, all the way through", 0),
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
    val live = c.drawPath
    val flap = c.flapPreview
    val outline = c.outlineForOverlay(pts)
    Canvas(Modifier.fillMaxSize()) {
        val glow0 = Color(0xFFFF9E26)
        if (live.size >= 4) {
            for (i in 0 until live.size / 2 - 1) {
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(live[i * 2], live[i * 2 + 1]), Offset(live[i * 2 + 2], live[i * 2 + 3]),
                    strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(glow0, Offset(live[i * 2], live[i * 2 + 1]), Offset(live[i * 2 + 2], live[i * 2 + 3]),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            if (c.cutShape != com.dronedirector.vertexapro.viewer.CutGeometry.FREEHAND) {
                val m = live.size / 2
                drawLine(glow0, Offset(live[(m - 1) * 2], live[(m - 1) * 2 + 1]), Offset(live[0], live[1]),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (flap.size >= 8) {
            val m = flap.size / 2
            for (i in 0 until m) {
                val a = Offset(flap[i * 2], flap[i * 2 + 1])
                val b = Offset(flap[((i + 1) % m) * 2], flap[((i + 1) % m) * 2 + 1])
                drawLine(Color.Black.copy(alpha = 0.35f), a, b, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(glow0, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        // smoothed curve preview
        if (outline !== pts && outline.size >= 6) {
            val m = outline.size / 2
            for (i in 0 until m) {
                val a = Offset(outline[i * 2], outline[i * 2 + 1])
                val b = Offset(outline[((i + 1) % m) * 2], outline[((i + 1) % m) * 2 + 1])
                drawLine(p.accent.copy(alpha = if (i == m - 1) 0.5f else 0.95f), a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        val n = pts.size / 2
        if (n == 0) return@Canvas
        fun pt(i: Int) = Offset(pts[i * 2], pts[i * 2 + 1])
        fun ok(i: Int) = !pts[i * 2].isNaN()
        val glow = Color(0xFFFF9E26)
        val brush = Brush.linearGradient(listOf(p.accent, glow))
        val curve = outline !== pts
        for (i in 0 until n - 1) {
            if (!ok(i) || !ok(i + 1)) continue
            if (curve) {
                drawLine(Color.White.copy(alpha = 0.35f), pt(i), pt(i + 1), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            } else {
                drawLine(Color.Black.copy(alpha = 0.35f), pt(i), pt(i + 1), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(brush, pt(i), pt(i + 1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        if (n >= 3 && ok(n - 1) && ok(0) && !curve) {
            drawLine(
                glow.copy(alpha = 0.8f), pt(n - 1), pt(0), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f), dash),
            )
        }
        val dense = c.cutShape >= com.dronedirector.vertexapro.viewer.CutGeometry.FREEHAND
        if (dense) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                if (!ok(i) || !ok(j) || (j == 0 && c.cutShape == com.dronedirector.vertexapro.viewer.CutGeometry.FREEHAND && c.askConnect)) continue
                drawLine(Color.Black.copy(alpha = 0.35f), pt(i), pt(j), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(brush, pt(i), pt(j), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            if (ok(0)) drawCircle(glow, 6.dp.toPx(), pt(0))
            return@Canvas
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
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Points", "Curve", "Freehand", "Rectangle", "Ellipse", "Flap").forEachIndexed { i, label ->
                PillChip(label, c.cutShape == i, p) {
                    if (c.cutShape != i) { c.cutShape = i; c.cutHint = shapeHint(i) }
                }
            }
        }
        VSpace(8.dp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PillChip("Top", false, p) { c.viewPreset(0) }
            PillChip("Front", false, p) { c.viewPreset(2) }
            PillChip("Side", false, p) { c.viewPreset(5) }
            PillChip("Flat view", c.ortho, p) { c.toggleOrtho() }
            if (c.cutShape <= 1) {
                PillChip("Snap", c.snapOn, p) { c.snapOn = !c.snapOn }
                PillChip("Straight 45°", c.straightOn, p) { c.straightOn = !c.straightOn }
            }
        }
        if (c.cutShape == com.dronedirector.vertexapro.viewer.CutGeometry.FLAP && c.cutPoints.size >= 2) {
            VSpace(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chord depth ${(c.flapDepth * 100).toInt()}%", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(130.dp))
                androidx.compose.material3.Slider(
                    value = c.flapDepth, onValueChange = { c.setFlapDepthValue(it) }, valueRange = 0.08f..0.5f,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border),
                )
            }
            if (c.flapPreview.size >= 8) {
                GradientButton("Use outline", Icons.Outlined.ContentCut, p, Modifier.fillMaxWidth()) { c.useFlapOutline() }
            }
        }
        VSpace(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", c.canUndoPoint, p) { c.undoCutPoint() }
            ToolIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", c.canRedoPoint, p) { c.redoCutPoint() }
            if (c.cutShape <= 1 && c.cutPoints.size >= 3) PillChip("Close outline", false, p) { c.askConnect = true }
            Spacer(Modifier.weight(1f))
            PillChip("Cancel", false, p) { c.cancelCut() }
        }
    }
}

fun shapeHint(shape: Int) = when (shape) {
    5 -> "Tap the wing's trailing edge where the flap starts, then where it ends."
    0 -> "Tap points on the model around the area. Drag to rotate between taps."
    1 -> "Tap points around the area — they're joined by a smooth curve."
    2 -> "Draw around the area with one finger. Two fingers move the view."
    3 -> "Drag a rectangle over the area. Two fingers move the view."
    else -> "Drag an oval over the area. Two fingers move the view."
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
    var mode by remember { mutableStateOf(if (c.cutMode in listOf(0, 1, 2, 3)) c.cutMode else 3) }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 440.dp).glass(p, RoundedCornerShape(30.dp), strong = true)
                .verticalScroll(rememberScrollState()).padding(22.dp),
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
            VSpace(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.glass).pressable { c.mirrorCut = !c.mirrorCut }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mirror cut", color = p.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Also cut the matching part on the other side (left ↔ right)", color = p.muted, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(
                    checked = c.mirrorCut, onCheckedChange = { c.mirrorCut = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = p.accent2, checkedThumbColor = Color.White),
                )
            }
            if (c.mirrorCut) {
                VSpace(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mirror across", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    listOf("X", "Y", "Z").forEachIndexed { i, a ->
                        PillChip(a + if (c.mirrorAuto && c.mirrorAxis == i) " (auto)" else "", c.mirrorAxis == i, p) {
                            c.mirrorAxis = i; c.mirrorAuto = false
                        }
                    }
                }
            }
            VSpace(12.dp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.glass).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gap", color = p.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (c.gapMm <= 0.05f) "None" else String.format(java.util.Locale.US, "%.1f mm", c.gapMm), color = p.accent,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text("Space between the new part and the model (uses the measure unit: 1 unit = ${listOf("m", "cm", "mm", "in", "ft")[c.unit]})",
                    color = p.muted, fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = c.gapMm, onValueChange = { c.gapMm = (it * 2).roundToInt() / 2f }, valueRange = 0f..30f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border),
                )
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
        if (c.hasRedo) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).pressable { c.redoLastCut() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Redo, null, tint = p.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Redo", color = p.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        PillChip("OK", false, p) { c.lastCut = null }
    }
}

@Composable
private fun ToolIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, p: Palette, onClick: () -> Unit) {
    val fg = if (enabled) p.text else p.muted.copy(alpha = 0.5f)
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (enabled) p.glass else Color.Transparent)
            .border(1.dp, p.border, RoundedCornerShape(50))
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

// ---------------------------------------------------------------- hinge

@Composable
fun HingeOverlay(c: ViewerController) {
    var pts by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(c.hingePart) {
        while (c.hingePart >= 0) {
            withFrameNanos { }
            pts = c.projectHingePoints()
        }
        pts = FloatArray(0)
    }
    val col = Color(0xFF7CF29A)
    Canvas(Modifier.fillMaxSize()) {
        val n = pts.size / 2
        fun ok(i: Int) = i < n && !pts[i * 2].isNaN()
        fun pt(i: Int) = Offset(pts[i * 2], pts[i * 2 + 1])
        if (ok(0) && ok(1)) {
            drawLine(Color.Black.copy(alpha = 0.4f), pt(0), pt(1), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            drawLine(col, pt(0), pt(1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        for (i in 0 until n) if (ok(i)) {
            drawCircle(Color.Black.copy(alpha = 0.4f), 8.dp.toPx(), pt(i))
            drawCircle(col, 6.dp.toPx(), pt(i))
        }
    }
}

@Composable
fun HingeToolbar(c: ViewerController, p: Palette) {
    val part = c.parts.firstOrNull { it.index == c.hingePart }
    val h = c.hinges[c.hingePart]
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).glass(p, RoundedCornerShape(22.dp), strong = true).padding(14.dp)) {
        Text("Hinge · ${part?.name ?: ""}", color = p.text, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1)
        Text(
            if (h == null) "Tap two points along the hinge line (snaps to edges and corners)."
            else "Move the slider to swing the part around its hinge.",
            color = p.muted, fontSize = 12.sp,
        )
        if (h != null) {
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(java.util.Locale.US, "%+.0f°", h.deg), color = p.accent, fontWeight = FontWeight.Black,
                    fontSize = 15.sp, modifier = Modifier.width(56.dp))
                androidx.compose.material3.Slider(
                    value = h.deg, onValueChange = { c.setDeflection(c.hingePart, it.roundToInt().toFloat()) }, valueRange = -60f..60f,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border),
                )
            }
        }
        VSpace(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (h != null) {
                PillChip("0°", false, p) { c.setDeflection(c.hingePart, 0f) }
                PillChip("New hinge line", false, p) { c.removeHinge(c.hingePart) }
            }
            Spacer(Modifier.weight(1f))
            PillChip("Done", false, p) { c.endHinge() }
        }
    }
}
