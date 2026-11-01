package com.astrum.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyvt.files.core.archive.ArchiveFormat
import com.lyvt.files.core.archive.ArchiveManager
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.common.NaturalOrder
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO

object Viewers {
    val textExt = setOf("txt", "md", "json", "xml", "csv", "html", "htm", "log", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs",
        "sh", "bat", "ps1", "yml", "yaml", "ini", "conf", "properties", "gradle", "css", "sql", "toml", "go", "rs")
    fun supports(f: File): Boolean {
        val e = f.extension.lowercase()
        return e in textExt || e == "pdf" || e in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp") || ArchiveFormat.of(f.name) !in setOf(ArchiveFormat.UNKNOWN, ArchiveFormat.RAR)
    }
}

@Composable
fun ViewerHost(f: File, onClose: () -> Unit) {
    val e = f.extension.lowercase()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
            Column(Modifier.weight(1f)) { Text(f.name, style = MaterialTheme.typography.titleMedium); Text("${Formatters.size(f.length())} · ${f.parent}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { AppState.systemOpen(f) }) { Text("Open with default app") }
            TextButton(onClick = { AppState.reveal(f) }) { Text("Show in folder") }
        }
        Box(Modifier.weight(1f)) {
            when {
                e == "pdf" -> PdfViewer(f)
                e in Viewers.textExt -> TextEditor(f)
                ArchiveFormat.of(f.name) != ArchiveFormat.UNKNOWN -> ArchiveViewer(f)
                else -> ImageViewer(f)
            }
        }
    }
}

// ---------------- Image viewer ----------------
@Composable
private fun ImageViewer(start: File) {
    val images = remember(start) { start.parentFile?.listFiles()?.filter { FileCategory.of(it.extension) == FileCategory.IMAGE && it.extension.lowercase() != "heic" }
        ?.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } ?: listOf(start) }
    var index by remember { mutableIntStateOf(images.indexOfFirst { it.path == start.path }.coerceAtLeast(0)) }
    var scale by remember { mutableFloatStateOf(1f) }; var rotation by remember { mutableIntStateOf(0) }; var slideshow by remember { mutableStateOf(false) }
    var convert by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    val f = images.getOrElse(index) { start }
    val img by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { ImageIO.read(f)?.toComposeImageBitmap() }.getOrNull() } }
    LaunchedEffect(slideshow) { while (slideshow) { delay(3000); index = (index + 1) % images.size } }
    LaunchedEffect(index) { scale = 1f; rotation = 0 }
    Column(Modifier.fillMaxSize().onKeyEvent { if (it.type == KeyEventType.KeyDown) when (it.key) { Key.DirectionRight -> { index = (index + 1) % images.size; true }
        Key.DirectionLeft -> { index = (index - 1 + images.size) % images.size; true }; else -> false } else false }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { index = (index - 1 + images.size) % images.size }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
            Text("${index + 1} / ${images.size}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { index = (index + 1) % images.size }) { Icon(Icons.Filled.ChevronRight, "Next") }
            IconButton(onClick = { scale = (scale * 1.25f).coerceAtMost(8f) }) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
            IconButton(onClick = { scale = (scale / 1.25f).coerceAtLeast(0.2f) }) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
            IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
            IconButton(onClick = { slideshow = !slideshow }) { Icon(if (slideshow) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Slideshow") }
            TextButton(onClick = { convert = true }) { Text("Compress / convert / rotate copy") }
            img?.let { Text("${it.width} × ${it.height}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f)); msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0B11)).pointerInput(f) { detectTransformGestures { _, _, z, _ -> scale = (scale * z).coerceIn(0.2f, 8f) } },
            contentAlignment = Alignment.Center) {
            img?.let { Image(it, f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation.toFloat() }) }
                ?: Text("Cannot decode this image format", color = Color.White)
        }
    }
    if (convert) {
        var fmt by remember { mutableStateOf("jpg") }; var q by remember { mutableFloatStateOf(85f) }; var maxSide by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { convert = false }, title = { Text("Save a converted copy") }, text = {
            Column {
                Row { listOf("jpg", "png").forEach { x -> Pill(x.uppercase(), fmt == x) { fmt = x }; Spacer(Modifier.width(6.dp)) } }
                if (fmt == "jpg") { Text("Quality ${q.toInt()}"); Slider(q, { q = it }, valueRange = 10f..100f) }
                Text(if (maxSide < 1) "Keep resolution" else "Longest side ${maxSide.toInt()} px"); Slider(maxSide, { maxSide = it }, valueRange = 0f..6000f)
                if (rotation != 0) Text("Rotation of $rotation° will be applied", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { convert = false; scope.launch { msg = withContext(Dispatchers.IO) { ImageTools.convert(f, fmt, q.toInt(), maxSide.toInt(), rotation) } } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { convert = false }) { Text("Cancel") } })
    }
}

object ImageTools {
    fun convert(src: File, fmt: String, quality: Int, maxSide: Int, rotation: Int): String = runCatching {
        var img = ImageIO.read(src) ?: error("Cannot decode image")
        if (maxSide > 0 && maxOf(img.width, img.height) > maxSide) {
            val k = maxSide.toDouble() / maxOf(img.width, img.height)
            img = java.awt.image.BufferedImage((img.width * k).toInt(), (img.height * k).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB).also { o ->
                val g = o.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(img, 0, 0, o.width, o.height, null); g.dispose() }
        }
        if (rotation != 0) {
            val r = Math.toRadians(rotation.toDouble()); val sw = rotation % 180 != 0
            val w = if (sw) img.height else img.width; val h = if (sw) img.width else img.height
            img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB).also { o -> val g = o.createGraphics()
                g.translate(w / 2.0, h / 2.0); g.rotate(r); g.drawImage(img, -img.width / 2, -img.height / 2, null); g.dispose() }
        }
        if (fmt == "jpg" && img.colorModel.hasAlpha()) img = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB).also { o ->
            val g = o.createGraphics(); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, o.width, o.height); g.drawImage(img, 0, 0, null); g.dispose() }
        val target = FileOperations.uniqueTarget(src.parentFile, "${src.nameWithoutExtension}_converted.$fmt")
        if (fmt == "jpg") {
            val w = ImageIO.getImageWritersByFormatName("jpg").next(); val p = w.defaultWriteParam
            p.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; p.compressionQuality = quality / 100f
            ImageIO.createImageOutputStream(target).use { o -> w.output = o; w.write(null, javax.imageio.IIOImage(img, null, null), p) }; w.dispose()
        } else ImageIO.write(img, "png", target)
        "Saved ${target.name} · ${Formatters.size(src.length())} → ${Formatters.size(target.length())}"
    }.getOrElse { "Failed: ${it.message}" }
}

