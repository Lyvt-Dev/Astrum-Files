package com.astrum.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

fun main() {
    AppState.load(); Organizer.load(); Transfer.load(); runCatching { Snapshots.record() }
    com.lyvt.files.core.scan.DuplicateFinder.cache = com.lyvt.files.core.scan.HashCache(java.io.File(Store.dir, "hash-cache.tsv"))
    // Optional start screen, e.g. -Dastrum.screen=FILES (used for screenshots / shortcuts).
    System.getProperty("astrum.screen")?.let { runCatching { AppState.screen = Screen.valueOf(it.uppercase()) } }
    if (System.getProperty("astrum.noupdate") == null) Updates.checkIfDue()
    val skipIntro = System.getProperty("astrum.nointro") != null
    application {
        val state = rememberWindowState(size = DpSize(1480.dp, 920.dp))
        Window(onCloseRequest = { Transfer.stop(); Transfer.nearby.stop(); Vault.lock(); AppState.save(); exitApplication() }, state = state, title = "Astrum Files", icon = painterResource("astrum.png")) {
            window.minimumSize = java.awt.Dimension(1100, 700)
            // Vault auto-lock after 5 minutes without activity or when the window is minimised for 1 minute.
            LaunchedEffect(Unit) { while (true) { delay(15_000)
                if (Vault.unlocked && (System.currentTimeMillis() - Vault.lastActivity > 5 * 60_000 || (state.isMinimized && System.currentTimeMillis() - Vault.lastActivity > 60_000))) Vault.lock() } }
            val dark = when (AppState.themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            AstrumTheme(dark, AppState.accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var intro by remember { mutableStateOf(!skipIntro) }
                    Box(Modifier.fillMaxSize()) { App(); if (!intro) UpdatePrompt(); if (intro) Intro(AppState.reduceMotion) { intro = false } }
                }
            }
        }
    }
}

@Composable
private fun App() {
    val scope = rememberCoroutineScope()
    val explorer = remember { ExplorerState(scope, AppState.home.absolutePath) }
    LaunchedEffect(AppState.screen) { if (AppState.screen == Screen.VAULT) Vault.lastActivity = System.currentTimeMillis() }
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    Row(Modifier.fillMaxSize()) {
        Sidebar()
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight()) {
          TopBar(dark)
          Box(Modifier.weight(1f).fillMaxWidth()) {
            val f = AppState.openFile
            if (f != null) ViewerHost(f) { AppState.openFile = null }
            else AnimatedContent(AppState.screen, transitionSpec = { fadeIn(tween(if (AppState.reduceMotion) 0 else 180)) togetherWith fadeOut(tween(if (AppState.reduceMotion) 0 else 120)) }) { s ->
                when (s) {
                    Screen.HOME -> HomeScreen(); Screen.FILES -> ExplorerScreen(explorer); Screen.CLEANER -> CleanerScreen(); Screen.ANALYTICS -> AnalyticsScreen()
                    Screen.VAULT -> VaultScreen(); Screen.TRANSFER -> TransferScreen(); Screen.ORGANIZER -> OrganizerScreen(); Screen.SETTINGS -> SettingsScreen()
                }
            }
          }
        }
    }
}

/** Same brand intro as the phone: logo settles, orbit sweeps, star sparkles, wordmark fades in. */
@Composable
private fun Intro(reduce: Boolean, done: () -> Unit) {
    val purple = Color(0xFF7548F8)
    val scale = remember { Animatable(0.82f) }; val alpha = remember { Animatable(0f) }; val orbit = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }; val title = remember { Animatable(0f) }; val exit = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduce) { alpha.snapTo(1f); scale.snapTo(1f); title.snapTo(1f); delay(200); exit.animateTo(0f, tween(120)); done(); return@LaunchedEffect }
        coroutineScope {
            async { alpha.animateTo(1f, tween(260)) }; async { scale.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            async { delay(140); orbit.animateTo(1f, tween(760)) }; async { delay(620); spark.animateTo(1f, tween(220)); spark.animateTo(0f, tween(320)) }
            async { delay(520); title.animateTo(1f, tween(380)) }
        }
        delay(180); exit.animateTo(0f, tween(260)); done()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = exit.value }.background(Color(0xFF101018)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(240.dp)) { drawCircle(Brush.radialGradient(listOf(purple.copy(alpha = 0.3f * alpha.value), Color.Transparent))) }
                Image(painterResource("astrum.png"), null, Modifier.size(136.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }.clip(RoundedCornerShape(32.dp)))
                Canvas(Modifier.size(240.dp)) {
                    val w = size.width * 0.92f; val h = size.height * 0.36f
                    rotate(-18f) { if (orbit.value > 0.01f) drawArc(Brush.sweepGradient(listOf(purple.copy(alpha = 0f), purple, Gold), center), 200f, 360f * orbit.value, false,
                        Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), alpha = (1.2f - orbit.value).coerceIn(0.35f, 1f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                    if (spark.value > 0f) { val c = Offset(size.width * 0.8f, size.height * 0.2f); val r = 15.dp.toPx() * spark.value
                        drawCircle(Gold.copy(alpha = spark.value), r * 0.35f, c); drawLine(Gold.copy(alpha = spark.value), c.copy(y = c.y - r), c.copy(y = c.y + r), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(Gold.copy(alpha = spark.value), c.copy(x = c.x - r), c.copy(x = c.x + r), 2.dp.toPx(), StrokeCap.Round) }
                }
            }
            Text("Astrum Files", style = TextStyle(color = Color(0xFFECEAF4), fontSize = 28.sp, fontWeight = FontWeight.Bold), modifier = Modifier.graphicsLayer { this.alpha = title.value; translationY = (1 - title.value) * 24 })
            Text("Your files, in orbit.", style = TextStyle(color = Color(0xFFA29DB8), fontSize = 14.sp), modifier = Modifier.graphicsLayer { this.alpha = title.value })
        }
    }
}
