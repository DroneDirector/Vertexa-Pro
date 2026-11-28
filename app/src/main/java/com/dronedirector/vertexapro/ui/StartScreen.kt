package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** After the splash: choose the Studio (open, cut, edit) or the Generator (build aircraft on command). */
@Composable
fun StartScreen(onStudio: () -> Unit, onGenerator: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(p)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WireLogo(64.dp, listOf(p.accent, p.accent2, p.accent3), glow = true)
            VSpace(14.dp)
            Row {
                Text("Vertexa", color = p.text, fontWeight = FontWeight.Black, fontSize = 30.sp)
                Text(" Pro", style = TextStyle(brush = p.accentBrush, fontSize = 30.sp, fontWeight = FontWeight.Light))
            }
            Text("What would you like to do?", color = p.muted, fontSize = 15.sp)
            VSpace(28.dp)
            StartCard(Icons.Outlined.ContentCut, "Studio", "Open 3D models, cut and splice parts, flaps, doors, export", p, onStudio)
            VSpace(14.dp)
            StartCard(Icons.Outlined.AutoAwesome, "Generator", "Build a detailed aircraft on command, at real size, every part separate", p, onGenerator)
        }
    }
}

@Composable
private fun StartCard(icon: ImageVector, title: String, sub: String, p: Palette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 520.dp).glass(p, RoundedCornerShape(28.dp), strong = true).pressable(onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(p.accentBrush), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(sub, color = p.muted, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = p.muted)
    }
}