// ---------------- Text / code editor ----------------
object Highlighter {
    private val kw = setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "public", "private",
        "protected", "static", "void", "int", "new", "def", "function", "const", "let", "true", "false", "null", "None", "True", "False", "try", "catch", "finally", "throw",
        "override", "suspend", "data", "enum", "struct", "from", "as", "in", "is", "this", "self", "extends", "implements", "namespace", "using", "include", "async", "await",
        "export", "type", "echo", "then", "fi", "do", "done", "lambda", "yield", "with", "pass", "char", "bool", "double", "float", "long", "string", "string", "SELECT", "FROM", "WHERE")
    private val re = Regex("""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(//[^\n]*|#[^\n]*|--[^\n]*)|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)|(</?[A-Za-z][^>]*>)""")
    fun highlight(t: String, ext: String, dark: Boolean): AnnotatedString {
        if (t.length > 400_000 || ext in setOf("txt", "log", "csv")) return AnnotatedString(t)
        val hash = ext in setOf("py", "sh", "yml", "yaml", "toml", "conf", "ini", "properties", "ps1")
        return buildAnnotatedString { append(t); re.findAll(t).forEach { m ->
            val c = when { m.groups[1] != null -> if (dark) Color(0xFF8BD49C) else Color(0xFF1B7F3B)
                m.groups[2] != null -> if (m.value.startsWith("#") && !hash) null else Color(0xFF8A869E)
                m.groups[3] != null -> if (dark) Color(0xFFFFB86C) else Color(0xFFB45309)
                m.groups[4] != null -> if (m.value in kw) (if (dark) Color(0xFFB39DFF) else Color(0xFF7548F8)) else null
                else -> if (dark) Color(0xFFE8B95A) else Color(0xFF9A6B12) }
            c?.let { addStyle(SpanStyle(color = it), m.range.first, m.range.last + 1) } } }
    }
}

