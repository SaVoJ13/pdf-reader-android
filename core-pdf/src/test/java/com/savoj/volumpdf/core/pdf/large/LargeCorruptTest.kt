package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.PdfBoxFormEngine
import com.savoj.volumpdf.core.pdf.PdfBoxPageOperationsEngine
import com.savoj.volumpdf.core.pdf.PdfIo
import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import com.savoj.volumpdf.core.pdf.model.MarkKind
import com.savoj.volumpdf.core.pdf.model.PageMarkup
import com.savoj.volumpdf.core.pdf.model.PdfOperationResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пошкоджені й безглузді файли. Очікування: кожна операція повертає Success або Error з userMessage,
 * без необробленого винятку, без зависання (Timeout), без витоку файлів; при Error вихідний файл
 * (заздалегідь заповнений маркером) не псується.
 */
class LargeCorruptTest : LargeTestBase() {

    private val forms = PdfBoxFormEngine()
    private val pageOps = PdfBoxPageOperationsEngine()
    private val valid = lazy { TestPdfs.valid10() }

    private fun markups(page: Int) = listOf(
        PageMarkup.Text("t", page, 100f, 100f, "Bad file test", 14f, 0xFF0000FF.toInt()),
        PageMarkup.Mark("c", page, 200f, 200f, MarkKind.CHECK, 16f, 0xFF00AA00.toInt())
    )

    /** Вихід із маркером: при Error маркер має лишитись непорушеним. */
    private fun sentinelOut(name: String): File = newOut(name).also { it.writeText("SENTINEL") }

    private fun checkOut(case: String, op: String, out: File, row: Row, verifyOpens: Boolean) {
        if (row.outcome == "Error") {
            val untouched = out.exists() && out.readText() == "SENTINEL"
            if (!untouched) {
                Probe.record(row.copy(op = "$op [CLOBBER]", outcome = "CLOBBER", detail = "вихід змінено/знищено після Error (len=${if (out.exists()) out.length() else -1})"), klass())
            }
            assertTrue("[$case/$op] після Error вихідний файл змінено (len=${if (out.exists()) out.length() else -1})", untouched)
        } else if (row.outcome == "OK" && verifyOpens) {
            val ok = try { PDDocument.load(out).use { it.numberOfPages >= 1 } } catch (e: Throwable) { false }
            if (!ok) Probe.record(row.copy(op = "$op [BADOUT]", outcome = "BADOUT", detail = "Success, але результат не відкривається"), klass())
            assertTrue("[$case/$op] Success, але результат не відкривається", ok)
        }
    }

    private fun corrupt(name: String, strictError: Boolean = false, verifyOpens: Boolean = true) {
        val f = TestPdfs.corruptSet().getValue(name)
        val v = valid.value
        val src = uri(f)
        val case = "bad_$name"
        val exp = if (strictError) Expect.ERROR else Expect.OK_OR_ERROR

        // Сирий завантажувач і метадані (без тверджень: виняток тут — лише спостереження)
        Probe.measure(case, "open+metadata (loader)", null) {
            PdfIo.loadDocument(mockContext, src, null).use { it.numberOfPages }
        }.second.also { Probe.record(it, klass()) }

        probe(case, "checkEditability", null, Expect.OK_OR_ERROR, listOf(f)) {
            runBlocking { newMarkupEngine().checkEditability(mockContext, src, null) }
        }
        // extractFormFields має сигнатуру List: виняток тут допустимий (UI ловить Throwable), лише фіксуємо
        Probe.measure(case, "extractFormFields", null) { runBlocking { forms.extractFormFields(mockContext, src, null) } }
            .second.also { Probe.record(it, klass()) }

        for ((label, page) in listOf("first" to 0, "last" to 9)) {
            val out = sentinelOut("m_$label.pdf")
            val (_, row) = probe(case, "applyEdits[$label]", out, exp, listOf(f)) {
                runBlocking { newMarkupEngine().applyEdits(mockContext, src, markups(page), emptyList(), uri(out), null) }
            }
            checkOut(case, "applyEdits[$label]", out, row, verifyOpens)
        }
        run {
            val out = sentinelOut("form.pdf")
            val (_, row) = probe(case, "fillFormFields", out, exp, listOf(f)) {
                runBlocking { forms.fillFormFields(mockContext, src, listOf(FormFieldUpdate("x", "y")), uri(out), null) }
            }
            checkOut(case, "fillFormFields", out, row, verifyOpens)
        }
        run {
            val out = sentinelOut("rot.pdf")
            val (_, row) = probe(case, "rotatePages[0]", out, exp, listOf(f)) {
                runBlocking { pageOps.rotatePages(mockContext, src, listOf(0), 90, uri(out), null) }
            }
            checkOut(case, "rotatePages", out, row, verifyOpens)
        }
        run {
            val out = sentinelOut("del.pdf")
            val (_, row) = probe(case, "deletePages[0]", out, exp, listOf(f)) {
                runBlocking { pageOps.deletePages(mockContext, src, listOf(0), uri(out), null) }
            }
            checkOut(case, "deletePages", out, row, verifyOpens)
        }
        run {
            val out = sentinelOut("ext.pdf")
            val (_, row) = probe(case, "extractPages[0]", out, exp, listOf(f)) {
                flowLast(pageOps.extractPages(mockContext, src, listOf(0), uri(out), null))
            }
            checkOut(case, "extractPages", out, row, verifyOpens)
        }
        run {
            val out = sentinelOut("merge.pdf")
            val (_, row) = probe(case, "mergePdfs[bad+valid]", out, exp, listOf(f, v)) {
                flowLast(pageOps.mergePdfs(mockContext, listOf(src, uri(v)), uri(out)))
            }
            checkOut(case, "mergePdfs", out, row, verifyOpens)
        }
    }

    @Test fun truncated50() = corrupt("truncated50")
    @Test fun truncatedNoTail() = corrupt("truncated_notail")
    @Test fun headerOnly() = corrupt("header_only", strictError = true)
    @Test fun empty0() = corrupt("empty0", strictError = true)
    @Test fun notPdfText() = corrupt("not_pdf_text", strictError = true)
    @Test fun notPdfRandom() = corrupt("not_pdf_random", strictError = true)
    @Test fun badXrefOffsets() = corrupt("bad_xref_offsets")
    @Test fun badStartxref() = corrupt("bad_startxref")
    @Test fun badTrailerRoot() = corrupt("bad_trailer_root")
    @Test fun noTrailer() = corrupt("no_trailer")
    @Test fun count9999() = corrupt("count_9999")
    @Test fun count0() = corrupt("count_0")
    @Test fun selfrefTree() = corrupt("selfref_tree")
    @Test fun pagesCycle() = corrupt("pages_cycle")
    @Test fun deepTree5000() = corrupt("deep_tree_5000")
    @Test fun deepArray100000() = corrupt("deep_array_100000")
    @Test fun mediabox1e9() = corrupt("mediabox_1e9")
    @Test fun mediaboxFloatOverflow() = corrupt("mediabox_float_overflow")
    @Test fun mediaboxZero() = corrupt("mediabox_zero")
    @Test fun mediaboxNegative() = corrupt("mediabox_negative")

    /** Декомпресійні бомби: 1 ГіБ у потоці вмісту, 3,6 ГБ у зображенні. Без перевірки відкриття результату. */
    @Test fun flateBombContent() = corrupt("flate_bomb_1g_content", verifyOpens = false)
    @Test fun flateBombImage() = corrupt("flate_bomb_image", verifyOpens = false)
}
