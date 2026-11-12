package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.PdfBoxFormEngine
import com.savoj.volumpdf.core.pdf.PdfBoxPageOperationsEngine
import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import com.savoj.volumpdf.core.pdf.model.MarkKind
import com.savoj.volumpdf.core.pdf.model.PageMarkup
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Зашифровані: RC4-40/128, AES-128/256 x (user-пароль є/нема) x (права повні/обмежені). */
class LargeEncryptedTest : LargeTestBase() {

    private val forms = PdfBoxFormEngine()
    private val pageOps = PdfBoxPageOperationsEngine()

    private fun mark(page: Int = 0) = listOf(
        PageMarkup.Text("t", page, 100f, 100f, "Enc test", 14f, 0xFF0000FF.toInt()),
        PageMarkup.Mark("c", page, 200f, 200f, MarkKind.CHECK, 16f, 0xFF00AA00.toInt())
    )

    /** Стан вихідного файлу: відкривається без пароля / з паролем, чи шифрований, скільки сторінок. */
    private fun inspect(f: File, pw: String?): String {
        fun tryOpen(p: String?): String = try {
            PDDocument.load(f, p ?: "").use { "OK(enc=${it.isEncrypted},pages=${it.numberOfPages})" }
        } catch (e: Throwable) {
            "FAIL(${e.javaClass.simpleName})"
        }
        return "noPw=${tryOpen(null)} withPw=${if (pw != null) tryOpen(pw) else "-"}"
    }

    private fun matrix(a: TestPdfs.Algo, userPw: Boolean, restricted: Boolean) {
        val f = TestPdfs.encrypted(a, userPw, restricted)
        val case = f.nameWithoutExtension
        val pw = if (userPw) TestPdfs.USER_PW else null
        val src = uri(f)

        // checkEditability з правильним паролем
        val (ce, _) = probe(case, "checkEditability(pw)", null, Expect.OK, listOf(f)) {
            runBlocking { newMarkupEngine().checkEditability(mockContext, src, pw) }
        }
        val e = ce.getOrThrow()
        assertNotNull(e)
        assertTrue(e!!.isEncrypted)
        assertEquals("canMarkup", !restricted, e.canMarkup)
        assertEquals("canFillForms", !restricted, e.canFillForms)

        // Без пароля / хибний пароль (лише коли є user-пароль)
        if (userPw) {
            val (c0, _) = probe(case, "checkEditability(no pw)", null, Expect.OK_OR_ERROR, listOf(f)) {
                runBlocking { newMarkupEngine().checkEditability(mockContext, src, null) }
            }
            assertNull(c0.getOrThrow())
            val out0 = newOut("wrong.pdf")
            probe(case, "applyEdits(wrong pw)", out0, Expect.ERROR, listOf(f)) {
                runBlocking { newMarkupEngine().applyEdits(mockContext, src, mark(), emptyList(), uri(out0), "wrong-pw") }
            }
            probe(case, "fillFormFields(wrong pw)", out0, Expect.ERROR, listOf(f)) {
                runBlocking { forms.fillFormFields(mockContext, src, listOf(FormFieldUpdate("t_1", "x")), uri(out0), "wrong-pw") }
            }
            val (xf, row) = Probe.measure(case, "extractFormFields(wrong pw)", null) {
                runBlocking { forms.extractFormFields(mockContext, src, "wrong-pw") }
            }
            Probe.record(row, klass())
            assertTrue("Хибний пароль у extractFormFields дає виняток (викликач ловить)", xf.isFailure)
        }

        // Розмітка як копія
        val out = newOut("markup_copy.pdf")
        probe(case, "applyEdits->copy", out, if (restricted) Expect.ERROR else Expect.OK, listOf(f)) {
            runBlocking { newMarkupEngine().applyEdits(mockContext, src, mark(), emptyList(), uri(out), pw) }
        }
        if (!restricted) {
            println("[INSPECT] $case applyEdits copy: ${inspect(out, pw)}")
            PDDocument.load(out, pw ?: "").use {
                assertTrue("копія має лишитись зашифрованою", it.isEncrypted)
                assertEquals(15, it.numberOfPages)
            }
        }
        out.delete()

        // Перезапис того самого файлу заборонено (R15)
        val same = newOut("same.pdf")
        f.copyTo(same, overwrite = true)
        probe(case, "applyEdits->same URI", same, Expect.ERROR, listOf(same)) {
            runBlocking { newMarkupEngine().applyEdits(mockContext, uri(same), mark(), emptyList(), uri(same), pw) }
        }
        assertTrue("оригінал має лишитись байт-у-байт", same.readBytes().contentEquals(f.readBytes()))
        same.delete()

        // Форми
        val outF = newOut("form_copy.pdf")
        probe(case, "fillFormFields->copy", outF, if (restricted) Expect.ERROR else Expect.OK, listOf(f)) {
            runBlocking { forms.fillFormFields(mockContext, src, listOf(FormFieldUpdate("t_1", "значення")), uri(outF), pw) }
        }
        if (!restricted) PDDocument.load(outF, pw ?: "").use { assertTrue(it.isEncrypted) }
        outF.delete()
        val (xf2, _) = probe(case, "extractFormFields(pw)", null, Expect.OK, listOf(f)) {
            runBlocking { forms.extractFormFields(mockContext, src, pw) }
        }
        assertEquals(8, xf2.getOrThrow().size)

        // Інструменти сторінок: фіксуємо результат і стан вихідного файлу
        val o1 = newOut("rot.pdf")
        val (_, row1) = probe(case, "rotatePages", o1, Expect.OK_OR_ERROR, listOf(f)) {
            runBlocking { pageOps.rotatePages(mockContext, src, listOf(0), 90, uri(o1), pw) }
        }
        if (row1.outcome == "OK") println("[INSPECT] $case rotatePages out: ${inspect(o1, pw)}")
        o1.delete()
        val o2 = newOut("ext.pdf")
        val (_, row2) = probe(case, "extractPages", o2, Expect.OK_OR_ERROR, listOf(f)) {
            flowLast(pageOps.extractPages(mockContext, src, listOf(0, 1), uri(o2), pw))
        }
        if (row2.outcome == "OK") println("[INSPECT] $case extractPages out: ${inspect(o2, pw)}")
        o2.delete()
        val o3 = newOut("del.pdf")
        val (_, row3) = probe(case, "deletePages", o3, Expect.OK_OR_ERROR, listOf(f)) {
            runBlocking { pageOps.deletePages(mockContext, src, listOf(0), uri(o3), pw) }
        }
        if (row3.outcome == "OK") println("[INSPECT] $case deletePages out: ${inspect(o3, pw)}")
        o3.delete()
    }

