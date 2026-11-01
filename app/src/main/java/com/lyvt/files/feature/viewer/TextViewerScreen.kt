package com.lyvt.files.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lyvt.files.ui.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDITABLE = 2L * 1024 * 1024
private const val MAX_PREVIEW = 8L * 1024 * 1024

object SyntaxHighlighter {
    private val keywords = setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package",
        "public", "private", "protected", "static", "void", "int", "new", "def", "function", "const", "let", "true", "false", "null", "None", "True", "False",
        "try", "catch", "finally", "throw", "override", "suspend", "data", "enum", "struct", "from", "as", "in", "is", "this", "self", "extends", "implements")
    private val token = Regex("""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(//[^\n]*|#[^\n]*)|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)|(<\/?[A-Za-z][^>]*>)""")

    fun highlight(text: String, ext: String, dark: Boolean): AnnotatedString {
        if (text.length > 300_000 || ext in setOf("txt", "log", "csv")) return AnnotatedString(text)
        val str = if (dark) Color(0xFF8BD49C) else Color(0xFF1B7F3B)
        val com = if (dark) Color(0xFF7A869A) else Color(0xFF8A94A6)
        val num = if (dark) Color(0xFFFFB86C) else Color(0xFFB45309)
        val kw = if (dark) Color(0xFF82AAFF) else Color(0xFF2F6BFF)
        val tag = if (dark) Color(0xFFC792EA) else Color(0xFF7A3EC8)
        val hashComments = ext in setOf("py", "sh", "yml", "yaml", "toml", "conf", "ini", "properties")
        return buildAnnotatedString {
            append(text)
            token.findAll(text).forEach { m ->
                val style = when {
                    m.groups[1] != null -> SpanStyle(color = str)
                    m.groups[2] != null -> if (m.value.startsWith("#") && !hashComments) null else SpanStyle(color = com)
                    m.groups[3] != null -> SpanStyle(color = num)
                    m.groups[4] != null -> if (m.value in keywords) SpanStyle(color = kw) else null
                    m.groups[5] != null -> SpanStyle(color = tag)
                    else -> null
                }
                style?.let { addStyle(it, m.range.first, m.range.last + 1) }
            }
        }
    }
}

@Composable
fun TextViewerScreen(nav: NavHostController, path: String) {
    val file = File(path)
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    val dirty = original != null && text != original

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            when {
                !file.exists() -> error = "File no longer exists"
                file.length() > MAX_PREVIEW -> error = "File is too large for the built-in viewer (${file.length() / 1024 / 1024} MB). Open it with another app."
                else -> runCatching {
                    val bytes = file.readBytes()
                    if (bytes.take(8000).count { it == 0.toByte() } > 0) error("This looks like a binary file")
                    String(bytes, Charsets.UTF_8)
                }.onSuccess { original = it; text = it }.onFailure { error = it.message }
            }
        }
    }
    BackHandler(dirty) { confirmLeave = true }

    fun save() = scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                // Safe save: write to temp, keep a .bak of the original, then atomically rename.
                val tmp = File(file.parentFile, ".${file.name}.lyvt-tmp")
                tmp.writeText(text)
                val bak = File(file.parentFile, ".${file.name}.bak")
                bak.delete(); file.copyTo(bak)
                if (!tmp.renameTo(file)) { tmp.delete(); error("Could not replace file") }
                bak.delete()
            }
        }
        if (r.isSuccess) { original = text; snackbar.showSnackbar("Saved") } else snackbar.showSnackbar("Save failed: ${r.exceptionOrNull()?.message}")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) confirmLeave = true else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(file.name + if (dirty) " •" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${text.lines().size} lines" + if (editing) " · editing" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Filled.FindReplace, "Find and replace") }
            if (original != null && file.length() <= MAX_EDITABLE && file.canWrite()) IconButton(onClick = { editing = !editing }) { Icon(Icons.Filled.Edit, "Edit") }
            if (dirty) IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "Save") }
        }
        if (findOpen) Column(Modifier.padding(horizontal = 12.dp)) {
            val count = if (find.isEmpty()) 0 else Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(text).count()
            OutlinedTextField(find, { find = it }, label = { Text("Find ($count)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replace, { replace = it }, label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = count > 0, onClick = { text = text.replace(find, replace, ignoreCase = true) }) { Text("All") }
            }
        }
        error?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error); return@Column }
        if (original == null) { Text("Loading…", Modifier.padding(24.dp)); return@Column }
        val lines = text.count { it == '\n' } + 1
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text((1..lines).joinToString("\n"), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            val highlight = remember(find) { find }
            BasicTextField(
                value = text, onValueChange = { if (editing) text = it }, readOnly = !editing, textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = HighlightTransformation(file.extension.lowercase(), dark, highlight),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
    }
    SnackbarHost(snackbar)
    if (confirmLeave) ConfirmDialog("Discard changes?", "Your edits to ${file.name} have not been saved.", "Discard", destructive = true,
        onConfirm = { nav.popBackStack() }, onDismiss = { confirmLeave = false })
}

private class HighlightTransformation(val ext: String, val dark: Boolean, val find: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val base = SyntaxHighlighter.highlight(text.text, ext, dark)
        val out = if (find.isEmpty()) base else buildAnnotatedString {
            append(base)
            Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(text.text).forEach {
                addStyle(SpanStyle(background = Color(0x66FFD54F)), it.range.first, it.range.last + 1)
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}
