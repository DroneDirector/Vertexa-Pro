package com.dronedirector.vertexapro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Grid4x4
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.formatBytes
import com.dronedirector.vertexapro.data.formatCount
import com.dronedirector.vertexapro.viewer.ModelSurfaceView
import com.dronedirector.vertexapro.viewer.Phase
import com.dronedirector.vertexapro.viewer.ViewModes
import com.dronedirector.vertexapro.viewer.ViewerController
import java.util.Locale

private data class ModeItem(val mode: Int, val label: String, val icon: ImageVector)

private val modeItems = listOf(
    ModeItem(ViewModes.TEXTURED, "Textured", Icons.Outlined.Image),
    ModeItem(ViewModes.SOLID, "Solid", Icons.Outlined.ViewInAr),
    ModeItem(ViewModes.WIRE_CLEAN, "Wireframe", Icons.Outlined.Polyline),
    ModeItem(ViewModes.WIRE, "See-through", Icons.Outlined.GridOn),
    ModeItem(ViewModes.SOLID_WIRE, "Solid + Wire", Icons.Outlined.Layers),
    ModeItem(ViewModes.TEX_WIRE, "Tex + Wire", Icons.Outlined.AutoAwesomeMosaic),
)

@Composable
fun ViewerScreen(
    c: ViewerController,
    onBack: () -> Unit,
    onLinkFolder: () -> Unit,
    onCycleTheme: () -> Unit,
    onModeChanged: (Int) -> Unit,
    onGridChanged: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    var showInfo by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var pendingExport by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val exportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val req = pendingExport
        pendingExport = null
        if (tree != null && req != null) { panel = Panel.NONE; c.export(tree, req.first, req.second) }
    }
    val extractFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) { panel = Panel.NONE; c.extractArchive(tree) }
    }
    fun toggle(p: Panel) { panel = if (panel == p) Panel.NONE else p }
    var renaming by remember { mutableStateOf<Int?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(c.cutHint, c.cutActive) {
        val h = c.cutHint
        if (!c.cutActive && h != null) {
            android.widget.Toast.makeText(ctx, h, android.widget.Toast.LENGTH_LONG).show()
            c.cutHint = null
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var surface by remember { mutableStateOf<ModelSurfaceView?>(null) }

    DisposableEffect(lifecycle, surface) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> surface?.onResume()
                Lifecycle.Event.ON_PAUSE -> surface?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(p.viewBottom)) {
        AndroidView(
            factory = { ctx -> ModelSurfaceView(ctx, c).also { c.view = it; surface = it } },
            modifier = Modifier.fillMaxSize(),
        )
        if (c.cutActive) CutOverlay(c, p)
        if (c.measureActive) MeasureOverlay(c, p)
        if (c.hingePart >= 0) HingeOverlay(c)

        // ---------------- top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, p, contentDescription = "Back") { onBack() }
            Spacer(Modifier.weight(1f))
            Box {
                RoundIconButton(Icons.Outlined.Info, p, active = showInfo, contentDescription = "Model info") { showInfo = !showInfo }
                val errs = c.report.count { it.level == 2 }
                val warns = c.report.count { it.level == 1 }
                if (errs + warns > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape)
                            .background(if (errs > 0) p.danger else Color(0xFFF5B342))
                            .border(2.dp, p.viewBottom, CircleShape),
                    )
                }
            }
        }

        // ---------------- right rail: tools · view · output, scrolls when the screen is short
        Column(
            Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding()
                .padding(end = 12.dp, top = 68.dp, bottom = 104.dp)
                .glass(p, RoundedCornerShape(28.dp), strong = true)
                .verticalScroll(rememberScrollState()).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val btn = 42.dp
            RailLabel("TOOLS", p)
            RoundIconButton(Icons.Outlined.ContentCut, p, active = c.cutActive, size = btn, contentDescription = "Cut tool") {
                panel = Panel.NONE
                if (c.cutActive) c.cancelCut() else c.startCut()
            }
            RoundIconButton(Icons.Outlined.Straighten, p, active = c.measureActive, size = btn, contentDescription = "Measure") {
                panel = Panel.NONE
                c.toggleMeasure()
            }
            RoundIconButton(Icons.Outlined.AccountTree, p, active = panel == Panel.PARTS, size = btn, contentDescription = "Parts") {
                toggle(Panel.PARTS)
                // a model that is one single mesh is split into its loose pieces automatically
                if (panel == Panel.PARTS && c.parts.size <= 1 && c.canSplit && !c.splitLoose && !c.hasUndo) c.updateSplit(true)
            }
            RailDivider(p)
            RailLabel("VIEW", p)
            RoundIconButton(Icons.Outlined.Explore, p, active = panel == Panel.CAMERA, size = btn, contentDescription = "View presets") { toggle(Panel.CAMERA) }
            RoundIconButton(Icons.Outlined.CenterFocusStrong, p, size = btn, contentDescription = "Reset view") { c.resetView() }
            RoundIconButton(Icons.Outlined.Autorenew, p, active = c.autoRotate, size = btn, contentDescription = "Auto rotate") { c.toggleAutoRotate() }
            RoundIconButton(Icons.Outlined.Bookmarks, p, active = panel == Panel.VIEWS, size = btn, contentDescription = "Saved views") { toggle(Panel.VIEWS) }
            RoundIconButton(Icons.Outlined.WbSunny, p, active = panel == Panel.LIGHT, size = btn, contentDescription = "Lighting") { toggle(Panel.LIGHT) }
            if (c.stats.points > 0) {
                RoundIconButton(Icons.Outlined.Grain, p, active = panel == Panel.POINTS, size = btn, contentDescription = "Point cloud") { toggle(Panel.POINTS) }
            }
            RoundIconButton(Icons.Outlined.Grid4x4, p, active = c.grid, size = btn, contentDescription = "Floor grid") {
                c.toggleGrid(); onGridChanged(c.grid)
            }
            AxisButton(c.upZ, p) { c.toggleUpAxis() }
            RoundIconButton(Icons.Outlined.Contrast, p, size = btn, contentDescription = "Theme") { onCycleTheme() }
            RailDivider(p)
            RailLabel("SAVE", p)
            RoundIconButton(Icons.Outlined.PhotoCamera, p, active = panel == Panel.CAPTURE, size = btn, contentDescription = "Capture") { toggle(Panel.CAPTURE) }
            RoundIconButton(Icons.Outlined.SaveAlt, p, active = panel == Panel.EXPORT, size = btn, contentDescription = "Convert & save") { toggle(Panel.EXPORT) }
        }

        // ---------------- bottom: texture warning + mode bar
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!c.cutActive && !c.measureActive) AnimationBar(c, p)
            AnimatedVisibility(c.lastCut != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                LastCutBar(c, p)
            }
            AnimatedVisibility(c.selectedPart >= 0 && !c.cutActive && c.hingePart < 0, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                SelectedPartBar(c, p) { renaming = c.selectedPart }
            }
            AnimatedVisibility(c.missing.isNotEmpty(), enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                MissingBanner(c.missing, p, onLinkFolder) { c.dismissMissing() }
            }
            if (c.texturesTotal > 0 && c.texturesLoaded < c.texturesTotal && c.missing.isEmpty() && c.phase == Phase.READY) {
                Text(
                    "Loading textures ${c.texturesLoaded}/${c.texturesTotal}",
                    color = p.text, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp).glass(p, RoundedCornerShape(50), strong = true)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp).glass(p, RoundedCornerShape(26.dp), strong = true)
                    .horizontalScroll(rememberScrollState()).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                modeItems.forEach { m ->
                    ModeButton(m, c.mode == m.mode, p) { c.updateMode(m.mode); onModeChanged(m.mode) }
                }
            }
        }

        // ---------------- loading
        val hasGeo = c.stats.triangles > 0 || c.stats.points > 0
        AnimatedVisibility(
            c.phase == Phase.LOADING && !hasGeo,
            enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 1.08f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.widthIn(max = 320.dp).glass(p, RoundedCornerShape(28.dp), strong = true).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LoadingRing(c.progress, 120.dp, p)
                    Text(pct(c.progress), color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
                VSpace(18.dp)
                Text(c.stage, color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                VSpace(4.dp)
                Text(
                    "${if (c.stage.startsWith("Unpacking")) "Unpacking" else "Reading"} ${formatBytes((c.stageSize * c.progress).toLong())} of ${formatBytes(c.stageSize)}",
                    color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                VSpace(18.dp)
                Text(
                    "Cancel", color = p.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).pressable { c.cancel(); onBack() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
        AnimatedVisibility(
            c.phase == Phase.LOADING && hasGeo,
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 74.dp),
        ) {
            Row(
                Modifier.glass(p, RoundedCornerShape(50), strong = true).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingRing(c.progress, 22.dp, p, stroke = 3.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Streaming ${pct(c.progress)} · ${formatCount(c.stats.triangles + c.stats.points)} ${if (c.stats.triangles > 0) "tris" else "pts"}",
                    color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Outlined.Close, "Cancel", tint = p.muted,
                    modifier = Modifier.size(22.dp).clip(CircleShape).pressable { c.cancel(); onBack() },
                )
            }
        }

        // ---------------- error
        AnimatedVisibility(c.phase == Phase.ERROR, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 360.dp).glass(p, RoundedCornerShape(28.dp), strong = true).padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = p.danger, modifier = Modifier.size(44.dp))
                VSpace(12.dp)
                Text("Couldn't open this model", color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                VSpace(6.dp)
                Text(c.error ?: "", color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                VSpace(18.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Details", Icons.AutoMirrored.Outlined.Assignment, p) { showReport = true }
                    GradientButton("Back", null, p, onClick = onBack)
                }
            }
        }

        // ---------------- info panel
        AnimatedVisibility(
            showInfo, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.Center),
        ) {
            InfoPanel(c, p, onReport = { showInfo = false; showReport = true }) { showInfo = false }
        }

        // ---------------- cut tool
        AnimatedVisibility(
            c.cutActive, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 66.dp, end = 66.dp, top = 8.dp),
        ) { CutToolbar(c, p) }
        if (c.cutActive) CutLoupe(c, p)
        AnimatedVisibility(
            c.combineFrom >= 0, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 66.dp, end = 66.dp, top = 8.dp),
        ) { CombineBanner(c, p) }
        AnimatedVisibility(
            c.measureActive, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp),
        ) { MeasureToolbar(c, p) }
        AnimatedVisibility(
            c.hingePart >= 0, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 66.dp),
        ) { HingeToolbar(c, p) }

        // ---------------- side panels
        AnimatedVisibility(
            panel != Panel.NONE,
            enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding().padding(end = 72.dp),
        ) {
            val close = { panel = Panel.NONE }
            when (panel) {
                Panel.PARTS -> PartsPanel(c, p, close)
                Panel.LIGHT -> LightingPanel(c, p, close)
                Panel.POINTS -> PointsPanel(c, p, close)
                Panel.CAPTURE -> CapturePanel(c, p, close)
                Panel.VIEWS -> ViewsPanel(c, p, close)
                Panel.CAMERA -> CameraPanel(c, p, close)
                Panel.EXPORT -> ExportPanel(
                    c, p,
                    onPickFolder = { f, vis -> pendingExport = f to vis; exportFolder.launch(null) },
                    onExtract = { extractFolder.launch(null) },
                    onClose = close,
                )
                Panel.NONE -> {}
            }
        }

        // ---------------- capture / export progress
        val job = c.job
        AnimatedVisibility(job != null, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)) {
            if (job != null) {
                JobCard(
                    job, p,
                    onCancel = if (job.title.startsWith("Saving") && !job.title.contains("snapshot")) ({ c.cancelExport() }) else null,
                    onDismiss = { c.dismissJob() },
                )
            }
        }

        if (c.askConnect) ConnectPrompt(c, p)
        if (c.createPartOpen) CreatePartCard(c, p)
        if (c.askAiDownload || c.aiDownload != null) AiDownloadCard(c, p)
        c.combineAsk?.let { CombineDialog(c, p, it) }
        renaming?.let { idx ->
            val current = c.parts.firstOrNull { it.index == idx }?.name ?: ""
            RenameDialog(current, p) { newName -> if (newName != null) c.renamePart(idx, newName); renaming = null }
        }

        // ---------------- load report
        AnimatedVisibility(showReport, enter = fadeIn(), exit = fadeOut()) {
            ReportPanel(c, p) { showReport = false }
        }
    }
}