    @Test fun rc4_40_nopw_all() = matrix(TestPdfs.Algo.RC4_40, false, false)
    @Test fun rc4_40_nopw_restr() = matrix(TestPdfs.Algo.RC4_40, false, true)
    @Test fun rc4_40_upw_all() = matrix(TestPdfs.Algo.RC4_40, true, false)
    @Test fun rc4_40_upw_restr() = matrix(TestPdfs.Algo.RC4_40, true, true)
    @Test fun rc4_128_nopw_all() = matrix(TestPdfs.Algo.RC4_128, false, false)
    @Test fun rc4_128_nopw_restr() = matrix(TestPdfs.Algo.RC4_128, false, true)
    @Test fun rc4_128_upw_all() = matrix(TestPdfs.Algo.RC4_128, true, false)
    @Test fun rc4_128_upw_restr() = matrix(TestPdfs.Algo.RC4_128, true, true)
    @Test fun aes_128_nopw_all() = matrix(TestPdfs.Algo.AES_128, false, false)
    @Test fun aes_128_nopw_restr() = matrix(TestPdfs.Algo.AES_128, false, true)
    @Test fun aes_128_upw_all() = matrix(TestPdfs.Algo.AES_128, true, false)
    @Test fun aes_128_upw_restr() = matrix(TestPdfs.Algo.AES_128, true, true)
    @Test fun aes_256_nopw_all() = matrix(TestPdfs.Algo.AES_256, false, false)
    @Test fun aes_256_nopw_restr() = matrix(TestPdfs.Algo.AES_256, false, true)
    @Test fun aes_256_upw_all() = matrix(TestPdfs.Algo.AES_256, true, false)
    @Test fun aes_256_upw_restr() = matrix(TestPdfs.Algo.AES_256, true, true)

    /** 60 МБ AES-256: збереження розмітки копією і page-операція. */
    @Test fun big60_aes256_copy() {
        val f = TestPdfs.encryptedBig()
        val src = uri(f)
        val case = "enc_big60_aes256"
        val out = newOut("big_copy.pdf")
        probe(case, "applyEdits->copy", out, Expect.OK, listOf(f)) {
            runBlocking { newMarkupEngine().applyEdits(mockContext, src, mark(), emptyList(), uri(out), TestPdfs.USER_PW) }
        }
        PDDocument.load(out, TestPdfs.USER_PW).use { assertTrue(it.isEncrypted); assertEquals(30, it.numberOfPages) }
        out.delete()
        val o2 = newOut("big_ext.pdf")
        probe(case, "extractPages[5]", o2, Expect.OK, listOf(f)) {
            flowLast(pageOps.extractPages(mockContext, src, (0 until 5).toList(), uri(o2), TestPdfs.USER_PW))
        }
        println("[INSPECT] $case extractPages out: ${inspect(o2, TestPdfs.USER_PW)}")
    }
}
