package com.lyvt.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.ui.LyvtRoot
import com.lyvt.files.ui.LyvtTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    /** Route requested by a widget or shortcut; consumed by the navigation host. */
    val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            LyvtTheme(settings) {
                // Surface provides LocalContentColor = onBackground, so text is readable in both themes.
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Box {
                        LyvtRoot(settings, pendingRoute)
                        // Plays once per launch (survives rotation), not when returning from background.
                        var intro by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(savedInstanceState == null) }
                        if (intro) com.lyvt.files.ui.SplashIntro(settings.reduceMotion) { intro = false }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object { const val EXTRA_ROUTE = "astrum.route" }
}