private fun pct(f: Float) = "${(f.coerceIn(0f, 1f) * 100).toInt()}%"

@Composable
private fun ModeButton(m: ModeItem, selected: Boolean, p: Palette, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (selected) 1f else 0f, tween(250), label = "sel")
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(p.accentBrush, alpha = alpha)
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(m.icon, m.label, tint = if (selected) Color.White else p.text, modifier = Modifier.size(22.dp))
            Text(
                m.label, color = if (selected) Color.White else p.muted, fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AxisButton(z: Boolean, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(p.glassStrong).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (z) "Z" else "Y", color = p.accent, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("UP", color = p.muted, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun LoadingRing(progress: Float, size: Dp, p: Palette, stroke: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "ring")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "prog")
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        val inset = sw / 2
        val arcSize = Size(this.size.width - sw, this.size.height - sw)
        drawArc(p.border, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
        rotate(-90f) {
            drawArc(
                Brush.sweepGradient(listOf(p.accent, p.accent2, p.accent3, p.accent)),
                0f, 360f * shown, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        rotate(spin) {
            drawArc(Color.White.copy(alpha = 0.35f), 0f, 30f, false, Offset(inset, inset), arcSize, style = Stroke(sw * 0.45f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun MissingBanner(names: List<String>, p: Palette, onLink: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).widthIn(max = 560.dp)
            .glass(p, RoundedCornerShape(20.dp), strong = true).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${names.size} texture${if (names.size == 1) "" else "s"} not found",
                color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            )
            Text(names.take(3).joinToString { it.substringAfterLast('/').substringAfterLast('\\') }, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(p.accentBrush).pressable(onLink)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Link folder", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).pressable(onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Dismiss", tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun InfoPanel(c: ViewerController, p: Palette, onReport: () -> Unit, onClose: () -> Unit) {
    val s = c.stats
    Column(
        Modifier.padding(24.dp).widthIn(max = 380.dp).glass(p, RoundedCornerShape(28.dp), strong = true).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormatBadge(Formats.ext(c.source.name), p)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.source.name, color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(c.source.size), color = p.muted, fontSize = 12.sp)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).pressable(onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Close", tint = p.muted, modifier = Modifier.size(20.dp))
            }
        }
        VSpace(16.dp)
        val rows = buildList {
            add("Vertices" to formatCount(s.vertices))
            if (s.triangles > 0) add("Triangles" to formatCount(s.triangles))
            if (s.points > 0) add("Points" to formatCount(s.points))
            add("Materials" to s.materials.toString())
            add("Textures" to if (c.texturesTotal == 0) "None" else "${c.texturesLoaded} / ${c.texturesTotal}")
            add("Size (W × H × D)" to dims(s.size, c.upZ))
            add("GPU chunks" to s.chunks.toString())
            if (c.loadMillis > 0) add("Load time" to String.format(Locale.US, "%.1f s", c.loadMillis / 1000.0))
            add("Up axis" to if (c.upZ) "Z" else "Y")
        }
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(k, color = p.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(v, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        }
        VSpace(14.dp)
        val issues = c.report.count { it.level >= 1 }
        GlassButton(
            "Load report", Icons.AutoMirrored.Outlined.Assignment, p, Modifier.fillMaxWidth(),
            subtitle = if (issues > 0) "$issues warning${if (issues > 1) "s" else ""} — tap to see what happened" else "How the file was read",
            onClick = onReport,
        )
        VSpace(12.dp)
        Text(
            "Drag to orbit · pinch to zoom · two fingers to pan · double-tap to recentre",
            color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun dims(d: DoubleArray, upZ: Boolean): String {
    fun f(v: Double) = when {
        v >= 1000 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }
    if (d.all { it == 0.0 }) return "—"
    return if (upZ) "${f(d[0])} × ${f(d[2])} × ${f(d[1])}" else "${f(d[0])} × ${f(d[1])} × ${f(d[2])}"
}

@Composable
fun ReportPanel(c: ViewerController, p: Palette, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val lines = c.report
    val errors = lines.count { it.level == 2 }
    val warns = lines.count { it.level == 1 }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pressable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp)
                .glass(p, RoundedCornerShape(26.dp), strong = true).pressable { }.padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Load report", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(
                        when {
                            errors > 0 -> "$errors problem${if (errors > 1) "s" else ""}, $warns warning${if (warns != 1) "s" else ""}"
                            warns > 0 -> "$warns warning${if (warns != 1) "s" else ""}"
                            else -> "No problems found"
                        },
                        color = if (errors > 0) p.danger else if (warns > 0) Color(0xFFF5B342) else p.muted, fontSize = 13.sp,
                    )
                }
                Box(Modifier.size(36.dp).clip(CircleShape).pressable(onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, "Close", tint = p.muted, modifier = Modifier.size(20.dp))
                }
            }
            VSpace(12.dp)
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lines.size) { i ->
                    val l = lines[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(
                                when (l.level) {
                                    2 -> p.danger.copy(alpha = 0.14f)
                                    1 -> Color(0x22F5B342)
                                    else -> p.glass
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            when (l.level) {
                                2 -> Icons.Outlined.ErrorOutline
                                1 -> Icons.Outlined.WarningAmber
                                else -> Icons.Outlined.Info
                            },
                            null,
                            tint = when (l.level) { 2 -> p.danger; 1 -> Color(0xFFF5B342); else -> p.muted },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(l.text, color = p.text, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
            VSpace(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Copy", Icons.Outlined.ContentCopy, p, Modifier.weight(1f)) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(c.reportText()))
                    android.widget.Toast.makeText(ctx, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                GradientButton("Share", Icons.Outlined.Share, p, Modifier.weight(1f)) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Vertexa Pro load report — ${c.source.name}")
                        .putExtra(android.content.Intent.EXTRA_TEXT, c.reportText())
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share load report"))
                }
            }
        }
    }
}


@Composable
private fun RailLabel(text: String, p: Palette) {
    Text(text, color = p.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun RailDivider(p: Palette) {
    Box(Modifier.padding(vertical = 2.dp).size(width = 26.dp, height = 1.dp).background(p.border))
}
