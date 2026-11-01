package com.lyvt.files

import com.lyvt.files.core.organizer.OrganizerEngine
import com.lyvt.files.core.organizer.OrganizerRule
import com.lyvt.files.core.pdf.PdfTools
import com.lyvt.files.feature.devtools.EncodingDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class OrganizerEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun dryRunTouchesNothingAndExecuteMovesAndUndoRestores() = runTest {
        val dl = tmp.newFolder("Download")
        File(dl, "app.apk").writeText("a"); File(dl, "notes.txt").writeText("n"); File(dl, "game.APK").writeText("g")
        val rule = OrganizerRule("apks", dl.path, "APKs", extensions = setOf("apk"))
        val plan = OrganizerEngine.plan(rule)
        assertEquals(setOf("app.apk", "game.APK"), plan.moves.map { it.from.name }.toSet())
        assertTrue(File(dl, "app.apk").exists()) // dry run did not move
        val res = OrganizerEngine.execute(plan)
        assertEquals(2, res.journal.size)
        assertTrue(File(dl, "APKs/app.apk").exists()); assertFalse(File(dl, "app.apk").exists())
        assertTrue(File(dl, "notes.txt").exists())
        val (ok, fail) = OrganizerEngine.undo(OrganizerEngine.journalFromJson(OrganizerEngine.journalToJson(res.journal)))
        assertEquals(2 to 0, ok to fail)
        assertTrue(File(dl, "app.apk").exists()); assertFalse(File(dl, "APKs").exists())
    }

    @Test fun neverOverwritesExistingTargets() = runTest {
        val src = tmp.newFolder("s"); File(src, "Docs").mkdirs()
        File(src, "Docs/a.pdf").writeText("old"); File(src, "a.pdf").writeText("new")
        val plan = OrganizerEngine.plan(OrganizerRule("d", src.path, "Docs", extensions = setOf("pdf")))
        assertEquals("a (1).pdf", plan.moves.single().to.name)
        OrganizerEngine.execute(plan)
        assertEquals("old", File(src, "Docs/a.pdf").readText()); assertEquals("new", File(src, "Docs/a (1).pdf").readText())
    }

    @Test fun tokensAndFilters() = runTest {
        val src = tmp.newFolder("code")
        val kt = File(src, "Main.kt").apply { writeText("x"); setLastModified(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse("2023-04-05")!!.time) }
        File(src, "big.bin").writeBytes(ByteArray(5000))
        assertEquals(File(src, "Code/Kotlin"), OrganizerEngine.resolveDestination(OrganizerRule("c", src.path, "Code/{lang}"), kt))
        assertEquals(File(src, "2023-04"), OrganizerEngine.resolveDestination(OrganizerRule("m", src.path, "{yyyy}-{MM}"), kt))
        assertEquals(File(src, "safe"), OrganizerEngine.resolveDestination(OrganizerRule("t", src.path, "../../safe"), kt))
        val plan = OrganizerEngine.plan(OrganizerRule("big", src.path, "Big", minSizeBytes = 1024))
        assertEquals(listOf("big.bin"), plan.moves.map { it.from.name })
        val re = OrganizerEngine.plan(OrganizerRule("re", src.path, "X", namePattern = "^main"))
        assertEquals(listOf("Main.kt"), re.moves.map { it.from.name })
    }

    @Test fun missingSourceReportsProblem() = runTest {
        assertTrue(OrganizerEngine.plan(OrganizerRule("x", "/does/not/exist", "y")).problems.isNotEmpty())
    }

    @Test fun pdfPageSpec() {
        assertEquals(listOf(1, 2, 3, 5), PdfTools.parsePages("1-3, 5, 99", 10))
    }

    @Test fun encodingDetection() {
        val ascii = tmp.newFile("a.txt").apply { writeText("hello\nworld\n") }
        val utf = tmp.newFile("u.txt").apply { writeText("grüße\r\n") }
        val bin = tmp.newFile("b.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        assertEquals("ASCII", EncodingDetector.detect(ascii).toMap()["Encoding"])
        assertEquals("LF (Unix)", EncodingDetector.detect(ascii).toMap()["Line endings"])
        assertEquals("UTF-8", EncodingDetector.detect(utf).toMap()["Encoding"])
        assertTrue(EncodingDetector.detect(bin).toMap()["Encoding"]!!.startsWith("Binary"))
    }
}
