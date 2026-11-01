package com.lyvt.files.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lyvt.files.R

/** Fingerprint/face with the device PIN/pattern as fallback, so the user can never be locked out of their own app. */
const val APP_LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

fun appLockAvailable(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(APP_LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
fun fingerprintEnrolled(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

fun promptAppLock(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Use your fingerprint")
        .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS).build())
}

/** Full-screen cover shown while the app is locked; asks for the fingerprint immediately. */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as? FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    fun ask() { activity?.let { promptAppLock(it, "Unlock Astrum Files", onUnlocked) { m -> error = m } } }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.astrum_logo), null, Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(20.dp))
        Text("Astrum Files is locked", style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::ask, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock with fingerprint") }
    }
}
