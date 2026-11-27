package com.dronedirector.vertexapro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedirector.vertexapro.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Animated launch screen. The splash always stays dark, whatever the app theme. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.6f) }
    val title = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }
    val stars = remember {
        val r = Random(7)
        List(90) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    LaunchedEffect(Unit) {
        launch { logoScale.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        launch { reveal.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
        launch { delay(450); title.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch { bar.animateTo(1f, tween(2100, easing = LinearEasing)) }
        delay(2350)
        onFinished()
    }
    val colors = listOf(Cyan, Violet, Pink)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF141A2E), Color(0xFF05060A)), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        // drifting star field converging to the centre
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            stars.forEach { (a, d, s) ->
                val ang = a * 6.283f + reveal.value * 0.6f
                val dist = (0.25f + d) * size.maxDimension * (1.15f - 0.35f * reveal.value)
                val p = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist)
                val tail = Offset(c.x + cos(ang) * dist * 1.04f, c.y + sin(ang) * dist * 1.04f)
                drawLine(Color.White.copy(alpha = 0.10f + 0.35f * s), p, tail, strokeWidth = 1.5f + 2f * s, cap = StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WireLogo(
                size = 190.dp, colors = colors, speed = 9000, reveal = reveal.value,
                modifier = Modifier.scale(logoScale.value),
            )
            Box(Modifier.height(28.dp))
            Row(Modifier.alpha(title.value)) {
                Text(
                    "VERTEXA",
                    style = TextStyle(
                        color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (4 + 8 * (1 - title.value)).sp,
                    ),
                )
                Text(
                    " PRO",
                    style = TextStyle(
                        brush = Brush.linearGradient(colors), fontSize = 38.sp, fontWeight = FontWeight.Light,
                        letterSpacing = (4 + 8 * (1 - title.value)).sp,
                    ),
                )
            }
            Text(
                "3D MODEL VIEWER",
                color = Color(0xFF8A91A5),
                fontSize = 13.sp,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(title.value).padding(top = 8.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth(0.42f).height(3.dp)) {
                drawLine(Color(0x22FFFFFF), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                drawLine(
                    Brush.horizontalGradient(colors), Offset(0f, size.height / 2),
                    Offset(size.width * bar.value, size.height / 2), size.height, StrokeCap.Round,
                )
            }
            Text(
                "v${BuildConfig.VERSION_NAME}",
                color = Color(0x66FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
