package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.viewer.CutGeometry
import com.dronedirector.vertexapro.viewer.ViewerController
import kotlin.math.roundToInt

private val cutTabs = listOf("Draw", "Smart", "Refine", "View")

/** Top-centre cut menu: only shown while the cut tool is on, its tools grouped in four tabs. */
@Composable
fun CutToolbar(c: ViewerController, p: Palette) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.widthIn(max = 520.dp).glass(p, RoundedCornerShape(22.dp), strong = true).padding(10.dp)) {
        // header: hint, undo / redo, close outline, leave
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(p.accentBrush), contentAlignment = Alignment.Center) {
                if (c.smartBusy || c.aiBusy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.ContentCut, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                c.cutHint ?: "", color = p.muted, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", c.canUndoPoint, p) { c.undoCutPoint() }
            Spacer(Modifier.width(4.dp))
            ToolIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", c.canRedoPoint, p) { c.redoCutPoint() }
            Spacer(Modifier.width(4.dp))
            if (c.cutShape <= CutGeometry.CURVE && c.cutPoints.size >= 3) {
                ToolIcon(Icons.Outlined.Link, "Close outline", true, p) { c.askConnect = true }
                Spacer(Modifier.width(4.dp))
            }
            ToolIcon(Icons.Outlined.Close, "Leave cut tool", true, p) { c.cancelCut() }
        }
        Spacer(Modifier.size(8.dp))
        // tabs
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(p.glass).padding(3.dp)) {
            cutTabs.forEachIndexed { i, label ->
                val sel = tab == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).then(if (sel) Modifier.background(p.accentBrush) else Modifier)
                        .pressable { tab = i }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (sel) Color.White else p.text, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) }
            }
        }
        Spacer(Modifier.size(8.dp))
        when (tab) {
            0 -> DrawTab(c, p)
            1 -> SmartTab(c, p)
            2 -> RefineTab(c, p)
            else -> ViewTab(c, p)
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun LabeledSlider(label: String, value: String, p: Palette, slider: @Composable (Modifier) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(104.dp)) {
            Text(label, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = p.muted, fontSize = 11.sp)
        }
        slider(Modifier.weight(1f))
    }
}

@Composable
private fun sliderColors(p: Palette) = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border)

@Composable
private fun DrawTab(c: ViewerController, p: Palette) {
    ChipRow {
        listOf("Points", "Curve", "Freehand", "Rectangle", "Ellipse", "Flap").forEachIndexed { i, label ->
            PillChip(label, c.cutShape == i && c.cutTool == ViewerController.TOOL_OUTLINE, p) {
                if (c.cutTool != ViewerController.TOOL_OUTLINE) c.chooseCutTool(ViewerController.TOOL_OUTLINE)
                if (c.cutShape != i) { c.cutShape = i; c.cutHint = shapeHint(i) }
            }
        }
    }
    if (c.cutShape == CutGeometry.FLAP && c.cutPoints.size >= 2) {
        Spacer(Modifier.size(6.dp))
        LabeledSlider("Chord depth", "${(c.flapDepth * 100).toInt()}%", p) { m ->
            Slider(c.flapDepth, { c.setFlapDepthValue(it) }, m, valueRange = 0.08f..0.5f, colors = sliderColors(p))
        }
        if (c.flapPreview.size >= 8) GradientButton("Use outline", Icons.Outlined.ContentCut, p, Modifier.fillMaxWidth()) { c.useFlapOutline() }
    }
}

@Composable
private fun SmartTab(c: ViewerController, p: Palette) {
    ChipRow {
        PillChip("AI select", c.cutTool == ViewerController.TOOL_AI, p, trailing = if (c.cutTool == ViewerController.TOOL_AI) Icons.Outlined.AutoAwesome else null) {
            c.chooseCutTool(ViewerController.TOOL_AI)
        }
        PillChip("Magic wand", c.cutTool == ViewerController.TOOL_WAND, p) { c.chooseCutTool(ViewerController.TOOL_WAND) }
        PillChip("Snap to lines", c.lineSnap, p) { c.lineSnap = !c.lineSnap }
        if (c.cutPoints.size >= 3) PillChip("Fit to edges", false, p) { c.fitToEdges() }
        if (c.cutPoints.size >= 3 || c.hasLastOutline) PillChip("Copy to other side", false, p) { c.copyToOtherSide() }
    }
    when (c.cutTool) {
        ViewerController.TOOL_AI -> {
            Spacer(Modifier.size(6.dp))
            ChipRow {
                PillChip("Add", !c.aiExclude, p) { c.aiExclude = false }
                PillChip("Remove", c.aiExclude, p) { c.aiExclude = true }
                if (c.aiPoints.isNotEmpty()) PillChip("Undo tap", false, p) { c.undoAiPoint() }
                if (c.aiPoints.isNotEmpty()) PillChip("Start over", false, p) { c.chooseCutTool(ViewerController.TOOL_AI); c.chooseCutTool(ViewerController.TOOL_AI) }
            }
            Text("Runs on your phone. Tip: a flat Top or Side view gives the cleanest outline.", color = p.muted, fontSize = 11.sp)
        }
        ViewerController.TOOL_WAND -> {
            Spacer(Modifier.size(6.dp))
            LabeledSlider("Tolerance", "${(c.wandTolerance * 100).toInt()}%", p) { m ->
                Slider(c.wandTolerance, { c.updateWandTolerance(it) }, m, valueRange = 0f..1f, colors = sliderColors(p))
            }
        }
    }
}

