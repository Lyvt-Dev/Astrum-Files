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
        "try", "catch", "finally", "throw", "override", "suspend", "data", "enum", "struct", "from", "as", "in", "is", "this", "self", "extends", "implements",
        "namespace", "using", "include", "define", "template", "typename", "auto", "public:", "private:", "async", "await", "export", "type", "readonly",
        "echo", "then", "fi", "do", "done", "esac", "elif", "lambda", "yield", "with", "pass", "char", "bool", "double", "float", "long", "string", "var")
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
    var preview by remember { mutableStateOf(false) }
    val ext = file.extension.lowercase()
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
            if (ext == "md" || ext == "markdown") TextButton(onClick = { preview = !preview }) { Text(if (preview) "Source" else "Preview") }
            if (ext in setOf("json", "xml") && original != null) TextButton(onClick = {
                runCatching { formatStructured(text, ext) }.onSuccess { text = it; if (!editing) scope.launch { snackbar.showSnackbar("Formatted — enable editing and save to keep it") } }
                    .onFailure { scope.launch { snackbar.showSnackbar("Invalid ${ext.uppercase()}: ${it.message}") } }
            }) { Text("Format") }
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
        if (preview) {
            Text(MarkdownRenderer.render(text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp))
            return@Column
        }
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

fun formatStructured(text: String, ext: String): String = if (ext == "json") {
    val t = text.trim()
    if (t.startsWith("[")) org.json.JSONArray(t).toString(2) else org.json.JSONObject(t).toString(2)
} else {
    val src = javax.xml.transform.stream.StreamSource(java.io.StringReader(text))
    val out = java.io.StringWriter()
    val tf = javax.xml.transform.TransformerFactory.newInstance().apply { runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) } }
    tf.newTransformer().apply {
        setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }.transform(src, javax.xml.transform.stream.StreamResult(out))
    out.toString()
}

/** Lightweight Markdown renderer: headings, bold, italic, inline code, code blocks, lists, quotes, links (text only). */
object MarkdownRenderer {
    fun render(md: String, accent: Color): AnnotatedString = buildAnnotatedString {
        var inCode = false
        md.lines().forEach { raw ->
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; return@forEach }
            if (inCode) { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))); append(raw); pop(); append("\n"); return@forEach }
            val h = Regex("^(#{1,6})\\s+(.*)").find(raw)
            when {
                h != null -> { val lvl = h.groupValues[1].length; pushStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = (26 - lvl * 2).sp, color = if (lvl <= 2) accent else Color.Unspecified)); inline(h.groupValues[2]); pop() }
                raw.trimStart().startsWith("> ") -> { pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.Gray)); append("▎ "); inline(raw.trimStart().removePrefix("> ")); pop() }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(raw) -> { append("  •  "); inline(raw.replaceFirst(Regex("^\\s*[-*+]\\s+"), "")) }
                Regex("^\\s*\\d+\\.\\s+").containsMatchIn(raw) -> { append("  "); inline(raw.trimStart()) }
                raw.trim() == "---" || raw.trim() == "***" -> append("────────────")
                else -> inline(raw)
            }
            append("\n")
        }
    }
    private val inlineRe = Regex("""(\*\*[^*]+\*\*)|(`[^`]+`)|(\*[^*]+\*|_[^_]+_)|(\[([^\]]+)\]\(([^)]+)\))""")
    private fun AnnotatedString.Builder.inline(s: String) {
        var last = 0
        inlineRe.findAll(s).forEach { m ->
            append(s.substring(last, m.range.first))
            when {
                m.groups[1] != null -> { pushStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); append(m.value.trim('*')); pop() }
                m.groups[2] != null -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))); append(m.value.trim('`')); pop() }
                m.groups[3] != null -> { pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)); append(m.value.trim('*', '_')); pop() }
                m.groups[4] != null -> { pushStyle(SpanStyle(color = Color(0xFF7548F8), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)); append(m.groupValues[5]); pop() }
            }
            last = m.range.last + 1
        }
        append(s.substring(last))
    }
}