object Markdown {
    fun render(md: String, accent: Color): AnnotatedString = buildAnnotatedString {
        var code = false
        md.lines().forEach { raw ->
            if (raw.trimStart().startsWith("```")) { code = !code; return@forEach }
            if (code) { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))); append(raw); pop(); append("\n"); return@forEach }
            val h = Regex("^(#{1,6})\\s+(.*)").find(raw)
            when {
                h != null -> { val l = h.groupValues[1].length; pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (26 - l * 2).sp, color = if (l <= 2) accent else Color.Unspecified)); inline(h.groupValues[2]); pop() }
                raw.trimStart().startsWith("> ") -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Gray)); append("▎ "); inline(raw.trimStart().removePrefix("> ")); pop() }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(raw) -> { append("   •  "); inline(raw.replaceFirst(Regex("^\\s*[-*+]\\s+"), "")) }
                else -> inline(raw)
            }
            append("\n")
        }
    }
    private val inl = Regex("""(\*\*[^*]+\*\*)|(`[^`]+`)|(\*[^*]+\*|_[^_]+_)|(\[([^\]]+)\]\(([^)]+)\))""")
    private fun AnnotatedString.Builder.inline(s: String) {
        var last = 0
        inl.findAll(s).forEach { m -> append(s.substring(last, m.range.first))
            when { m.groups[1] != null -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(m.value.trim('*')); pop() }
                m.groups[2] != null -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))); append(m.value.trim('`')); pop() }
                m.groups[3] != null -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(m.value.trim('*', '_')); pop() }
                else -> { pushStyle(SpanStyle(color = Color(0xFF7548F8), textDecoration = TextDecoration.Underline)); append(m.groupValues[5]); pop() } }
            last = m.range.last + 1 }
        append(s.substring(last))
    }
}

