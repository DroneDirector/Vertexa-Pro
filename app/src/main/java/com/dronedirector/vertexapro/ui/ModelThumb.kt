package com.dronedirector.vertexapro.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.viewer.ThumbJob
import com.dronedirector.vertexapro.viewer.ThumbnailGenerator
import com.dronedirector.vertexapro.viewer.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the model's thumbnail. If there is none yet it asks the background renderer for one
 * (models over 250 MB get theirs the first time they are opened) and shows the format badge meanwhile.
 */
@Composable
fun ModelThumb(
    job: ThumbJob,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    showBadge: Boolean = true,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val version by ThumbnailStore.version.collectAsState()
    var bmp by remember(job.uri) { mutableStateOf<Bitmap?>(ThumbnailStore.cached(job.uri)) }
    var pending by remember(job.uri) { mutableStateOf(false) }
    LaunchedEffect(job.uri, version) {
        val (b, failed) = withContext(Dispatchers.IO) {
            ThumbnailStore.load(ctx, job.uri) to ThumbnailStore.failed(ctx, job.uri)
        }
        bmp = b
        if (b == null) {
            val canAuto = job.size in 0..ThumbnailStore.AUTO_LIMIT && !failed
            pending = canAuto
            if (canAuto) ThumbnailGenerator.request(ctx, job)
        } else pending = false
    }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.clip(shape).background(Brush.verticalGradient(listOf(DarkPalette.viewTop, DarkPalette.viewBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(bmp, label = "thumb") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (pending) {
                        val t = rememberInfiniteTransition(label = "shimmer")
                        val a by t.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
                        WireLogo(40.dp, listOf(p.accent, p.accent2, p.accent3), Modifier.alpha(a), glow = false, speed = 5000)
                    } else {
                        WireLogo(40.dp, listOf(Color(0x8822D3EE), Color(0x888B5CF6), Color(0x88EC4899)), glow = false, speed = 30000)
                    }
                }
            }
        }
        if (showBadge) {
            Text(
                Formats.ext(job.name).uppercase(),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(p.accentBrush)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

fun thumbJobOf(uri: Uri, name: String, size: Long, extras: List<Uri> = emptyList(), tree: Uri? = null, relPath: String? = null) =
    ThumbJob(uri, name, size, extras, tree, relPath)
