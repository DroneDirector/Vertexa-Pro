package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.RecentModel
import com.dronedirector.vertexapro.data.SavedFolder
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.dronedirector.vertexapro.data.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    recents: List<RecentModel>,
    folders: List<SavedFolder>,
    onBrowseFolder: (SavedFolder) -> Unit,
    onRemoveFolder: (SavedFolder) -> Unit,
    updateAvailable: String?,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRecent: (RecentModel) -> Unit,
    onRemoveRecent: (RecentModel) -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(p)
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WireLogo(30.dp, listOf(p.accent, p.accent2, p.accent3), glow = false)
                    Spacer(Modifier.width(10.dp))
                    Text("Vertexa", color = p.text, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(
                        " Pro",
                        style = TextStyle(brush = p.accentBrush, fontSize = 20.sp, fontWeight = FontWeight.Light),
                    )
                    Spacer(Modifier.weight(1f))
                    if (updateAvailable != null) {
                        RoundIconButton(Icons.Outlined.SystemUpdate, p, active = true, size = 42.dp, contentDescription = "Update") { onUpdate() }
                        Spacer(Modifier.width(10.dp))
                    }
                    RoundIconButton(Icons.Outlined.Settings, p, size = 42.dp, contentDescription = "Settings") { onSettings() }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VSpace(12.dp)
                    WireLogo(170.dp, listOf(p.accent, p.accent2, p.accent3), speed = 16000)
                    VSpace(18.dp)
                    Text(
                        "Open any 3D model",
                        color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    )
                    VSpace(8.dp)
                    Text(
                        "Textured, solid or wireframe — from tiny parts to multi-gigabyte scans.",
                        color = p.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                    VSpace(26.dp)
                    GradientButton(
                        "Open 3D model", Icons.Outlined.FileOpen, p,
                        subtitle = "Pick the model — add its .mtl & textures too",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenFiles,
                    )
                    VSpace(12.dp)
                    GlassButton(
                        "Open from folder", Icons.Outlined.FolderOpen, p,
                        subtitle = "Browse with thumbnails · best for textures",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenFolder,
                    )
                    VSpace(30.dp)
                }
            }
            if (folders.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp)) {
                        SectionTitle("Folders", p)
                    }
                }
                items(folders, key = { "f" + it.uri }) { f ->
                    Row(
                        Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp, vertical = 5.dp)
                            .glass(p, RoundedCornerShape(20.dp)).pressable { onBrowseFolder(f) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(p.accentBrush, alpha = 0.2f),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Folder, null, tint = p.accent) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Browse, sort and preview models", color = p.muted, fontSize = 12.sp)
                        }
                        Box(Modifier.size(36.dp).pressable { onRemoveFolder(f) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, "Remove", tint = p.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                item { VSpace(18.dp) }
            }
            if (recents.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp)) {
                        SectionTitle("Recent", p)
                    }
                }
                items(recents, key = { it.uri }) { r ->
                    RecentRow(r, p, { onOpenRecent(r) }, { onRemoveRecent(r) })
                }
                item { VSpace(18.dp) }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 24.dp).navigationBarsPadding(),
                ) {
                    SectionTitle("Supported formats", p)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { Formats.chips.forEach { Chip(it, p) } }
                    VSpace(32.dp)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(r: RecentModel, p: Palette, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .padding(horizontal = 24.dp, vertical = 5.dp)
            .glass(p, RoundedCornerShape(20.dp))
            .pressable(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelThumb(
            thumbJobOf(Uri.parse(r.uri), r.name, r.size, r.extras.map(Uri::parse), r.tree?.let(Uri::parse), r.relPath),
            Modifier.size(56.dp), corner = 14.dp, showBadge = false,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.time))
            val ext = Formats.ext(r.name).uppercase()
            val extra = if (r.tree != null) " · folder" else if (r.extras.isNotEmpty()) " · +${r.extras.size} files" else ""
            Text("$ext · ${formatBytes(r.size)} · $date$extra", color = p.muted, fontSize = 12.sp, maxLines = 1)
        }
        Box(Modifier.size(36.dp).pressable(onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Remove", tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

