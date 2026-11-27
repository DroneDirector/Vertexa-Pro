package com.dronedirector.vertexapro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.update.UpdateManager

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    autoCheck: Boolean,
    updatePhase: UpdatePhase,
    onTheme: (ThemeMode) -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var showLicences by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(p)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, p, size = 42.dp, contentDescription = "Back") { onBack() }
                Spacer(Modifier.width(14.dp))
                Text("Settings", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                VSpace(10.dp)
                SectionTitle("Appearance", p)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeMode.entries.forEach { m ->
                        ThemeCard(m, m == theme, Modifier.weight(1f)) { onTheme(m) }
                    }
                }
                VSpace(28.dp)
                SectionTitle("Updates", p)
                Column(Modifier.fillMaxWidth().glass(p).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Vertexa Pro", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Version ${UpdateManager.currentVersion()}", color = p.muted, fontSize = 13.sp)
                        }
                        when (updatePhase) {
                            UpdatePhase.Checking -> CircularProgressIndicator(Modifier.size(26.dp), color = p.accent, strokeWidth = 3.dp)
                            UpdatePhase.UpToDate -> Icon(Icons.Outlined.CheckCircle, null, tint = p.accent)
                            else -> {}
                        }
                    }
                    VSpace(6.dp)
                    val status = when (updatePhase) {
                        UpdatePhase.Checking -> "Checking for updates…"
                        UpdatePhase.UpToDate -> "You're on the latest version."
                        is UpdatePhase.Failed -> updatePhase.message
                        is UpdatePhase.Available -> "Version ${updatePhase.release.version} is available."
                        else -> "Updates are installed right here in the app."
                    }
                    Text(status, color = p.muted, fontSize = 13.sp)
                    VSpace(14.dp)
                    GradientButton("Check for updates", Icons.Outlined.SystemUpdate, p, Modifier.fillMaxWidth(), onClick = onCheckUpdates)
                    VSpace(14.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Check automatically at start", color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoCheck, onCheckedChange = onAutoCheck,
                            colors = SwitchDefaults.colors(checkedTrackColor = p.accent2, checkedThumbColor = Color.White),
                        )
                    }
                }
                VSpace(28.dp)
                SectionTitle("About", p)
                Column(Modifier.fillMaxWidth().glass(p).padding(18.dp)) {
                    Text(
                        "A fast viewer for 3D models — open OBJ, FBX, glTF/GLB, STL, PLY, DAE, 3DS, 3MF, BLEND and more, " +
                            "with or without textures, as solid or wireframe. Files are streamed, so very large models open too. " +
                            "Vertexa Pro only opens files; it never changes or saves them.",
                        color = p.muted, fontSize = 13.sp,
                    )
                    VSpace(12.dp)
                    Text(
                        if (showLicences) "Hide open-source licences" else "Open-source licences",
                        color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).pressable { showLicences = !showLicences }
                            .padding(vertical = 6.dp),
                    )
                    if (showLicences) {
                        VSpace(6.dp)
                        Text(LICENCES, color = p.muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                VSpace(32.dp)
            }
        }
    }
}

@Composable
private fun ThemeCard(m: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tp = paletteFor(m)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(p.glass)
            .then(
                if (selected) Modifier.border(2.dp, Brush.linearGradient(listOf(p.accent, p.accent2, p.accent3)), shape)
                else Modifier.border(1.dp, p.border, shape),
            )
            .pressable(onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(tp.viewTop, tp.viewBottom))),
            contentAlignment = Alignment.Center,
        ) {
            WireLogo(54.dp, listOf(tp.wire, tp.accent2, tp.accent3), glow = false, speed = 20000)
        }
        VSpace(8.dp)
        Text(m.label, color = p.text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
    }
}

private const val LICENCES = """Open Asset Import Library (assimp) — BSD 3-Clause
Copyright (c) 2006-2024, assimp team. All rights reserved.
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met: (1) Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer. (2) Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution. (3) Neither the name of the assimp team, nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission of the assimp team.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

zlib — Copyright (C) 1995-2024 Jean-loup Gailly and Mark Adler. zlib licence.
pugixml, utf8cpp, unzip/minizip, Open3DGC, poly2tri, clipper, RapidJSON, stb_image and other libraries bundled with assimp are used under their respective permissive licences (MIT / BSD / zlib / public domain).

AndroidX, Jetpack Compose, Material icons and Kotlin coroutines — Apache License 2.0."""
