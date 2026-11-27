package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.BrowseItem
import com.dronedirector.vertexapro.data.SortKey
import com.dronedirector.vertexapro.data.formatBytes
import com.dronedirector.vertexapro.data.sortItems
import java.text.DateFormat
import java.util.Date

@Composable
fun BrowserScreen(
    title: String,
    items: List<BrowseItem>?,          // null while scanning
    sortKey: SortKey,
    sortAsc: Boolean,
    grid: Boolean,
    onSort: (SortKey, Boolean) -> Unit,
    onGrid: (Boolean) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onRefresh: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }

    val types = remember(items) { items.orEmpty().map { it.ext }.distinct().sorted() }
    val shown = remember(items, sortKey, sortAsc, query, typeFilter) {
        val list = items.orEmpty().filter {
            (query.isBlank() || it.name.contains(query, true) || it.relPath.contains(query, true)) &&
                (typeFilter == null || it.ext == typeFilter)
        }
        sortItems(list, sortKey, sortAsc)
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(p)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, p, size = 42.dp, contentDescription = "Back") { onBack() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (items == null) "Looking for models…" else "${shown.size} of ${items.size} models",
                        color = p.muted, fontSize = 12.sp,
                    )
                }
                if (onRefresh != null) {
                    RoundIconButton(Icons.Outlined.Refresh, p, size = 42.dp, contentDescription = "Rescan") { onRefresh() }
                    Spacer(Modifier.width(8.dp))
                }
                RoundIconButton(
                    if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, p, size = 42.dp,
                    contentDescription = if (grid) "List view" else "Grid view",
                ) { onGrid(!grid) }
            }
            // search
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(p, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = p.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search models", color = p.muted, fontSize = 15.sp)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // sort + type filter
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sort", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                SortKey.entries.forEach { k ->
                    val active = k == sortKey
                    PillChip(
                        k.label, active, p,
                        trailing = if (active) (if (sortAsc) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward) else null,
                    ) {
                        if (active) onSort(k, !sortAsc)
                        else onSort(k, k == SortKey.NAME || k == SortKey.TYPE)
                    }
                }
                if (types.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("Type", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    PillChip("All", typeFilter == null, p) { typeFilter = null }
                    types.forEach { t -> PillChip(t.uppercase(), typeFilter == t, p) { typeFilter = if (typeFilter == t) null else t } }
                }
            }

            if (items == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.accent)
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (items.isEmpty()) "No supported 3D models here." else "Nothing matches your search.", color = p.muted, fontSize = 15.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.uri.toString() }) { item ->
                        if (grid) GridCard(item, p) { onOpen(item) } else ListRow(item, p) { onOpen(item) }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

private fun dateText(ms: Long): String =
    if (ms <= 0) "—" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun BrowseItem.job() = thumbJobOf(uri, name, size, extras, tree, if (tree != null) relPath else null)

@Composable
private fun GridCard(item: BrowseItem, p: Palette, onClick: () -> Unit) {
    Column(Modifier.glass(p, RoundedCornerShape(20.dp)).pressable(onClick).padding(8.dp)) {
        ModelThumb(item.job(), Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(item.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatBytes(item.size)} · ${dateText(item.modified)}", color = p.muted, fontSize = 11.sp, maxLines = 1)
            val folder = item.relPath.substringBeforeLast('/', "")
            if (folder.isNotEmpty()) Text(folder, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(item: BrowseItem, p: Palette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(p, RoundedCornerShape(18.dp)).pressable(onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelThumb(item.job(), Modifier.size(64.dp), corner = 12.dp, showBadge = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.ext.uppercase()} · ${formatBytes(item.size)} · ${dateText(item.modified)}",
                color = p.muted, fontSize = 12.sp, maxLines = 1,
            )
            val folder = item.relPath.substringBeforeLast('/', "")
            if (folder.isNotEmpty()) Text(folder, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PillChip(
    text: String,
    active: Boolean,
    p: Palette,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .then(if (active) Modifier.background(p.accentBrush) else Modifier.background(p.glass).border(1.dp, p.border, shape))
            .pressable(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (active) Color.White else p.text, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
