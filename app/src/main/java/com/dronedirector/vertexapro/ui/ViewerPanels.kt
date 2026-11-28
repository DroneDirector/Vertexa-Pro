package com.dronedirector.vertexapro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.formatCount
import com.dronedirector.vertexapro.viewer.JobState
import com.dronedirector.vertexapro.viewer.ViewerController

enum class Panel { NONE, PARTS, LIGHT, POINTS, CAPTURE, EXPORT, VIEWS, CAMERA, AIRCRAFT }

@Composable
private fun PanelShell(title: String, subtitle: String?, p: Palette, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.width(320.dp).heightIn(max = 560.dp).glass(p, RoundedCornerShape(26.dp), strong = true)
            .pressable { }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (subtitle != null) Text(subtitle, color = p.muted, fontSize = 12.sp)
            }
            Box(Modifier.size(34.dp).clip(CircleShape).pressable(onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Close", tint = p.muted, modifier = Modifier.size(18.dp))
            }
        }
        VSpace(12.dp)
        content()
    }
}

@Composable
private fun SwitchRow(text: String, sub: String?, checked: Boolean, enabled: Boolean = true, p: Palette, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(text, color = if (enabled) p.text else p.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = p.muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent2, checkedThumbColor = Color.White),
        )
    }
}

// ---------------------------------------------------------------- parts

