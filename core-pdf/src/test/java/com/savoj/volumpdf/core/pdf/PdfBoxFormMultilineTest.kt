package com.savoj.volumpdf.core.pdf

import com.savoj.volumpdf.core.pdf.model.FormField
import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import com.savoj.volumpdf.core.pdf.model.PdfOperationResult
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Регресія (знахідка великих файлів F1): багаторядкове текстове поле. PDFBox при побудові appearance
 * кидав StringIndexOutOfBoundsException (не IllegalArgumentException): fillFormFields падав цілком,
 * а після збереження (NeedAppearances=true) extractFormFields кидав виняток при самому відкритті форми.
 */
class PdfBoxFormMultilineTest : MarkupTestSupport() {

    private fun multilineForm(): File = saveNew({ doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val acro = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acro
        val res = PDResources()
        res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        acro.defaultResources = res
        acro.defaultAppearance = "/Helv 10 Tf 0 g"
        val tf = PDTextField(acro)
        tf.partialName = "ml"
        tf.isMultiline = true
        tf.widgets[0].rectangle = PDRectangle(40f, 700f, 150f, 22f)
        tf.widgets[0].page = page
        page.annotations.add(tf.widgets[0])
        acro.fields.add(tf)
    }, "ml.pdf")

    @Test
    fun fillMultilineThenReadBack() = runBlocking {
        val src = multilineForm()
        val out = tempFolder.newFile("ml_out.pdf")
        val engine = PdfBoxFormEngine()
        val r = engine.fillFormFields(mockContext, uri(src), listOf(FormFieldUpdate("ml", "line1\nline2")), uri(out), null)
        assertTrue("fill має успішно записати багаторядкове поле, отримано: $r", r is PdfOperationResult.Success)
        val fields = engine.extractFormFields(mockContext, uri(out), null)
        assertEquals(1, fields.size)
        val f = fields[0] as FormField.Text
        assertTrue(f.isMultiline)
        assertEquals("line1\nline2", f.value.replace("\r\n", "\n"))
    }

    @Test
    fun markupEngineAlsoWritesMultilineFormUpdate() = runBlocking {
        val src = multilineForm()
        val out = tempFolder.newFile("ml_out2.pdf")
        val r = newMarkupEngine().applyEdits(mockContext, uri(src), emptyList(), listOf(FormFieldUpdate("ml", "abc")), uri(out), null)
        assertTrue("applyEdits з формою має успішно записати, отримано: $r", r is PdfOperationResult.Success)
        PDDocument.load(out).use { assertEquals(1, it.numberOfPages) }
    }
}
