package com.lyvt.files.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyvt.files.R
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private val Purple = Color(0xFF7548F8)
private val Gold = Color(0xFFE8B95A)

/**
 * Brand intro that continues the system splash (same #101018 background and centred logo):
 * the logo settles in, an orbit sweeps around it (the "Astrum" star in orbit), a sparkle twinkles,
 * the wordmark fades up, then everything fades into the app. About 1.4 s; 250 ms when motion is reduced.
 */
@Composable
fun SplashIntro(reduceMotion: Boolean, onFinished: () -> Unit) {
    val logoScale = remember { Animatable(0.82f) }
    val logoAlpha = remember { Animatable(0f) }
    val orbit = remember { Animatable(0f) }
    val sparkle = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            logoAlpha.snapTo(1f); logoScale.snapTo(1f); title.snapTo(1f)
            delay(150); exit.animateTo(0f, tween(100)); onFinished(); return@LaunchedEffect
        }
        coroutineScope {
            async { logoAlpha.animateTo(1f, tween(260)) }
            async { logoScale.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            async { delay(140); orbit.animateTo(1f, tween(760, easing = LinearOutSlowInEasing)) }
            async { delay(620); sparkle.animateTo(1f, tween(220)); sparkle.animateTo(0f, tween(320)) }
            async { delay(520); title.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        }
        delay(180)
        exit.animateTo(0f, tween(260))
        onFinished()
    }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }.background(Color(0xFF101018)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                // Soft halo behind the logo
                Canvas(Modifier.size(220.dp)) {
                    drawCircle(Brush.radialGradient(listOf(Purple.copy(alpha = 0.28f * logoAlpha.value), Color.Transparent)), radius = size.minDimension / 2)
                }
                Image(painterResource(R.drawable.astrum_logo), contentDescription = "Astrum Files",
                    modifier = Modifier.size(128.dp).graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value }.clip(RoundedCornerShape(30.dp)))
                // Orbit: a tilted ellipse drawn progressively, gold head fading into purple
                Canvas(Modifier.size(220.dp)) {
                    val w = size.width * 0.92f; val h = size.height * 0.36f
                    val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
                    rotate(-18f) {
                        val sweep = 360f * orbit.value
                        if (sweep > 1f) drawArc(
                            brush = Brush.sweepGradient(listOf(Purple.copy(alpha = 0f), Purple, Gold), center = center),
                            startAngle = 200f, sweepAngle = sweep, useCenter = false, topLeft = tl, size = Size(w, h),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round), alpha = (1.2f - orbit.value).coerceIn(0.35f, 1f),
                        )
                    }
                    // Four-point sparkle at the top-right
                    val s = sparkle.value
                    if (s > 0f) {
                        val c = Offset(size.width * 0.80f, size.height * 0.20f); val r = 14.dp.toPx() * s; val k = r * 0.22f
                        val p = Path().apply {
                            moveTo(c.x, c.y - r); quadraticTo(c.x + k, c.y - k, c.x + r, c.y); quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
                            quadraticTo(c.x - k, c.y + k, c.x - r, c.y); quadraticTo(c.x - k, c.y - k, c.x, c.y - r); close()
                        }
                        drawPath(p, Gold.copy(alpha = s))
                        drawCircle(Gold.copy(alpha = 0.25f * s), radius = r * 1.4f, center = c)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Astrum Files", style = TextStyle(color = Color(0xFFECEAF4), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 24f })
            Text("Your files, in orbit.", style = TextStyle(color = Color(0xFFA29DB8), fontSize = 14.sp),
                modifier = Modifier.graphicsLayer { alpha = title.value * 0.9f; translationY = (1f - title.value) * 30f })
        }
    }
}