@Composable
fun PartsPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = c.parts.filter { query.isBlank() || it.name.contains(query, true) }
    PanelShell("Parts", "${c.parts.size} part${if (c.parts.size == 1) "" else "s"} · tap a part in the view to select it", p, onClose) {
        SwitchRow(
            "Split into loose parts",
            if (c.canSplit) "Every piece that isn't joined to the rest becomes its own part. Turns on by itself for single-mesh models"
            else "Available for OBJ models",
            c.splitLoose, c.canSplit, p,
        ) { c.updateSplit(it) }
        VSpace(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip("Show all", false, p) { c.showAllParts() }
            PillChip("Hide all", false, p) { c.hideAllParts() }
        }
        if (c.parts.size > 10) {
            VSpace(10.dp)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.glass).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = p.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search parts", color = p.muted, fontSize = 14.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        VSpace(10.dp)
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.index }) { part ->
                val visible = part.index !in c.hiddenParts
                val sel = part.index == c.selectedPart
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .then(if (sel) Modifier.border(1.5.dp, Brush.linearGradient(listOf(p.accent, p.accent2)), RoundedCornerShape(14.dp)) else Modifier)
                        .background(if (sel) p.accent.copy(alpha = 0.12f) else p.glass)
                        .pressable { c.selectPart(if (sel) -1 else part.index) }
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).pressable { c.setPartVisible(part.index, !visible) }, contentAlignment = Alignment.Center) {
                        Icon(if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (visible) "Hide" else "Show",
                            tint = if (visible) p.accent else p.muted, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(part.name, color = if (visible) p.text else p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (part.triangles > 0) "${formatCount(part.triangles)} triangles" else "${formatCount(part.points)} points",
                            color = p.muted, fontSize = 11.sp)
                    }
                    Box(Modifier.size(38.dp).clip(CircleShape).pressable { c.selectPart(part.index); c.focusPart(part.index) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CenterFocusWeak, "Zoom to part", tint = p.muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Hidden parts are always one tap away: tap a name to show it again, or show them all. */
@Composable
fun HiddenPartsBar(c: ViewerController, p: Palette, hidden: List<com.dronedirector.vertexapro.viewer.PartUi>) {
    Row(
        Modifier.padding(bottom = 10.dp, start = 12.dp, end = 12.dp).widthIn(max = 560.dp).glass(p, RoundedCornerShape(22.dp), strong = true)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = p.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("${hidden.size} hidden", color = p.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hidden.take(30).forEach { part ->
                PillChip(part.name.ifBlank { "Part ${part.index + 1}" }, false, p, trailing = null) { c.setPartVisible(part.index, true) }
            }
        }
        Spacer(Modifier.width(6.dp))
        PillChip("Show all", true, p) { c.showAllParts() }
    }
}

/** Floating actions for the part selected in the view. */
@Composable
fun SelectedPartBar(c: ViewerController, p: Palette, onRename: () -> Unit) {
    val part = c.parts.firstOrNull { it.index == c.selectedPart } ?: return
    Row(
        Modifier.padding(bottom = 10.dp).widthIn(max = 560.dp).glass(p, RoundedCornerShape(22.dp), strong = true)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF9E26)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 160.dp)) {
            Text(part.name, color = p.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (part.triangles > 0) "${formatCount(part.triangles)} triangles" else "${formatCount(part.points)} points", color = p.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (c.canEdit) PillChip(if (c.hinges.containsKey(part.index)) "Hinge ✓" else "Hinge", false, p) { c.startHinge(part.index) }
            if (c.canEdit && part.triangles > 0) PillChip("Edit with brush", true, p) { c.editPartWithBrush(part.index) }
            if (c.canEdit && c.parts.size > 1) PillChip("Combine…", false, p) { c.startCombine(part.index) }
            PillChip("Rename", false, p) { onRename() }
            PillChip("Focus", false, p) { c.focusPart(part.index) }
            PillChip("Isolate", false, p) { c.isolatePart(part.index) }
            PillChip("Hide", false, p) { c.setPartVisible(part.index, false) }
        }
        Box(Modifier.size(36.dp).clip(CircleShape).pressable { c.selectPart(-1) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Deselect", tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

// ---------------------------------------------------------------- lighting

private val lightNames = listOf(
    "Studio" to "Balanced key + fill",
    "Soft box" to "Even, gentle light",
    "Dramatic" to "Strong side light",
    "Outdoor" to "Sun and sky",
    "Rim light" to "Backlit edges",
    "Flat" to "No shading",
)

@Composable
fun LightingPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    PanelShell("Studio lighting", "Changes how solid and textured models are lit", p, onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lightNames.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col, (name, sub) ->
                        val i = row * 2 + col
                        val sel = c.lighting == i
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .then(if (sel) Modifier.background(p.accentBrush) else Modifier.background(p.glass).border(1.dp, p.border, RoundedCornerShape(16.dp)))
                                .pressable { c.updateLighting(i) }.padding(12.dp),
                        ) {
                            Text(name, color = if (sel) Color.White else p.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(sub, color = if (sel) Color.White.copy(alpha = 0.85f) else p.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- points

@Composable
fun PointsPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    PanelShell("Point cloud", "${formatCount(c.stats.points)} points", p, onClose) {
        Text("Point size", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = c.pointSize, onValueChange = { c.updatePointSize(it) }, valueRange = 1f..14f,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border),
        )
        VSpace(6.dp)
        Text("Colour", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Original", "Single", "Height").forEachIndexed { i, label ->
                PillChip(label, c.pointColorMode == i, p) { c.updatePointColorMode(i) }
            }
        }
        VSpace(6.dp)
        Text(
            when (c.pointColorMode) {
                0 -> "Colours stored in the file (white if none)."
                1 -> "One clean colour for every point."
                else -> "Coloured from lowest (blue) to highest (red)."
            },
            color = p.muted, fontSize = 12.sp,
        )
    }
}

// ---------------------------------------------------------------- capture

@Composable
fun CapturePanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    var transparent by remember { mutableStateOf(true) }
    PanelShell("Capture", "Save what you see", p, onClose) {
        SwitchRow("Transparent background", "PNG without the background and floor grid", transparent, true, p) { transparent = it }
        VSpace(10.dp)
        GradientButton("Snapshot", Icons.Outlined.PhotoCamera, p, Modifier.fillMaxWidth(), subtitle = "PNG · Pictures/Vertexa Pro") {
            c.snapshot(transparent)
        }
        VSpace(10.dp)
        GlassButton("Turntable video", Icons.Outlined.Movie, p, Modifier.fillMaxWidth(), subtitle = "One full turn · 6 s · 1080×1080 MP4") {
            c.recordTurntable()
        }
    }
}

// ---------------------------------------------------------------- export

@Composable
fun ExportPanel(c: ViewerController, p: Palette, onPickFolder: (Int, Boolean) -> Unit, onExtract: () -> Unit, onClose: () -> Unit) {
    var format by remember { mutableIntStateOf(0) }
    var visibleOnly by remember { mutableStateOf(false) }
    PanelShell("Convert & save", "Writes a new file — the original is never changed", p, onClose) {
        Text("Format", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip("OBJ + MTL + textures", format == 0, p) { format = 0 }
            PillChip("STL", format == 1, p) { format = 1 }
        }
        if (c.canEdit) {
            VSpace(6.dp)
            PillChip("GLB · parts with pivots", format == 3, p) { format = 3 }
        }
        if (c.parts.size > 1 && c.canEdit) {
            VSpace(6.dp)
            PillChip("STL · one file per part", format == 2, p) { format = 2 }
        }
        VSpace(6.dp)
        Text(
            when (format) {
                0 -> "Every part is saved as its own object, in place. Textures are copied next to it."
                1 -> "Binary STL for 3D printing (geometry only), all parts in one file."
                3 -> "glTF binary for Blender, Unreal and MSFS tools: every part is its own object, with its hinge (or centre) as pivot — ready to animate."
                else -> "A separate STL for every visible part, each in its original position."
            },
            color = p.muted, fontSize = 12.sp,
        )
        if (c.parts.size > 1) {
            VSpace(6.dp)
            SwitchRow("Visible parts only", "Leave out the parts you've hidden", visibleOnly, true, p) { visibleOnly = it }
        }
        VSpace(10.dp)
        GradientButton("Choose folder & save", Icons.Outlined.SaveAlt, p, Modifier.fillMaxWidth()) { onPickFolder(format, visibleOnly) }
        if (c.isArchive) {
            VSpace(14.dp)
            Box(Modifier.fillMaxWidth().size(1.dp).background(p.border))
            VSpace(14.dp)
            GlassButton("Extract archive", Icons.Outlined.FolderZip, p, Modifier.fillMaxWidth(),
                subtitle = "Save the original files from the ZIP") { onExtract() }
        }
    }
}

// ---------------------------------------------------------------- job card

@Composable
fun JobCard(job: JobState, p: Palette, onCancel: (() -> Unit)?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.padding(24.dp).widthIn(max = 380.dp).glass(p, RoundedCornerShape(26.dp), strong = true).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(job.title, color = if (job.failed) p.danger else p.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
        VSpace(10.dp)
        if (!job.done) {
            LinearProgressIndicator(
                progress = { job.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                color = p.accent, trackColor = p.border,
            )
            VSpace(8.dp)
            Text("${(job.progress * 100).toInt()}%", color = p.muted, fontSize = 13.sp)
            if (onCancel != null) {
                VSpace(10.dp)
                Text("Cancel", color = p.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).pressable(onCancel).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        } else {
            if (job.message != null) Text(job.message, color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            VSpace(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Close", null, p, onClick = onDismiss)
                if (job.result != null && job.mime != null) {
                    GradientButton("Share", Icons.Outlined.Share, p) {
                        val send = Intent(Intent.ACTION_SEND).setType(job.mime).putExtra(Intent.EXTRA_STREAM, job.result)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share"))
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------- saved views

@Composable
fun ViewsPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    PanelShell("Saved views", "Camera angles for this model · the last view is remembered", p, onClose) {
        GradientButton("Save current view", Icons.Outlined.BookmarkAdd, p, Modifier.fillMaxWidth()) { c.saveView() }
        VSpace(10.dp)
        if (c.views.isEmpty()) {
            Text("No saved views yet.", color = p.muted, fontSize = 13.sp)
        }
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(c.views.size) { i ->
                val v = c.views[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.glass)
                        .pressable { c.goToView(i) }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bookmark, null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(v.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(38.dp).clip(CircleShape).pressable { c.deleteView(i) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, "Delete", tint = p.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- view presets

@Composable
fun CameraPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    PanelShell("View", "Look straight at the model from any side", p, onClose) {
        val names = listOf("Top", "Bottom", "Front", "Back", "Left", "Right")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.chunked(3).forEachIndexed { row, trio ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trio.forEachIndexed { col, n ->
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.glass)
                                .border(1.dp, p.border, RoundedCornerShape(14.dp))
                                .pressable { c.viewPreset(row * 3 + col) }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(n, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    }
                }
            }
        }
        VSpace(10.dp)
        SwitchRow("Flat view (no perspective)", "Straight lines stay straight — best for drawing cuts", c.ortho, true, p) { c.toggleOrtho() }
    }
}

// ---------------------------------------------------------------- aircraft

@Composable
fun AircraftPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(c.aircraftType) { c.checkRealSize() }
    PanelShell("Aircraft", "Real size and control surfaces", p, onClose) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Type", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            VSpace(6.dp)
            com.dronedirector.vertexapro.viewer.AircraftProfiles.all.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { prof ->
                        val i = com.dronedirector.vertexapro.viewer.AircraftProfiles.all.indexOf(prof)
                        PillChip(prof.name, c.aircraftType == i, p) { c.aircraftType = i }
                    }
                }
            }
            VSpace(8.dp)
            Text("Real size", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val sz = c.airSize
            val prof = c.airProfile
            if (sz != null && !prof.generic && sz.byLength > 0) {
                val lenAfterSpan = sz.lengthUnits * sz.bySpan
                Text(
                    "Real: ${"%.2f".format(prof.lengthM)} m long, ${"%.2f".format(prof.spansM.first { it.first == sz.spanName }.second)} m span (${sz.spanName}). " +
                        "Your model: ${"%.3g".format(sz.lengthUnits)} × ${"%.3g".format(sz.spanUnits)} units." +
                        if (kotlin.math.abs(sz.byLength / sz.bySpan - 1) > 0.06)
                            " Its proportions differ from the real one (by span it would be ${"%.2f".format(lenAfterSpan)} m long)."
                        else " Proportions match.",
                    color = p.muted, fontSize = 12.sp,
                )
                VSpace(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("Scale by length", false, p) { c.applyRealSize(true) }
                    PillChip("By wingspan", false, p) { c.applyRealSize(false) }
                }
            } else {
                Text("Pick a type, or measure two points and type the real distance.", color = p.muted, fontSize = 12.sp)
            }
            if (c.measurePoints.size >= 2) {
                VSpace(8.dp)
                var meters by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.glass).border(1.dp, p.border, RoundedCornerShape(12.dp)).padding(10.dp)) {
                        if (meters.isEmpty()) Text("Measured distance in m", color = p.muted, fontSize = 13.sp)
                        BasicTextField(meters, { meters = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } }, singleLine = true,
                            textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(6.dp))
                    PillChip("Set", false, p) { meters.replace(',', '.').toDoubleOrNull()?.let { c.applyMeasuredSize(it) } }
                }
            }
            VSpace(14.dp)
            Text("Control surfaces", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            SwitchRow("Flaps", "Inboard and outboard flaps on both wings", c.airFlaps, true, p) { c.airFlaps = it }
            SwitchRow("Ailerons", null, c.airAilerons, true, p) { c.airAilerons = it }
            SwitchRow(
                "Follow panel lines on the texture",
                if (prof.generic) "Always on for unknown types: the lines painted on the wing decide where the cut goes"
                else "Moves the outlines onto the painted panel lines",
                c.airTexture || prof.generic, !prof.generic, p,
            ) { c.airTexture = it }
            VSpace(8.dp)
            if (c.airBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(c.airStatus ?: "Working…", color = p.text, fontSize = 13.sp)
                }
            } else {
                GradientButton(if (c.airAilerons && !c.airFlaps) "Cut ailerons" else "Cut flaps", Icons.Outlined.ContentCut, p, Modifier.fillMaxWidth()) {
                    c.cutControlSurfaces()
                }
                c.airStatus?.let { VSpace(6.dp); Text(it, color = p.muted, fontSize = 12.sp) }
            }
            VSpace(6.dp)
            Text(
                "Only the wing between its top and bottom skin is cut — engines, pylons, landing gear and the tail are left alone. " +
                    "Each surface gets a hinge at its front edge.",
                color = p.muted, fontSize = 11.sp,
            )
        }
    }
}
