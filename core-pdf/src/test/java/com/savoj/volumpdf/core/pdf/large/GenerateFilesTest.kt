package com.savoj.volumpdf.core.pdf.large

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Будує всі тестові файли (кеш у build/large-files) і друкує їхні розміри й час генерації. */
class GenerateFilesTest : LargeTestBase() {

    private fun gen(name: String, build: () -> File) {
        val t0 = System.nanoTime()
        val f = build()
        println("[GEN] $name: ${f.length()} B (${f.length() / 1024 / 1024} MB), ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(f.exists())
    }

    @Test fun large() {
        gen("big60") { TestPdfs.big60() }
        gen("big100") { TestPdfs.big100() }
        gen("pages500") { TestPdfs.pages500() }
        gen("pages2000") { TestPdfs.pages2000() }
    }

    @Test fun scans() {
        TestPdfs.jpegVariants().forEach { println("[GEN] ${it.name}: ${it.length()} B") }
        gen("scan200") { TestPdfs.scan200() }
    }

    @Test fun complex() {
        gen("valid10") { TestPdfs.valid10() }
        gen("rotcrop") { TestPdfs.rotCrop() }
        gen("deeptree8") { TestPdfs.deepTree(8) }
        gen("forms600") { TestPdfs.forms600() }
        gen("fonts120") { TestPdfs.fonts120(fontFile()) }
    }

    @Test fun encrypted() {
        for (a in TestPdfs.Algo.values()) for (u in listOf(false, true)) for (r in listOf(false, true))
            gen(TestPdfs.encryptedName(a, u, r)) { TestPdfs.encrypted(a, u, r) }
        gen("enc_big60") { TestPdfs.encryptedBig() }
    }

    @Test fun corrupt() {
        val t0 = System.nanoTime()
        val m = TestPdfs.corruptSet()
        m.forEach { (k, v) -> println("[GEN] bad_$k: ${v.length()} B") }
        println("[GEN] corrupt set ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
