package com.lyvt.files.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.update.UpdateInfo

@Composable
fun UpdateDialog(u: UpdateInfo, onInstall: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) = AlertDialog(
    onDismissRequest = onLater,
    title = { Text("Update available: ${u.version}") },
    text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        Text("A new version of Astrum Files is ready${if (u.size > 0) " (${Formatters.size(u.size)})" else ""}. Your files and settings are kept.", style = MaterialTheme.typography.bodyMedium)
        if (u.notes.isNotBlank()) Text("\n" + u.notes.take(1500), style = MaterialTheme.typography.bodySmall)
    } },
    confirmButton = { TextButton(onClick = onInstall) { Text("Update now") } },
    dismissButton = { Column { TextButton(onClick = onLater) { Text("Later") }; TextButton(onClick = onSkip) { Text("Skip this version") } } },
)