fun formatStructured(text: String, ext: String): String = if (ext == "json") {
    val t = text.trim(); if (t.startsWith("[")) org.json.JSONArray(t).toString(2) else org.json.JSONObject(t).toString(2)
} else {
    val out = java.io.StringWriter()
    javax.xml.transform.TransformerFactory.newInstance().apply { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }.newTransformer().apply {
        setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes"); setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }.transform(javax.xml.transform.stream.StreamSource(java.io.StringReader(text)), javax.xml.transform.stream.StreamResult(out)); out.toString()
}

@Composable
private fun TextEditor(f: File) {
    var original by remember { mutableStateOf<String?>(null) }; var text by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    var find by remember { mutableStateOf("") }; var repl by remember { mutableStateOf("") }; var preview by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val ext = f.extension.lowercase(); val dark = MaterialTheme.colorScheme.background.red < 0.5f
    LaunchedEffect(f) { withContext(Dispatchers.IO) {
        when { f.length() > 16L * 1024 * 1024 -> error = "File is larger than 16 MB — open it with another editor."
            else -> runCatching { val b = f.readBytes(); if (b.take(8000).any { it == 0.toByte() }) error("This looks like a binary file"); String(b, Charsets.UTF_8) }
                .onSuccess { original = it; text = it }.onFailure { error = it.message } } } }
    val dirty = original != null && text != original
    fun save() = scope.launch { msg = withContext(Dispatchers.IO) { runCatching {
        val tmp = File(f.parentFile, ".${f.name}.astrum-tmp"); tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); "Saved"
    }.getOrElse { "Save failed: ${it.message}" } }; if (msg == "Saved") original = text }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(find, { find = it }, label = { Text("Find (${if (find.isEmpty()) 0 else Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(text).count()})") }, singleLine = true, modifier = Modifier.width(220.dp))
            Spacer(Modifier.width(8.dp)); OutlinedTextField(repl, { repl = it }, label = { Text("Replace") }, singleLine = true, modifier = Modifier.width(200.dp))
            TextButton(enabled = find.isNotEmpty(), onClick = { text = text.replace(find, repl, ignoreCase = true) }) { Text("Replace all") }
            if (ext == "md") TextButton(onClick = { preview = !preview }) { Text(if (preview) "Source" else "Preview") }
            if (ext in setOf("json", "xml")) TextButton(onClick = { runCatching { text = formatStructured(text, ext) }.onFailure { msg = "Invalid ${ext.uppercase()}: ${it.message}" } }) { Text("Format") }
            Spacer(Modifier.weight(1f)); msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
            IconButton(enabled = dirty, onClick = { save() }) { Icon(Icons.Filled.Save, "Save (Ctrl+S)") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)); return@Column }
        if (original == null) { Text("Loading…"); return@Column }
        if (preview) { Text(Markdown.render(text, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)); return@Column }
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).onKeyEvent { if (it.type == KeyEventType.KeyDown && it.isCtrlPressedCompat() && it.key == Key.S) { if (dirty) save(); true } else false }) {
            Text((1..(text.count { it == '\n' } + 1)).joinToString("\n"), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp))
            BasicTextField(text, { text = it }, textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = { t -> TransformedText(Highlighter.highlight(t.text, ext, dark), OffsetMapping.Identity) },
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isCtrlPressedCompat() = (this.nativeKeyEvent as? java.awt.event.KeyEvent)?.isControlDown == true

// ---------------- PDF ----------------
object PdfTools {
    fun mergeInto(files: List<File>, dir: File): String = runCatching {
        val out = FileOperations.uniqueTarget(dir, "merged.pdf")
        PDFMergerUtility().apply { files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.forEach { addSource(it) }; destinationFileName = out.absolutePath }
            .mergeDocuments(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())
        "Created ${out.name}"
    }.getOrElse { "Merge failed: ${it.message}" }
    fun extract(f: File, pages: List<Int>): String = runCatching {
        val out = FileOperations.uniqueTarget(f.parentFile, "${f.nameWithoutExtension}_pages.pdf")
        Loader.loadPDF(f).use { src -> PDDocument().use { d -> pages.forEach { d.importPage(src.getPage(it - 1)) }; d.save(out) } }; "Saved ${out.name}"
    }.getOrElse { "Failed: ${it.message}" }
    fun split(f: File): String = runCatching {
        var n = 0; Loader.loadPDF(f).use { src -> for (i in 0 until src.numberOfPages) { PDDocument().use { d -> d.importPage(src.getPage(i)); d.save(FileOperations.uniqueTarget(f.parentFile, "${f.nameWithoutExtension}_p${i + 1}.pdf")) }; n++ } }
        "Created $n files"
    }.getOrElse { "Failed: ${it.message}" }
    fun rotateCopy(f: File, deg: Int): String = runCatching {
        val out = FileOperations.uniqueTarget(f.parentFile, "${f.nameWithoutExtension}_rotated.pdf")
        Loader.loadPDF(f).use { d -> d.pages.forEach { it.rotation = (it.rotation + deg) % 360 }; d.save(out) }; "Saved ${out.name}"
    }.getOrElse { "Failed: ${it.message}" }
}

@Composable
private fun PdfViewer(f: File) {
    val doc = remember(f) { runCatching { Loader.loadPDF(f) } }
    androidx.compose.runtime.DisposableEffect(doc) { onDispose { doc.getOrNull()?.close() } }
    val d = doc.getOrNull() ?: run { Text("Cannot open PDF: ${doc.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)); return }
    val renderer = remember(d) { PDFRenderer(d) }
    val lock = remember { Any() }
    var zoom by remember { mutableFloatStateOf(1f) }; var rotation by remember { mutableIntStateOf(0) }; var msg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }; var pagesSpec by remember { mutableStateOf("") }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${list.firstVisibleItemIndex + 1} / ${d.numberOfPages}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { zoom = (zoom * 1.2f).coerceAtMost(4f) }) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
            IconButton(onClick = { zoom = (zoom / 1.2f).coerceAtLeast(0.4f) }) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
            IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
            OutlinedTextField(query, { query = it }, label = { Text("Search text") }, singleLine = true, modifier = Modifier.width(180.dp))
            TextButton(enabled = query.isNotBlank(), onClick = { scope.launch {
                val hit = withContext(Dispatchers.IO) { synchronized(lock) { val st = PDFTextStripper(); (1..d.numberOfPages).firstOrNull { p ->
                    p - 1 > list.firstVisibleItemIndex && run { st.startPage = p; st.endPage = p; st.getText(d).contains(query, true) } } ?: (1..d.numberOfPages).firstOrNull { p -> st.startPage = p; st.endPage = p; st.getText(d).contains(query, true) } } }
                if (hit != null) list.scrollToItem(hit - 1) else msg = "Not found (scanned PDFs contain no text)" } }) { Text("Find next") }
            OutlinedTextField(pagesSpec, { pagesSpec = it }, label = { Text("Pages e.g. 1-3,5") }, singleLine = true, modifier = Modifier.width(150.dp))
            TextButton(enabled = pagesSpec.isNotBlank(), onClick = { scope.launch { msg = withContext(Dispatchers.IO) {
                val pages = pagesSpec.split(',').flatMap { p -> p.trim().split('-').let { r -> if (r.size == 2) (r[0].trim().toInt()..r[1].trim().toInt()).toList() else listOf(r[0].trim().toInt()) } }.filter { it in 1..d.numberOfPages }
                PdfTools.extract(f, pages) } } }) { Text("Extract") }
            TextButton(onClick = { scope.launch { msg = withContext(Dispatchers.IO) { PdfTools.split(f) } } }) { Text("Split") }
            if (rotation != 0) TextButton(onClick = { scope.launch { msg = withContext(Dispatchers.IO) { PdfTools.rotateCopy(f, rotation) } } }) { Text("Save rotated") }
            Spacer(Modifier.weight(1f)); msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        }
        Text(d.documentInformation.let { i -> listOfNotNull(i.title?.let { "Title: $it" }, i.author?.let { "Author: $it" }, i.producer?.let { "Producer: $it" }).joinToString(" · ") },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentPadding = PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            items(d.numberOfPages) { p ->
                val bmp by produceState<ImageBitmap?>(null, p, zoom) { value = withContext(Dispatchers.IO) { synchronized(lock) { runCatching { renderer.renderImageWithDPI(p, 110f * zoom).toComposeImageBitmap() }.getOrNull() } } }
                Box(Modifier.padding(8.dp).background(Color.White)) { bmp?.let { Image(it, "Page ${p + 1}", modifier = Modifier.graphicsLayer { rotationZ = rotation.toFloat() }) } ?: Text("Rendering page ${p + 1}…", Modifier.padding(60.dp), color = Color.Gray) }
            }
        }
    }
}

