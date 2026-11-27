package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.viewer.ViewerController
import java.util.Locale

/** Play / pause, scrub, clip and speed controls for animated models. */
@Composable
fun AnimationBar(c: ViewerController, p: Palette) {
    if (c.clips.isEmpty()) return
    val dur = c.clips.getOrNull(c.clip)?.seconds ?: 0.0
    val t = if (dur > 0) c.animTime % dur else 0.0
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).widthIn(max = 640.dp)
            .glass(p, RoundedCornerShape(22.dp), strong = true).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(p.accentBrush).pressable { c.playAnimation(!c.playing) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (c.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (c.playing) "Pause" else "Play",
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = if (dur > 0) (t / dur).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { f -> if (c.playing) c.playAnimation(false); c.seek(f * dur) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent2, inactiveTrackColor = p.border),
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format(Locale.US, "%.1f / %.1f s", t, dur), color = p.muted, fontSize = 12.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (c.clips.size > 1) {
                c.clips.forEachIndexed { i, clip -> PillChip(clip.name.take(24), c.clip == i, p) { c.selectClip(i) } }
                Spacer(Modifier.width(6.dp))
            }
            Text("Speed", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            listOf(0.25f, 0.5f, 1f, 2f).forEach { speed ->
                PillChip(if (speed < 1f) "${speed}×" else "${speed.toInt()}×", c.animSpeed == speed, p) { c.updateAnimSpeed(speed) }
            }
        }
    }
}
