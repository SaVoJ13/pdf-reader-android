package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import com.savoj.volumpdf.core.pdf.model.InkStroke
import com.savoj.volumpdf.core.pdf.model.MarkKind
import com.savoj.volumpdf.core.pdf.model.PageMarkup
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** MarkupEngine.applyEdits (текст, ✓, ✗, штрих, підпис) на першій, середній і останній сторінках великих файлів. */
class LargeMarkupTest : LargeTestBase() {

    private fun markups(page: Int): List<PageMarkup> = listOf(
        PageMarkup.Text("t$page", page, 100f, 100f, "Test 123 ABC", 14f, 0xFF0000FF.toInt()),
        PageMarkup.Mark("c$page", page, 200f, 200f, MarkKind.CHECK, 16f, 0xFF00AA00.toInt()),
        PageMarkup.Mark("x$page", page, 240f, 200f, MarkKind.CROSS, 16f, 0xFFAA0000.toInt()),
        PageMarkup.Ink("i$page", page, listOf(InkStroke(listOf(pt(120f, 300f), pt(180f, 340f), pt(240f, 310f)), 0xFF000000.toInt(), 2f))),
        PageMarkup.Signature("s$page", page, mockBitmap(), 300f, 400f, 100f, 40f)
    )

    private fun run(case: String, f: File, pages: Int, formUpdates: List<FormFieldUpdate> = emptyList(), expect: Expect = Expect.OK) {
        val src = uri(f)
        for ((label, idx) in listOf("first" to 0, "middle" to pages / 2, "last" to pages - 1)) {
            val out = newOut("${case}_$label.pdf")
            probe(case, "applyEdits[$label p${idx + 1}]", out, expect, listOf(f)) {
                runBlocking { newMarkupEngine().applyEdits(mockContext, src, markups(idx), formUpdates, uri(out), null) }
            }
            out.delete()
        }
    }

    @Test fun big60() = run("big60", TestPdfs.big60(), 30)
    @Test fun big100() = run("big100", TestPdfs.big100(), 50)
    @Test fun pages520() = run("pages520", TestPdfs.pages500(), 520)
    @Test fun pages2000() = run("pages2000", TestPdfs.pages2000(), 2000)
    @Test fun deepTree() = run("deeptree256", TestPdfs.deepTree(8), 256)
    @Test fun rotCrop() = run("rotcrop12", TestPdfs.rotCrop(), 12)
    @Test fun fonts120() = run("fonts120", TestPdfs.fonts120(fontFile()), 120)
    @Test fun forms600() = run(
        "forms600+fields", TestPdfs.forms600(), 20,
        (0 until 200).filter { it % 4 != 0 }.map { FormFieldUpdate("t_$it", "val$it") } +(0 until 200).map { FormFieldUpdate("c_$it", true) }
    )

    /** 324 МБ: у купі 512 МБ очікується відмова («Файл завеликий для редагування»), не падіння. */
    @Test fun scan200() = run("scan200_324MB", TestPdfs.scan200(), 200, expect = Expect.OK_OR_ERROR)
}