// ---------------- Archives ----------------
@Composable
private fun ArchiveViewer(f: File) {
    var pw by remember { mutableStateOf<String?>(null) }; var ask by remember { mutableStateOf(false) }
    val entries by produceState<Result<List<com.lyvt.files.core.archive.ArchiveEntryInfo>>?>(null, f, pw) { value = withContext(Dispatchers.IO) { runCatching { ArchiveManager.list(f, pw?.toCharArray()) } } }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var msg by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var bytes by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    fun extract(only: Set<String>?) = scope.launch {
        busy = true; bytes = 0
        val dest = FileOperations.uniqueTarget(f.parentFile, f.name.substringBefore('.'))
        msg = withContext(Dispatchers.IO) { runCatching { ArchiveManager.extract(f, dest, pw?.toCharArray(), only) { bytes += it } }.fold({ "Extracted $it file(s) to ${dest.name}" },
            { if (it is com.lyvt.files.core.archive.ArchivePasswordException) { ask = true; it.message } else "Failed: ${it.message}" }) }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { extract(null) }, enabled = !busy) { Text("Extract all") }
            TextButton(onClick = { extract(selected.toSet()) }, enabled = !busy && selected.isNotEmpty()) { Text("Extract ${selected.size} selected") }
            TextButton(onClick = { scope.launch { msg = ArchiveManager.test(f, pw?.toCharArray()).fold({ "Integrity OK — $it entries verified" }, { "Integrity check failed: ${it.message}" }) } }) { Text("Test integrity") }
            if (busy) Text("Extracting… ${Formatters.size(bytes)}", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f)); msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        }
        val r = entries
        when {
            r == null -> Text("Reading archive…")
            r.isFailure -> Text(r.exceptionOrNull()?.message ?: "Cannot read archive", color = MaterialTheme.colorScheme.error)
            else -> { if (r.getOrThrow().any { it.encrypted } && pw == null) LaunchedEffect(Unit) { ask = true }
                LazyColumn { items(r.getOrThrow(), key = { it.path }) { e -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(e.path in selected, { c -> if (c) selected.add(e.path) else selected.remove(e.path) })
                    Text((if (e.isDirectory) "📁 " else "") + e.path + if (e.encrypted) " 🔒" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!e.isDirectory && e.size >= 0) Text(Formatters.size(e.size), style = MaterialTheme.typography.bodySmall) } } } }
        }
    }
    if (ask) InputDialog("Archive password", password = true, label = "Password", onConfirm = { pw = it }, onDismiss = { ask = false })
}
