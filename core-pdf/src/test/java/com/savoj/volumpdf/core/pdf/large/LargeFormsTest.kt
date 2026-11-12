package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.PdfBoxFormEngine
import com.savoj.volumpdf.core.pdf.model.FormField
import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** FormEngine: читання 600 полів, запис значень (усі типи), багаторядкові поля. */
class LargeFormsTest : LargeTestBase() {

    private val engine = PdfBoxFormEngine()

    @Test fun fill600SingleLineText_checks_combos_lists() {
        val f = TestPdfs.forms600(); val out = newOut("forms600_filled.pdf")
        val updates = (0 until 200).filter { it % 4 != 0 }.map { FormFieldUpdate("t_$it", "новий текст $it") } +
            (0 until 200).map { FormFieldUpdate("c_$it", it % 3 == 0) } +
            (0 until 100).map { FormFieldUpdate("cmb_$it", if (it % 3 == 0) "free" else "three") } +
            (0 until 100).map { FormFieldUpdate("lst_$it", "beta") }
        probe("forms600", "fillFormFields[${updates.size} updates]", out, Expect.OK, listOf(f)) {
            runBlocking { engine.fillFormFields(mockContext, uri(f), updates, uri(out), null) }
        }
        val (r, _) = probe("forms600_out", "extractFormFields", null, Expect.OK, listOf(out)) {
            runBlocking { engine.extractFormFields(mockContext, uri(out), null) }
        }
        val fields = r.getOrThrow()
        assertEquals(600, fields.size)
        assertEquals("beta", (fields.first { it.fullyQualifiedName == "lst_5" } as FormField.Choice).selectedValue)
    }

    /** Відомий збій (JVM): багаторядкове текстове поле — StringIndexOutOfBounds при побудові appearance. */
    @Test fun fillMultilineText() {
        val f = TestPdfs.forms600(); val out = newOut("forms600_ml.pdf")
        probe("forms600", "fillFormFields[multiline t_0]", out, Expect.OK, listOf(f)) {
            runBlocking { engine.fillFormFields(mockContext, uri(f), listOf(FormFieldUpdate("t_0", "line1\nline2")), uri(out), null) }
        }
    }

    @Test fun fillUnknownFieldAndEmpty() {
        val f = TestPdfs.big60(); val out = newOut("big60_nofields.pdf")
        // Документ без AcroForm: непорожні оновлення → Error з повідомленням; порожні → копія
        probe("big60", "fillFormFields[no AcroForm, 1 update]", out, Expect.ERROR, listOf(f)) {
            runBlocking { engine.fillFormFields(mockContext, uri(f), listOf(FormFieldUpdate("x", "y")), uri(out), null) }
        }
        out.delete()
        probe("big60", "fillFormFields[no AcroForm, 0 updates]", out, Expect.OK, listOf(f)) {
            runBlocking { engine.fillFormFields(mockContext, uri(f), emptyList(), uri(out), null) }
        }
    }
}
