package com.savoj.volumpdf.core.pdf.large

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.io.OutputStream
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Генератор синтетичних PDF для тестів великих, складних, зашифрованих і пошкоджених файлів.
 * Файли кешуються в `build/large-files` (поза git); повторний виклик не перебудовує готовий файл.
 */
object TestPdfs {
    val dir: File = File("build/large-files").also { it.mkdirs() }

    // Синтетичні паролі лише для тестів
    const val USER_PW = "usr-test-pw"
    const val OWNER_PW = "own-test-pw"

    private const val MB = 1024L * 1024L

    private fun cached(name: String, build: (File) -> Unit): File {
        val f = File(dir, name)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(dir, "$name.part")
        tmp.delete()
        build(tmp)
        if (f.exists()) f.delete()
        check(tmp.renameTo(f)) { "Не вдалося перейменувати $tmp" }
        return f
    }

    // ---------------------------------------------------------------- Сирі (потокові) генератори

    private fun pageText(i: Int, lines: Int): ByteArray {
        val sb = StringBuilder("BT /F1 11 Tf 14 TL 50 800 Td\n")
        for (l in 0 until lines) sb.append("(Synthetic page ${i + 1} line ${l + 1}: lorem ipsum dolor sit amet, consectetur) Tj T*\n")
        sb.append("ET\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Плоский документ: [n] сторінок із текстом (Flate), опційно /Rotate і зсунутий CropBox.
     * Об'єкти: 1 каталог, 2 /Pages, 3 шрифт, далі пари (сторінка, вміст).
     */
    private fun textDoc(
        f: File, n: Int, lines: Int = 30, mediaBox: String = "[0 0 595 842]",
        pageExtra: (Int) -> String = { "" }, catalogExtra: String = "", pagesExtra: String = "", countOverride: Int? = null
    ) {
        RawPdf(f).use { w ->
            w.reserve(3)
            val firstPage = 4
            val kids = (0 until n).joinToString(" ") { "${firstPage + it * 2} 0 R" }
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R $catalogExtra >>")
            w.obj(2, "<< /Type /Pages /Kids [$kids] /Count ${countOverride ?: n} $pagesExtra >>")
            w.obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            for (i in 0 until n) {
                val p = w.alloc(); val c = w.alloc()
                w.obj(p, "<< /Type /Page /Parent 2 0 R /MediaBox $mediaBox /Contents $c 0 R " +
                    "/Resources << /Font << /F1 3 0 R >> >> ${pageExtra(i)} >>")
                w.streamObj(c, "/Filter /FlateDecode", flate(pageText(i, lines)))
            }
            w.finish(1)
        }
    }

    /** Файл із n сторінок, кожна містить несжимаєме зображення DeviceGray (випадкові байти). */
    private fun blobDoc(f: File, targetBytes: Long, pages: Int, seed: Long) {
        val perPage = targetBytes / pages
        val w0 = 2048
        val h0 = (perPage / w0).toInt()
        RawPdf(f).use { w ->
            w.reserve(2)
            val kids = (0 until pages).joinToString(" ") { "${3 + it * 3} 0 R" }
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            w.obj(2, "<< /Type /Pages /Kids [$kids] /Count $pages >>")
            val rnd = Random(seed)
            val chunk = ByteArray(1 shl 20)
            for (i in 0 until pages) {
                val p = w.alloc(); val c = w.alloc(); val im = w.alloc()
                w.obj(p, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents $c 0 R " +
                    "/Resources << /XObject << /Im0 $im 0 R >> >> >>")
                w.streamObj(c, "", "q 595 0 0 842 0 0 cm /Im0 Do Q".toByteArray())
                val len = w0.toLong() * h0
                w.streamObj(im, "/Type /XObject /Subtype /Image /Width $w0 /Height $h0 /ColorSpace /DeviceGray /BitsPerComponent 8", len) { os ->
                    var left = len
                    while (left > 0) {
                        val k = minOf(left, chunk.size.toLong()).toInt()
                        rnd.nextBytes(chunk); os.write(chunk, 0, k); left -= k
                    }
                }
            }
            w.finish(1)
        }
    }

    /** Справжній JPEG через javax.imageio (рефлексія: android.jar не містить java.awt; у JVM-тесті воно є). */
    private fun noisyJpeg(wpx: Int, hpx: Int, amp: Int, seed: Long): ByteArray {
        val biCls = Class.forName("java.awt.image.BufferedImage")
        val img = biCls.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(wpx, hpx, 1)
        val setRgb = biCls.getMethod("setRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
        val rnd = Random(seed)
        val row = IntArray(wpx)
        for (y in 0 until hpx) {
            for (x in 0 until wpx) {
                val base = 200 + ((x + y) % 40) - 20 + (if ((y / 60) % 2 == 0 && (x / 200) % 3 == 0) -90 else 0)
                val nz = if (amp > 0) rnd.nextInt(amp * 2 + 1) - amp else 0
                val v = (base + nz).coerceIn(0, 255)
                row[x] = (v shl 16) or (v shl 8) or v
            }
            setRgb.invoke(img, 0, y, wpx, 1, row, 0, wpx)
        }
        val tmp = File.createTempFile("jpg", ".jpg", dir)
        try {
            val io = Class.forName("javax.imageio.ImageIO")
            val ok = io.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
                .invoke(null, img, "jpeg", tmp) as Boolean
            check(ok) { "ImageIO не записав JPEG" }
            return tmp.readBytes()
        } finally { tmp.delete() }
    }

    /** Три JPEG A4 300 dpi (2480×3508) різного розміру: файли jpg_v0..2.jpg. */
    fun jpegVariants(): List<File> = (0..2).map { i ->
        cached("jpg_v$i.jpg") { f ->
            val amp = listOf(6, 25, 60)[i]
            f.writeBytes(noisyJpeg(2480, 3508, amp, (i + 1).toLong()))
        }
    }

    /** 20 JPEG-файлів для imagesToPdf. */
    fun jpegSet(n: Int): List<File> { val v = jpegVariants(); return (0 until n).map { v[listOf(0, 0, 1, 0, 2)[it % 5]] } }

    /** Скан: 200 сторінок-зображень A4 300 dpi, власна копія потоку на сторінку. */
    fun scan200(): File = cached("scan200.pdf") { f ->
        val variants = jpegVariants().map { it.readBytes() }
        println("[gen] scan variants JPEG bytes: " + variants.joinToString { it.size.toString() })
        val pattern = intArrayOf(0, 0, 1, 0, 0, 1, 0, 2, 0, 1)
        val pages = 200
        RawPdf(f).use { w ->
            w.reserve(2)
            val kids = (0 until pages).joinToString(" ") { "${3 + it * 3} 0 R" }
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            w.obj(2, "<< /Type /Pages /Kids [$kids] /Count $pages >>")
            for (i in 0 until pages) {
                val p = w.alloc(); val c = w.alloc(); val im = w.alloc()
                // A4 595×842 pt
                w.obj(p, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents $c 0 R " +
                    "/Resources << /XObject << /Im0 $im 0 R >> >> >>")
                w.streamObj(c, "", "q 595 0 0 842 0 0 cm /Im0 Do Q".toByteArray())
                val jpg = variants[pattern[i % pattern.size]]
                w.streamObj(im, "/Type /XObject /Subtype /Image /Width 2480 /Height 3508 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpg)
            }
            w.finish(1)
        }
    }

    fun big60(): File = cached("big60.pdf") { blobDoc(it, 60 * MB, 30, 11) }
    fun big100(): File = cached("big100.pdf") { blobDoc(it, 100 * MB, 50, 12) }
    fun pages500(): File = cached("pages500.pdf") { textDoc(it, 520, 25) }
    fun pages2000(): File = cached("pages2000.pdf") { textDoc(it, 2000, 40) }

    /** 12 PDF-сторінок звичайного розміру: основа для пошкоджень і малих перевірок. */
    fun valid10(): File = cached("valid10.pdf") { textDoc(it, 10, 20) }

    /** Сторінки з /Rotate 0/90/180/270 і зсунутим CropBox, MediaBox із ненульовим початком. */
    fun rotCrop(): File = cached("rotcrop.pdf") {
        textDoc(it, 12, 20, mediaBox = "[-20 -30 615 812]", pageExtra = { i ->
            "/Rotate ${(i % 4) * 90} /CropBox [${10 + i * 3} ${15 + i * 2} ${500 + i * 3} ${700 + i * 2}]"
        })
    }

    /** Глибоке дерево сторінок: бінарне дерево глибиною [depth] (2^depth сторінок). */
    fun deepTree(depth: Int = 8): File = cached("deeptree$depth.pdf") { f ->
        RawPdf(f).use { w ->
            val total = 1 shl depth
            // Нумерація: 1 каталог, 2 корінь; решта виділяється рекурсивно
            w.reserve(2)
            val font = w.alloc()
            w.obj(font, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            data class Node(val num: Int, val lines: String)
            val written = ArrayList<Pair<Int, String>>()
            fun build(num: Int, parent: Int, d: Int, first: Int): Int {
                if (d == 0) {
                    val c = w.alloc()
                    written.add(num to "<< /Type /Page /Parent $parent 0 R /MediaBox [0 0 595 842] /Contents $c 0 R /Resources << /Font << /F1 $font 0 R >> >> >>")
                    written.add(-c to "")
                    contents[c] = pageText(first, 10)
                    return 1
                }
                val l = w.alloc(); val r = w.alloc()
                val cl = build(l, num, d - 1, first)
                val cr = build(r, num, d - 1, first + cl)
                written.add(num to "<< /Type /Pages /Parent $parent 0 R /Kids [$l 0 R $r 0 R] /Count ${cl + cr} >>")
                return cl + cr
            }
            val l = w.alloc(); val r = w.alloc()
            val cl = build(l, 2, depth - 1, 0)
            val cr = build(r, 2, depth - 1, cl)
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            w.obj(2, "<< /Type /Pages /Kids [$l 0 R $r 0 R] /Count ${cl + cr} >>")
            for ((num, body) in written) {
                if (num < 0) w.streamObj(-num, "/Filter /FlateDecode", flate(contents[-num]!!)) else w.obj(num, body)
            }
            check(cl + cr == total)
            w.finish(1)
            contents.clear()
        }
    }
    private val contents = HashMap<Int, ByteArray>()

    // ---------------------------------------------------------------- PdfBox-генератори (форми, шрифти, шифрування)

    private fun addFormPages(doc: PDDocument, pages: Int, textN: Int, checkN: Int, comboN: Int, listN: Int, prefix: String = "") {
        val acro = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acro
        val res = PDResources()
        res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        acro.defaultResources = res
        acro.defaultAppearance = "/Helv 10 Tf 0 g"
        val pageList = (0 until pages).map { PDPage(PDRectangle.A4).also { p -> doc.addPage(p) } }
        var slot = 0
        fun place(): Pair<PDPage, PDRectangle> {
            val page = pageList[(slot / 30) % pages]
            val k = slot % 30
            slot++
            return page to PDRectangle(40f + (k % 3) * 180f, 780f - (k / 3) * 70f, 150f, 22f)
        }
        for (i in 0 until textN) {
            val (page, rect) = place()
            val tf = PDTextField(acro); tf.partialName = "${prefix}t_$i"
            if (i % 4 == 0) tf.isMultiline = true
            tf.value = "v$i"
            tf.widgets[0].rectangle = rect; tf.widgets[0].page = page
            page.annotations.add(tf.widgets[0]); acro.fields.add(tf)
        }
        for (i in 0 until checkN) {
            val (page, rect) = place()
            val cb = PDCheckBox(acro); cb.partialName = "${prefix}c_$i"
            val widget = cb.widgets[0]
            widget.rectangle = PDRectangle(rect.lowerLeftX, rect.lowerLeftY, 16f, 16f); widget.page = page
            if (i % 2 == 0) { // половина з /AP /N, половина без (M1)
                val ap = PDAppearanceDictionary()
                val sub = COSDictionary()
                sub.setItem(COSName.getPDFName("Yes"), PDAppearanceStream(doc))
                sub.setItem(COSName.Off, PDAppearanceStream(doc))
                ap.normalAppearance = PDAppearanceEntry(sub)
                widget.appearance = ap
            }
            page.annotations.add(widget); acro.fields.add(cb)
            if (i % 2 == 0) cb.unCheck()
        }
        for (i in 0 until comboN) {
            val (page, rect) = place()
            val cb = PDComboBox(acro); cb.partialName = "${prefix}cmb_$i"
            cb.options = listOf("one", "two", "three", "four")
            if (i % 3 == 0) cb.isEdit = true
            cb.setValue("two")
            cb.widgets[0].rectangle = rect; cb.widgets[0].page = page
            page.annotations.add(cb.widgets[0]); acro.fields.add(cb)
        }
        for (i in 0 until listN) {
            val (page, rect) = place()
            val lb = PDListBox(acro); lb.partialName = "${prefix}lst_$i"
            lb.options = listOf("alpha", "beta", "gamma")
            lb.widgets[0].rectangle = rect; lb.widgets[0].page = page
            page.annotations.add(lb.widgets[0]); acro.fields.add(lb)
        }
    }

    /** 20 сторінок, 600 полів (Text 200 [кожне 4-те багаторядкове], CheckBox 200, Combo 100, List 100). */
    fun forms600(): File = cached("forms600.pdf") { f ->
        PDDocument().use { doc -> addFormPages(doc, 20, 200, 200, 100, 100); doc.save(f) }
    }

    /** 120 вбудованих шрифтів-підмножин Roboto + стандартні; по сторінці на шрифт. */
    fun fonts120(fontFile: File): File = cached("fonts120.pdf") { f ->
        PDDocument().use { doc ->
            for (i in 0 until 120) {
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                val font = PDType0Font.load(doc, fontFile.inputStream(), true)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(font, 14f); cs.newLineAtOffset(50f, 750f)
                    cs.showText("Font ${i + 1} sample ABC xyz 123"); cs.endText()
                    cs.beginText(); cs.setFont(PDType1Font.TIMES_ROMAN, 12f); cs.newLineAtOffset(50f, 700f)
                    cs.showText("Times line"); cs.endText()
                }
            }
            doc.save(f)
        }
    }

    enum class Algo(val keyLen: Int, val aes: Boolean) { RC4_40(40, false), RC4_128(128, false), AES_128(128, true), AES_256(256, true) }

    fun encryptedName(a: Algo, userPw: Boolean, restricted: Boolean) =
        "enc_${a.name}_${if (userPw) "upw" else "nopw"}_${if (restricted) "restr" else "all"}.pdf"

    /** Зашифрований малий документ (15 сторінок, 8 полів) з/без user-пароля, з/без обмежень прав. */
    fun encrypted(a: Algo, userPw: Boolean, restricted: Boolean): File = cached(encryptedName(a, userPw, restricted)) { f ->
        PDDocument().use { doc ->
            addFormPages(doc, 15, 3, 2, 2, 1)
            val ap = AccessPermission()
            if (restricted) {
                ap.setCanModify(false); ap.setCanFillInForm(false); ap.setCanModifyAnnotations(false)
                ap.setCanExtractContent(false); ap.setCanPrint(false)
            }
            val pol = StandardProtectionPolicy(OWNER_PW, if (userPw) USER_PW else "", ap)
            pol.encryptionKeyLength = a.keyLen; pol.isPreferAES = a.aes
            doc.protect(pol)
            doc.save(f)
        }
    }

    /** ≈ 60 МБ, AES-256, user-пароль: збереження копією зашифрованого великого файлу. */
    fun encryptedBig(): File = cached("enc_big60_aes256.pdf") { f ->
        PDDocument.load(big60()).use { doc ->
            val pol = StandardProtectionPolicy(OWNER_PW, USER_PW, AccessPermission())
            pol.encryptionKeyLength = 256; pol.isPreferAES = true
            doc.protect(pol)
            doc.save(f)
        }
    }

    // ---------------------------------------------------------------- Пошкоджені

    /** Усі пошкоджені файли; значення — генератор у файл. */
    fun corruptSet(): Map<String, File> {
        val m = LinkedHashMap<String, File>()
        fun add(name: String, build: (File) -> Unit) { m[name] = cached("bad_$name.pdf", build) }
        val base = valid10()
        val bytes by lazy { base.readBytes() }

        add("truncated50") { it.writeBytes(bytes.copyOf(bytes.size / 2)) }
        add("truncated_notail") { it.writeBytes(bytes.copyOf(bytes.size - 120)) }
        add("header_only") { it.writeBytes("%PDF-1.7\n".toByteArray()) }
        add("empty0") { it.writeBytes(ByteArray(0)) }
        add("not_pdf_text") { it.writeText("This is not a PDF file.\n".repeat(50)) }
        add("not_pdf_random") { f -> f.writeBytes(ByteArray(1_000_000).also { Random(5).nextBytes(it) }) }
        add("bad_xref_offsets") { f -> textDoc(f, 10, 20).also { } ; brokenXref(f) }
        add("bad_startxref") { f ->
            val s = String(bytes, Charsets.ISO_8859_1)
            val i = s.lastIndexOf("startxref")
            f.writeBytes((s.substring(0, i) + "startxref\n999999999\n%%EOF\n").toByteArray(Charsets.ISO_8859_1))
        }
        add("bad_trailer_root") { f ->
            val s = String(bytes, Charsets.ISO_8859_1)
            f.writeBytes(s.replace("/Root 1 0 R", "/Root 777 0 R").toByteArray(Charsets.ISO_8859_1))
        }
        add("no_trailer") { f -> RawPdfNoTrailer.write(f) }
        add("count_9999") { textDoc(it, 10, 20, countOverride = 9999) }
        add("count_0") { textDoc(it, 10, 20, countOverride = 0) }
        add("selfref_tree") { f ->
            RawPdf(f).use { w ->
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                w.obj(2, "<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 /Parent 2 0 R >>")
                w.obj(3, "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 595 842] >>")
                w.finish(1)
            }
        }
        add("pages_cycle") { f ->
            RawPdf(f).use { w ->
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                w.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                w.obj(3, "<< /Type /Pages /Kids [2 0 R] /Count 1 /Parent 2 0 R >>")
                w.finish(1)
            }
        }
        add("deep_tree_5000") { f ->
            RawPdf(f).use { w ->
                val depth = 5000
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                for (i in 0 until depth) {
                    val n = 2 + i
                    w.obj(n, "<< /Type /Pages /Kids [${n + 1} 0 R] /Count 1 ${if (i > 0) "/Parent ${n - 1} 0 R" else ""} >>")
                }
                val leaf = 2 + depth
                w.obj(leaf, "<< /Type /Page /Parent ${leaf - 1} 0 R /MediaBox [0 0 595 842] >>")
                w.finish(1)
            }
        }
        add("deep_array_100000") { f ->
            RawPdf(f).use { w ->
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R /Junk 4 0 R >>")
                w.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                w.obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>")
                w.obj(4, "[".repeat(100_000) + "]".repeat(100_000))
                w.finish(1)
            }
        }
        add("flate_bomb_1g_content") { f -> bombDoc(f, gib = 1) }
        add("flate_bomb_image") { f ->
            // Заявлено 60000×60000 ×1 байт = 3,6 ГБ нулів у Flate-потоці
            RawPdf(f).use { w ->
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                w.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                w.obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /XObject << /Im0 5 0 R >> >> >>")
                w.streamObj(4, "", "q 595 0 0 842 0 0 cm /Im0 Do Q".toByteArray())
                val total = 60000L * 60000L
                val comp = zerosDeflated(total)
                w.streamObj(5, "/Type /XObject /Subtype /Image /Width 60000 /Height 60000 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", comp.size.toLong()) { it.write(comp) }
                w.finish(1)
            }
        }
        add("mediabox_1e9") { textDoc(it, 10, 20, mediaBox = "[0 0 1000000000 1000000000]") }
        add("mediabox_float_overflow") { textDoc(it, 10, 20, mediaBox = "[0 0 3e38 3e38]".replace("3e38", "340000000000000000000000000000000000000")) }
        add("mediabox_zero") { textDoc(it, 10, 20, mediaBox = "[0 0 0 0]") }
        add("mediabox_negative") { textDoc(it, 10, 20, mediaBox = "[600 800 -100 -100]") }
        return m
    }

    private fun brokenXref(f: File) {
        val s = String(f.readBytes(), Charsets.ISO_8859_1)
        val i = s.lastIndexOf("xref\n0 ")
        // нулюємо всі зсуви в таблиці xref
        val head = s.substring(0, i)
        val tail = s.substring(i).replace(Regex("\\d{10} 00000 n "), "0000000000 00000 n ")
        f.writeBytes((head + tail).toByteArray(Charsets.ISO_8859_1))
    }

    private object RawPdfNoTrailer {
        fun write(f: File) {
            RawPdf(f).use { w ->
                w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
                w.obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                w.obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>")
                w.finish(1, noTrailer = true)
            }
        }
    }

    private fun zerosDeflated(total: Long): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val d = Deflater(9)
        DeflaterOutputStream(bos, d, 1 shl 16).use { os ->
            val z = ByteArray(1 shl 20)
            var left = total
            while (left > 0) { val k = minOf(left, z.size.toLong()).toInt(); os.write(z, 0, k); left -= k }
        }
        return bos.toByteArray()
    }

    /** Вміст сторінки 0 — Flate-потік, що розпаковується в [gib] ГіБ пробілів. */
    private fun bombDoc(f: File, gib: Int) {
        val comp = zerosDeflated(gib * 1024L * MB).also { println("[gen] bomb ${gib}GiB compressed=${it.size}") }
        RawPdf(f).use { w ->
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            w.obj(2, "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
            w.obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>")
            w.streamObj(4, "/Filter /FlateDecode", comp.size.toLong()) { it.write(comp) }
            w.obj(5, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>")
            w.finish(1)
        }
    }
}
