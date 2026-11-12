package com.savoj.volumpdf.core.pdf.large

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Потоковий записувач «сирого» PDF: дозволяє будувати файли у сотні МБ без утримання їх у купі
 * і навмисно псувати структуру (чого PdfBox не дозволяє). Увесь вміст синтетичний.
 */
class RawPdf(val file: File, version: String = "1.7") : AutoCloseable {
    private class Counting(val inner: OutputStream) : OutputStream() {
        var pos = 0L
        override fun write(b: Int) { inner.write(b); pos++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); pos += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    private val out = Counting(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
    private val offsets = HashMap<Int, Long>()
    private var next = 1

    init {
        raw("%PDF-$version\n%âãÏÓ\n")
    }

    fun alloc(): Int = next++
    fun reserve(n: Int): Int { val first = next; next += n; return first }

    fun raw(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

    fun obj(n: Int, body: String) {
        offsets[n] = out.pos
        raw("$n 0 obj\n$body\nendobj\n")
    }

    /** Потік з відомою довжиною: [gen] має записати рівно [length] байтів. */
    fun streamObj(n: Int, dict: String, length: Long, gen: (OutputStream) -> Unit) {
        offsets[n] = out.pos
        raw("$n 0 obj\n<< $dict /Length $length >>\nstream\n")
        val before = out.pos
        gen(out)
        check(out.pos - before == length) { "Довжина потоку $n: очікувано $length, записано ${out.pos - before}" }
        raw("\nendstream\nendobj\n")
    }

    fun streamObj(n: Int, dict: String, data: ByteArray) =
        streamObj(n, dict, data.size.toLong()) { it.write(data) }

    fun lastOffset(): Long = out.pos

    /** Звичайний кінець файлу з коректною xref-таблицею. */
    fun finish(root: Int, extraTrailer: String = "", brokenXref: Boolean = false, noTrailer: Boolean = false) {
        if (noTrailer) { out.flush(); return }
        val xrefPos = out.pos
        val size = next
        val sb = StringBuilder()
        sb.append("xref\n0 $size\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1 until size) {
            val off = offsets[i]
            if (off == null) sb.append("0000000000 00000 f \n")
            else sb.append(String.format("%010d 00000 n \n", if (brokenXref) 0L else off))
        }
        sb.append("trailer\n<< /Size $size /Root $root 0 R $extraTrailer >>\nstartxref\n$xrefPos\n%%EOF\n")
        raw(sb.toString())
    }

    override fun close() { out.flush(); out.close() }
}

/** Стиснення Flate невеликого буфера. */
fun flate(data: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    DeflaterOutputStream(bos, Deflater(6)).use { it.write(data) }
    return bos.toByteArray()
}