@Composable
private fun RefineTab(c: ViewerController, p: Palette) {
    ChipRow {
        PillChip("Erase brush", c.cutTool == ViewerController.TOOL_ERASE, p) { c.chooseCutTool(ViewerController.TOOL_ERASE) }
        if (c.eraseSpots.isNotEmpty()) PillChip("Clear erase", false, p) { c.clearErase() }
        if (c.cutShape <= CutGeometry.CURVE) {
            PillChip("Snap", c.snapOn, p) { c.snapOn = !c.snapOn }
            PillChip("Straight 45°", c.straightOn, p) { c.straightOn = !c.straightOn }
        }
        if (c.selectedCutPoint >= 0) PillChip("Delete point", false, p) { c.deleteSelectedPoint() }
    }
    Spacer(Modifier.size(4.dp))
    if (c.cutTool == ViewerController.TOOL_ERASE) {
        LabeledSlider("Brush size", "${c.eraseBrushDp.roundToInt()} dp", p) { m ->
            Slider(c.eraseBrushDp, { c.updateBrush(it) }, m, valueRange = 8f..90f, colors = sliderColors(p))
        }
    }
    if (c.cutPoints.size >= 3 && c.cutShape != CutGeometry.FLAP) {
        LabeledSlider(
            "Depth",
            if (c.depthLo <= 0.001f && c.depthHi >= 0.999f) "Whole model" else "${(c.depthLo * 100).toInt()}–${(c.depthHi * 100).toInt()}% deep", p,
        ) { m ->
            RangeSlider(c.depthLo..c.depthHi, { r -> c.updateDepth(r.start, r.endInclusive) }, m, colors = sliderColors(p))
        }
    }
    LabeledSlider("Magnifier", "${"%.1f".format(c.loupeZoom)}×", p) { m ->
        Slider(c.loupeZoom, { c.updateLoupeZoom(it) }, m, valueRange = 1.5f..6f, colors = sliderColors(p))
    }
}

@Composable
private fun ViewTab(c: ViewerController, p: Palette) {
    ChipRow {
        PillChip("Top", false, p) { c.viewPreset(0) }
        PillChip("Front", false, p) { c.viewPreset(2) }
        PillChip("Side", false, p) { c.viewPreset(5) }
        PillChip("Flat view", c.ortho, p) { c.toggleOrtho() }
        PillChip("Cut lines only", c.linesOnly, p) { c.toggleLinesOnly() }
    }
    Text("Orange shows what will be cut, red what the erase brush protects.", color = p.muted, fontSize = 11.sp)
}

/** ~3 cm magnifier near the top while a point is dragged or the erase brush paints. */
@Composable
fun CutLoupe(c: ViewerController, p: Palette) {
    val img = c.loupeImage ?: return
    val at = c.loupeAt ?: return
    val density = LocalDensity.current
    val side = c.loupeSizePx
    val sideDp = with(density) { side.toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        // stays at the top, on the side away from the finger
        val left = if (at.x > w / 2) margin else w - side - margin
        val top = with(density) { 64.dp.toPx() } + (if (at.y < side + 140 * density.density) with(density) { 200.dp.toPx() } else 0f)
        Box(
            Modifier.statusBarsPadding().offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(sideDp)
                .clip(RoundedCornerShape(18.dp)).border(2.dp, p.accent, RoundedCornerShape(18.dp)),
        ) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)
            val pts = c.projectCutPoints()
            val origin = c.loupeOrigin
            val src = c.loupeSide.toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / src
                fun map(x: Float, y: Float) = Offset((x - origin.x) * k, (y - origin.y) * k)
                val n = pts.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    if (n < 2 || (j == 0 && n < 3)) continue
                    if (pts[i * 2].isNaN() || pts[j * 2].isNaN()) continue
                    drawLine(Color(0xFFFF9E26), map(pts[i * 2], pts[i * 2 + 1]), map(pts[j * 2], pts[j * 2 + 1]), strokeWidth = 2.dp.toPx())
                }
                for (i in 0 until n) {
                    if (pts[i * 2].isNaN()) continue
                    drawCircle(Color.White, 5.dp.toPx(), map(pts[i * 2], pts[i * 2 + 1]), style = Stroke(1.5.dp.toPx()))
                }
                // crosshair on the finger
                val f = map(at.x, at.y)
                val r = if (c.cutTool == ViewerController.TOOL_ERASE) c.eraseBrushDp * density.density * k else 10.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.5f), r, f, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, r, f, style = Stroke(1.5.dp.toPx()))
                drawLine(Color.White, Offset(f.x - 6.dp.toPx(), f.y), Offset(f.x + 6.dp.toPx(), f.y), strokeWidth = 1.dp.toPx())
                drawLine(Color.White, Offset(f.x, f.y - 6.dp.toPx()), Offset(f.x, f.y + 6.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/** First use of AI select: one download, then it works offline. */
@Composable
fun AiDownloadCard(c: ViewerController, p: Palette) {
    val progress = c.aiDownload
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pressable { if (progress == null) c.askAiDownload = false }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 360.dp).glass(p, RoundedCornerShape(30.dp), strong = true).pressable { }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.accent, Color(0xFF3DDC97)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.size(16.dp))
            Text(if (progress == null) "Turn on AI select?" else "Downloading AI…", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.size(6.dp))
            if (progress == null) {
                Text(
                    "Tap a flap, door or panel and the AI outlines it for you. It needs a one-time download of about 45 MB and then runs fully on your phone.",
                    color = p.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Not now", null, p) { c.askAiDownload = false }
                    GradientButton("Download", Icons.Outlined.Download, p) { c.downloadAi() }
                }
            } else {
                Text("${(progress * 100).toInt()}%", color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.size(10.dp))
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = p.accent, trackColor = p.border)
            }
        }
    }
}
