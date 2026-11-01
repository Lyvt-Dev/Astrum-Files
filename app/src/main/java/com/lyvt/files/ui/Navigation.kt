package com.lyvt.files.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lyvt.files.R
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.feature.analytics.AnalyticsScreen
import com.lyvt.files.feature.cleaner.CleanerScreen
import com.lyvt.files.feature.explorer.ExplorerScreen
import com.lyvt.files.feature.home.HomeScreen
import com.lyvt.files.feature.settings.HistoryScreen
import com.lyvt.files.feature.settings.SettingsScreen
import com.lyvt.files.feature.trash.TrashScreen
import com.lyvt.files.feature.vault.VaultScreen
import com.lyvt.files.feature.viewer.ArchiveScreen
import com.lyvt.files.feature.viewer.ImageViewerScreen
import com.lyvt.files.feature.viewer.MediaPlayerScreen
import com.lyvt.files.feature.viewer.PdfViewerScreen
import com.lyvt.files.feature.viewer.TextViewerScreen

object Routes {
    const val HOME = "home"; const val FILES = "files"; const val CLEANER = "cleaner"; const val ANALYTICS = "analytics"; const val VAULT = "vault"
    const val TRASH = "trash"; const val SETTINGS = "settings"; const val HISTORY = "history"
    const val TOOLS = "tools"; const val ORGANIZER = "organizer"; const val LIBRARY = "library"; const val NETWORK = "network"
    const val TRANSFER = "transfer"; const val TIMELINE = "timeline"
    fun inspect(path: String) = "inspect?path=${Uri.encode(path)}"
    fun files(path: String? = null, query: String? = null) = "files?path=${Uri.encode(path ?: "")}&q=${Uri.encode(query ?: "")}"
    fun image(path: String) = "image?path=${Uri.encode(path)}"
    fun text(path: String) = "text?path=${Uri.encode(path)}"
    fun media(path: String) = "media?path=${Uri.encode(path)}"
    fun pdf(path: String) = "pdf?path=${Uri.encode(path)}"
    fun archive(path: String) = "archive?path=${Uri.encode(path)}"
}

