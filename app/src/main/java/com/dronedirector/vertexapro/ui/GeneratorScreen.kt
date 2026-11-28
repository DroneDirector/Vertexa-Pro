package com.dronedirector.vertexapro.ui

import android.content.Context
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.data.formatBytes
import com.dronedirector.vertexapro.data.formatCount
import com.dronedirector.vertexapro.generator.AircraftBuilder
import com.dronedirector.vertexapro.generator.GenOptions
import com.dronedirector.vertexapro.generator.Rgb
import com.dronedirector.vertexapro.generator.Specs
import com.dronedirector.vertexapro.generator.MeshyJob
import com.dronedirector.vertexapro.generator.GeneratorHandoff
import com.dronedirector.vertexapro.viewer.AircraftProfiles
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val tailColors = listOf(
    "Default" to null,
    "Blue" to Rgb(0.08, 0.22, 0.55), "Red" to Rgb(0.72, 0.08, 0.10), "Green" to Rgb(0.05, 0.45, 0.25),
    "Orange" to Rgb(0.95, 0.45, 0.05), "Black" to Rgb(0.08, 0.08, 0.09), "White" to Rgb(0.93, 0.94, 0.95),
)

fun generatedDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Generated").apply { mkdirs() }

/** Generator: "make me an Airbus A320" → a detailed, real-size model with every part separate. */
@Composable
fun GeneratorScreen(onBack: () -> Unit, onOpen: (File) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var detail by remember { mutableIntStateOf(1) }
    var gearDown by remember { mutableStateOf(true) }
    var tailIdx by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val gprefs = remember { ctx.getSharedPreferences("generator", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(gprefs.getString("meshy_key", "") ?: "") }
    var useAi by remember { mutableStateOf(gprefs.getBoolean("use_ai", true)) }
    var showKey by remember { mutableStateOf(apiKey.isBlank()) }
    val ai by MeshyJob.state.collectAsState()
    LaunchedEffect(ai) {
        val st = ai
        if (st is MeshyJob.State.Done) {
            GeneratorHandoff.set(android.net.Uri.fromFile(st.file), st.profile)
            MeshyJob.state.value = MeshyJob.State.Idle
            onOpen(st.file)
        } else if (st is MeshyJob.State.Failed) {
            message = st.message
            MeshyJob.state.value = MeshyJob.State.Idle
        }
    }
    var files by remember { mutableStateOf(generatedDir(ctx).listFiles()?.filter { it.extension == "obj" || it.extension == "glb" }?.sortedByDescending { it.lastModified() } ?: emptyList()) }

    fun generateAi() {
        val text = prompt.ifBlank { "Airbus A320" }
        if (apiKey.isBlank()) { showKey = true; message = "Add your Meshy API key first (meshy.ai → API)."; return }
        val profile = AircraftProfiles.indexFor(text)
        val prof = AircraftProfiles.all[profile]
        val typeName = if (prof.generic) "" else prof.name
        val lower = text.lowercase()
        val gear = gearDown && !("gear up" in lower || "retracted" in lower || "in flight" in lower)
        val tailName = tailColors.drop(1).firstOrNull { (n, _) -> n.lowercase() in lower }?.first ?: tailColors[tailIdx].first.takeIf { tailIdx > 0 }
        val aiPrompt = "$text. Photorealistic, highly detailed commercial airliner $typeName with accurate real-world proportions and shape, " +
            "correct nose and cockpit, swept wings with flaps, slats and ailerons, detailed engines and pylons, " +
            (if (gear) "landing gear extended, " else "landing gear retracted, ") +
            "passenger windows and doors, single aircraft only, no base, no ground, no stand, no people"
        val texture = "Realistic airline livery: clean white fuselage, " + (tailName?.let { "${it.lowercase()} tail fin, " } ?: "") +
            "light grey wings and stabilisers, dark tinted cockpit and cabin windows, outlined doors, metallic engine inlets, fine panel lines"
        message = null
        MeshyJob.start(generatedDir(ctx), apiKey.trim(), aiPrompt, texture, if (typeName.isNotEmpty()) typeName else text.take(30), profile)
    }

    fun generate() {
        if (useAi) { generateAi(); return }
        val text = prompt.ifBlank { "Airbus A320" }
        val spec = Specs.parse(text)
        if (spec == null) {
            message = "I can build: " + Specs.all.joinToString { it.name } + ". Try e.g. “Make me an Airbus A320neo”."
            return
        }
        val lower = text.lowercase()
        val gear = gearDown && !("gear up" in lower || "retracted" in lower || "in flight" in lower)
        val tailFromText = tailColors.drop(1).firstOrNull { (n, _) -> n.lowercase() in lower }?.second
        val opts = GenOptions(detail = if ("ultra" in lower) 2 else detail, gearDown = gear, tail = tailFromText ?: tailColors[tailIdx].second)
        busy = true
        message = null
        progress = 0f
        scope.launch {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(generatedDir(ctx), spec.name.replace(' ', '_') + "_" + stamp + ".obj")
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    AircraftBuilder(spec, opts).build(file) { f, s -> scope.launch { progress = f; stage = s } }
                }
            }
            busy = false
            result.onSuccess { tris ->
                files = generatedDir(ctx).listFiles()?.filter { it.extension == "obj" || it.extension == "glb" }?.sortedByDescending { it.lastModified() } ?: emptyList()
                message = "${spec.name}: ${formatCount(tris)} triangles, ${formatBytes(file.length())}"
                onOpen(file)
            }.onFailure { message = "Couldn't build it: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(p)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, p, size = 42.dp, contentDescription = "Back") { onBack() }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Generator", color = p.text, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("Real-size aircraft, every part separate", color = p.muted, fontSize = 12.sp)
                }
            }
            VSpace(18.dp)
            Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).glass(p, RoundedCornerShape(26.dp), strong = true).padding(18.dp)) {
                Text("What should I build?", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                VSpace(8.dp)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(p.glass)
                        .border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(14.dp),
                ) {
                    if (prompt.isEmpty()) Text("e.g. Make me an Airbus A320neo with a red tail", color = p.muted, fontSize = 15.sp)
                    BasicTextField(prompt, { prompt = it }, textStyle = TextStyle(color = p.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                }
                VSpace(10.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Specs.all.forEach { s -> PillChip(s.name, prompt.equals(s.name, true), p) { prompt = s.name } }
                    if (useAi) listOf("Boeing 777-300ER", "Airbus A350-900", "Boeing 787-9").forEach { n -> PillChip(n, prompt == n, p) { prompt = n } }
                }
                VSpace(14.dp)
                Text("Made by", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VSpace(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("AI — realistic", useAi, p) { useAi = true; gprefs.edit().putBoolean("use_ai", true).apply() }
                    PillChip("Offline — basic", !useAi, p) { useAi = false; gprefs.edit().putBoolean("use_ai", false).apply() }
                    if (useAi) PillChip(if (showKey) "Hide key" else "API key", false, p) { showKey = !showKey }
                }
                if (useAi && showKey) {
                    VSpace(8.dp)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.glass)
                            .border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(12.dp),
                    ) {
                        if (apiKey.isEmpty()) Text("Meshy API key (msy_…)", color = p.muted, fontSize = 14.sp)
                        BasicTextField(apiKey, { apiKey = it.trim(); gprefs.edit().putString("meshy_key", apiKey).apply() }, singleLine = true,
                            textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Text("From meshy.ai → Settings → API. Each aircraft uses Meshy credits; it's stored only on this phone.", color = p.muted, fontSize = 11.sp)
                }
                if (useAi) {
                    Text("The AI builds a realistic textured aircraft; the app then makes it real size and cuts flaps and ailerons as separate parts. Takes a few minutes.",
                        color = p.muted, fontSize = 11.sp)
                }
                VSpace(10.dp)
                if (!useAi) {
                Text("Detail", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VSpace(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("High", detail == 1, p) { detail = 1 }
                    PillChip("Ultra", detail == 2, p) { detail = 2 }
                }
                }
                VSpace(10.dp)
                Text("Landing gear", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VSpace(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("Down", gearDown, p) { gearDown = true }
                    PillChip("Up (in flight)", !gearDown, p) { gearDown = false }
                }
                VSpace(10.dp)
                Text("Tail colour", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                VSpace(6.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tailColors.forEachIndexed { i, (n, _) -> PillChip(n, tailIdx == i, p) { tailIdx = i } }
                }
                VSpace(18.dp)
                val run = ai as? MeshyJob.State.Running
                if (run != null) {
                    Text("AI: ${run.stage} · ${(run.progress * 100).toInt()}%", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    VSpace(8.dp)
                    LinearProgressIndicator({ run.progress }, Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = p.accent, trackColor = p.border)
                    VSpace(8.dp)
                    PillChip("Cancel", false, p) { MeshyJob.cancel() }
                } else if (busy) {
                    Text("Building: $stage · ${(progress * 100).toInt()}%", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    VSpace(8.dp)
                    LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = p.accent, trackColor = p.border)
                } else {
                    GradientButton("Generate", Icons.Outlined.AutoAwesome, p, Modifier.fillMaxWidth()) { generate() }
                }
                message?.let { VSpace(8.dp); Text(it, color = p.muted, fontSize = 12.sp) }
                VSpace(10.dp)
                Text(
                    "Built from the real dimensions at real size in metres. Flaps, slats, ailerons, spoilers, elevators, rudder, " +
                        "engines, fans, pylons, landing gear, wheels, gear doors, passenger and cargo doors and every window are their own parts — " +
                        "tap one in the viewer to highlight it.",
                    color = p.muted, fontSize = 11.sp,
                )
            }
            if (files.isNotEmpty()) {
                VSpace(18.dp)
                Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                    Text("Generated", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    VSpace(8.dp)
                    files.take(30).forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(p, RoundedCornerShape(18.dp)).pressable { onOpen(f) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(p.accentBrush), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Flight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.nameWithoutExtension.replace('_', ' '), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(formatBytes(f.length()), color = p.muted, fontSize = 11.sp)
                            }
                            Box(Modifier.size(36.dp).clip(CircleShape).pressable {
                                f.delete()
                                files = files - f
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = p.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            VSpace(24.dp)
        }
    }
}
