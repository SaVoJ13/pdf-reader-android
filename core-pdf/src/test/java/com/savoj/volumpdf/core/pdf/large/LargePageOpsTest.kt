package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.PdfBoxPageOperationsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** PageOperationsEngine: злиття, розділення, перестановка, поворот, видалення, зображення → PDF. */
class LargePageOpsTest : LargeTestBase() {

    private val engine = PdfBoxPageOperationsEngine()

    private fun merge(case: String, a: File, b: File) {
        val out = newOut("$case.pdf")
        probe(case, "mergePdfs", out, Expect.OK, listOf(a, b)) { flowLast(engine.mergePdfs(mockContext, listOf(uri(a), uri(b)), uri(out))) }
        out.delete()
    }

    @Test fun merge_pages520_pages2000() = merge("merge_pages520+2000", TestPdfs.pages500(), TestPdfs.pages2000())
    @Test fun merge_big60_big100() = merge("merge_big60+big100(160MB)", TestPdfs.big60(), TestPdfs.big100())
    @Test fun merge_big60_big60() = merge("merge_big60+big60(120MB)", TestPdfs.big60(), TestPdfs.big60())
    @Test fun merge_forms_rotcrop() = merge("merge_forms600+rotcrop", TestPdfs.forms600(), TestPdfs.rotCrop())

    private fun extract(case: String, f: File, idx: List<Int>) {
        val out = newOut("$case.pdf")
        probe(case, "extractPages[${idx.size}]", out, Expect.OK, listOf(f)) { flowLast(engine.extractPages(mockContext, uri(f), idx, uri(out), null)) }
        out.delete()
    }

    @Test fun split_big100_first5() = extract("split_big100_first5", TestPdfs.big100(), (0 until 5).toList())
    @Test fun split_big100_all() = extract("split_big100_all50", TestPdfs.big100(), (0 until 50).toList())
    @Test fun split_pages2000_100() = extract("split_pages2000_100", TestPdfs.pages2000(), (900 until 1000).toList())
    @Test fun split_pages2000_all() = extract("split_pages2000_all", TestPdfs.pages2000(), (0 until 2000).toList())
    @Test fun reorder_pages520_reverse() = extract("reorder_pages520_reversed", TestPdfs.pages500(), (519 downTo 0).toList())
    @Test fun split_forms600_page1() = extract("split_forms600_p1", TestPdfs.forms600(), listOf(0))
    @Test fun split_rotcrop_all() = extract("split_rotcrop_all", TestPdfs.rotCrop(), (0 until 12).toList())

    private fun rotate(case: String, f: File, idx: List<Int>) {
        val out = newOut("$case.pdf")
        probe(case, "rotatePages[${idx.size}]", out, Expect.OK, listOf(f)) {
            runBlocking { engine.rotatePages(mockContext, uri(f), idx, 90, uri(out), null) }
        }
        out.delete()
    }

    @Test fun rotate_big100_first() = rotate("rotate_big100_p1", TestPdfs.big100(), listOf(0))
    @Test fun rotate_pages2000_all() = rotate("rotate_pages2000_all", TestPdfs.pages2000(), (0 until 2000).toList())
    @Test fun rotate_rotcrop_all() = rotate("rotate_rotcrop_all", TestPdfs.rotCrop(), (0 until 12).toList())

    private fun delete(case: String, f: File, idx: List<Int>) {
        val out = newOut("$case.pdf")
        probe(case, "deletePages[${idx.size}]", out, Expect.OK, listOf(f)) {
            runBlocking { engine.deletePages(mockContext, uri(f), idx, uri(out), null) }
        }
        out.delete()
    }

    @Test fun delete_pages520_10() = delete("delete_pages520_10", TestPdfs.pages500(), (100 until 110).toList())
    @Test fun delete_pages2000_1999() = delete("delete_pages2000_keep1", TestPdfs.pages2000(), (1 until 2000).toList())
    @Test fun delete_big60_half() = delete("delete_big60_half", TestPdfs.big60(), (0 until 15).toList())
    @Test fun delete_deeptree_100() = delete("delete_deeptree256_100", TestPdfs.deepTree(8), (0 until 100).toList())

    private fun images(case: String, n: Int) {
        val files = TestPdfs.jpegSet(n)
        val out = newOut("$case.pdf")
        val total = files.sumOf { it.length() }
        probe(case, "imagesToPdf[$n jpeg A4@300dpi, ${total / 1024 / 1024} MB]", out, Expect.OK, files.distinct()) {
            runBlocking { engine.imagesToPdf(mockContext, files.map { uri(it) }, uri(out)) }
        }
        out.delete()
    }

    @Test fun images_1() = images("images_1", 1)
    @Test fun images_20() = images("images_20", 20)
    @Test fun images_60() = images("images_60", 60)
}