private data class Dest(val route: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val destinations = listOf(
    Dest(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Dest(Routes.FILES, R.string.nav_files, Icons.Outlined.Folder, Icons.Filled.Folder),
    Dest(Routes.CLEANER, R.string.nav_cleaner, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Dest(Routes.ANALYTICS, R.string.nav_analytics, Icons.Outlined.Analytics, Icons.Filled.Analytics),
    Dest(Routes.VAULT, R.string.nav_vault, Icons.Outlined.Lock, Icons.Filled.Lock),
)

/** Opens a file in the matching built-in viewer, or returns false to hand off to external apps. */
fun NavHostController.openFile(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    val route = when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif" -> Routes.image(path)
        "mp4", "mkv", "webm", "3gp", "mov", "m4v", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav" -> Routes.media(path)
        "pdf" -> Routes.pdf(path)
        "zip", "7z", "tar", "gz", "tgz", "bz2", "xz" -> Routes.archive(path)
        "txt", "md", "json", "xml", "csv", "html", "htm", "log", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "sh",
        "yml", "yaml", "ini", "conf", "properties", "gradle", "css", "sql", "toml" -> Routes.text(path)
        else -> return false
    }
    navigate(route); return true
}

@Composable
fun LyvtRoot(settings: AppSettings, pendingRoute: androidx.compose.runtime.MutableState<String?> = remember { mutableStateOf(null) }) {
    val ctx = LocalContext.current
    var hasAccess by remember { mutableStateOf(StoragePermission.granted(ctx)) }
    var limitedMode by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) hasAccess = StoragePermission.granted(ctx) }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (!hasAccess && !limitedMode) {
        PermissionScreen(onLimited = { limitedMode = true }, onGranted = { hasAccess = StoragePermission.granted(ctx) })
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?')
    val showBar = currentRoute in destinations.map { it.route }
    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }
    androidx.compose.runtime.LaunchedEffect(pendingRoute.value) {
        val r = pendingRoute.value ?: return@LaunchedEffect
        pendingRoute.value = null
        when (r) {
            "files", "cleaner", "analytics", "vault", "home" -> go(r)
            "downloads" -> nav.navigate(Routes.files(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath))
            else -> nav.navigate(r)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && showBar) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.safeDrawingPadding()) {
                Spacer(Modifier.weight(1f))
                destinations.forEach { d ->
                    val sel = currentRoute == d.route
                    NavigationRailItem(selected = sel, onClick = { go(d.route) }, icon = { Icon(if (sel) d.selectedIcon else d.icon, null) },
                        label = { Text(stringResource(d.label)) })
                }
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f)) {
                    NavHost(nav, startDestination = if (settings.startPage == "files") Routes.FILES else Routes.HOME,
                        enterTransition = { if (settings.reduceMotion) fadeIn(snap()) else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                        exitTransition = { fadeOut(tween(if (settings.reduceMotion) 0 else 160)) },
                        popEnterTransition = { fadeIn(tween(if (settings.reduceMotion) 0 else 220)) },
                        popExitTransition = { if (settings.reduceMotion) fadeOut(snap()) else fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } }) {
                        composable(Routes.HOME) { HomeScreen(nav) }
                        composable("${Routes.FILES}?path={path}&q={q}", arguments = listOf(
                            navArgument("path") { type = NavType.StringType; defaultValue = "" },
                            navArgument("q") { type = NavType.StringType; defaultValue = "" },
                        )) { ExplorerScreen(nav, wide) }
                        composable(Routes.CLEANER) { CleanerScreen(nav) }
                        composable(Routes.ANALYTICS) { AnalyticsScreen(nav) }
                        composable(Routes.VAULT) { VaultScreen(nav) }
                        composable(Routes.TRASH) { TrashScreen(nav) }
                        composable(Routes.SETTINGS) { SettingsScreen(nav) }
                        composable(Routes.HISTORY) { HistoryScreen(nav) }
                        composable(Routes.TOOLS) { com.lyvt.files.feature.tools.ToolsScreen(nav) }
                        composable(Routes.ORGANIZER) { com.lyvt.files.feature.organizer.OrganizerScreen(nav) }
                        composable(Routes.LIBRARY) { com.lyvt.files.feature.library.LibraryScreen(nav) }
                        composable(Routes.NETWORK) { com.lyvt.files.feature.network.NetworkScreen(nav) }
                        composable(Routes.TRANSFER) { com.lyvt.files.feature.transfer.TransferScreen(nav) }
                        composable(Routes.TIMELINE) { com.lyvt.files.feature.analytics.TimelineScreen(nav) }
                        composable("inspect?path={path}") { com.lyvt.files.feature.devtools.InspectorScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                        composable("image?path={path}") { ImageViewerScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                        composable("text?path={path}") { TextViewerScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                        composable("media?path={path}") { MediaPlayerScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                        composable("pdf?path={path}") { PdfViewerScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                        composable("archive?path={path}") { ArchiveScreen(nav, Uri.decode(it.arguments?.getString("path").orEmpty())) }
                    }
                }
                if (showBar) MiniPlayer { nav.navigate(Routes.media(it)) }
                if (!wide && showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    destinations.forEach { d ->
                        val sel = currentRoute == d.route
                        NavigationBarItem(selected = sel, onClick = { go(d.route) }, icon = { Icon(if (sel) d.selectedIcon else d.icon, null) },
                            label = { Text(stringResource(d.label), maxLines = 1) })
                    }
                }
            }
        }
    }
}

object StoragePermission {
    fun granted(ctx: android.content.Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PermissionScreen(onLimited: () -> Unit, onGranted: () -> Unit) {
    val ctx = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onLimited() }
    val lyvt = LocalLyvt.current
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lyvt.gradientStart, lyvt.gradientEnd))).safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(Icons.Outlined.Shield, MaterialTheme.colorScheme.primary, 88.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else legacy.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.perm_grant)) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33) media.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO))
            else media.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.perm_media)) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.perm_explain), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
