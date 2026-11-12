package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.PdfIo
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Test
import java.io.File

/**
 * ЕКСПЕРИМЕНТ (не змінює продукт): скільки купи потребує завантаження з файлу з тимчасовим сховищем
 * (MemoryUsageSetting.setupTempFileOnly) порівняно з поточним PDDocument.load(InputStream).
 * Результати лише інформують Архітектора; рішення про зміну PdfIo.loadDocument не приймається тут.
 */
class LargeAlternativeLoadTest : LargeTestBase() {

    private fun compare(case: String, f: File, withStream: Boolean = true) {
        val u = uri(f)
        if (withStream) {
        Probe.measure(case, "ALT stream: load(InputStream)+save", null) {
            PdfIo.loadDocument(mockContext, u, null).use { d -> d.save(newOut("alt_a.pdf")); d.numberOfPages }
        }.second.also { Probe.record(it, klass()) }
        newOut("alt_a.pdf").delete()
        }
        Probe.measure(case, "ALT file+tempfile: load(File, tempFileOnly)+save", null) {
            PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()).use { d -> d.save(newOut("alt_b.pdf")); d.numberOfPages }
        }.second.also { Probe.record(it, klass()) }
        newOut("alt_b.pdf").delete()
        Probe.measure(case, "ALT file+mixed(32MB): load(File, mixed)+save", null) {
            PDDocument.load(f, MemoryUsageSetting.setupMixed(32L * 1024 * 1024)).use { d -> d.save(newOut("alt_c.pdf")); d.numberOfPages }
        }.second.also { Probe.record(it, klass()) }
        newOut("alt_c.pdf").delete()
    }

    @Test fun big100() = compare("big100", TestPdfs.big100())
    /** Потоковий варіант на 324 МБ у купі 512 МБ вішає JVM (див. LARGE_FILES_REPORT), тут його пропущено. */
    @Test fun scan200() = compare("scan200_324MB", TestPdfs.scan200(), withStream = false)
}
