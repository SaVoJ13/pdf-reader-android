package com.savoj.volumpdf.core.pdf.large

import com.savoj.volumpdf.core.pdf.MarkupTestSupport
import com.savoj.volumpdf.core.pdf.model.PdfOperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Результат одного виміряного виклику. */
data class Row(
    val case: String, val op: String, val ms: Long, val peakMb: Long, val baseMb: Long, val retainedMb: Long,
    val outBytes: Long, val outcome: String, val detail: String, val maxHeapMb: Long
)

/** Виміри часу й піку купи JVM (семплер у фоновому потоці; GC перед стартом). Цифри лише з виводу тестів. */
object Probe {
    @Volatile var currentClass: String = "unknown"
    private val rt = Runtime.getRuntime()
    private fun usedMb() = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    private val results = File("build/large-files-results").also { it.mkdirs() }

    fun gc() { repeat(3) { System.gc(); Thread.sleep(30) } }

    /** Виконує [block], вимірює; повертає результат (або виняток) і рядок. */
    fun <T> measure(case: String, op: String, out: File?, block: () -> T): Pair<Result<T>, Row> {
        // Маркер на випадок смерті JVM (OOM у воркері): без наступного фінального рядка = JVM_DIED
        record(Row(case, op, 0, 0, 0, 0, -1, "STARTED", "", 0), currentClass)
        gc()
        val base = usedMb()
        val peak = java.util.concurrent.atomic.AtomicLong(base)
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (run.get()) { val u = usedMb(); if (u > peak.get()) peak.set(u); try { Thread.sleep(4) } catch (_: InterruptedException) { break } }
        }.also { it.isDaemon = true; it.start() }
        val t0 = System.nanoTime()
        val res = try { Result.success(block()) } catch (t: Throwable) { Result.failure(t) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        run.set(false); sampler.join(200)
        val u = usedMb(); if (u > peak.get()) peak.set(u)
        gc()
        val retained = usedMb()
        val outBytes = out?.takeIf { it.exists() }?.length() ?: -1
        val (outcome, detail) = classify(res)
        val row = Row(case, op, ms, peak.get(), base, retained, outBytes, outcome, detail, rt.maxMemory() / (1024 * 1024))
        println("[RESULT] ${row.case} | ${row.op} | ${row.ms} ms | peak ${row.peakMb} MB (base ${row.baseMb}, retained ${row.retainedMb}) | out ${row.outBytes} B | ${row.outcome} | ${row.detail}")
        return res to row
    }

    private fun <T> classify(res: Result<T>): Pair<String, String> {
        res.exceptionOrNull()?.let { return "CRASH" to "${it.javaClass.simpleName}: ${(it.message ?: "").take(120)} at ${it.stackTrace.filter { s -> s.className.contains("tom_roush") || s.className.contains("volumpdf") }.take(4).joinToString(" < ") { s -> s.className.substringAfterLast(".") + "." + s.methodName + ":" + s.lineNumber }}" }
        return when (val v = res.getOrNull()) {
            is PdfOperationResult.Success -> "OK" to "pages=${v.pagesCount}"
            is PdfOperationResult.Error -> "Error" to "msg='${v.userMessage.take(100)}' cause=${v.cause.javaClass.simpleName} at ${v.cause.stackTrace.filter { it.className.contains("tom_roush") || it.className.contains("volumpdf") }.take(4).joinToString(" < ") { it.className.substringAfterLast(".") + "." + it.methodName + ":" + it.lineNumber }}"
            null -> "null" to ""
            else -> "OK" to v.toString().take(80)
        }
    }

    fun record(row: Row, klass: String) {
        File(results, "$klass.tsv").appendText(
            listOf(row.case, row.op, row.ms, row.peakMb, row.baseMb, row.retainedMb, row.outBytes, row.outcome, row.detail.replace('\t', ' '), row.maxHeapMb)
                .joinToString("\t") + "\n"
        )
    }
}

/** Спільна основа: контекст, вимірювання, перевірки поведінки, перевірка витоку файлів. */
abstract class LargeTestBase : MarkupTestSupport() {

    @get:Rule
    val timeout: Timeout = Timeout(300, TimeUnit.SECONDS)

    /** Очікування для кейсу. */
    enum class Expect { OK, ERROR, OK_OR_ERROR }

    @org.junit.Before fun setCurrentClass() { Probe.currentClass = javaClass.simpleName }

    protected fun klass(): String = javaClass.simpleName

    /** Витягує результат із PdfOperationResult/Flow для вимірювання. */
    protected fun flowLast(f: Flow<PdfOperationResult>): PdfOperationResult = runBlocking { f.toList().last() }

    /**
     * Виміряти й перевірити контракт: без необробленого винятку; Error має непорожній userMessage;
     * очікування [expect]; файл-джерело не тримається відкритим (перейменування можливе на Windows);
     * у каталозі виходу немає залишків тимчасових файлів.
     */
    protected fun <T> probe(
        case: String, op: String, out: File? = null, expect: Expect = Expect.OK_OR_ERROR,
        sources: List<File> = emptyList(), block: () -> T
    ): Pair<Result<T>, Row> {
        val (res, row) = Probe.measure(case, op, out, block)
        Probe.record(row, klass())
        val leaks = leakReport(sources, out)
        if (leaks.isNotEmpty()) {
            Probe.record(row.copy(op = "$op [LEAK]", outcome = "LEAK", detail = leaks), klass())
        }
        res.exceptionOrNull()?.let { throw AssertionError("[$case/$op] необроблений виняток: $it", it) }
        val v = res.getOrNull()
        if (v is PdfOperationResult.Error) {
            org.junit.Assert.assertTrue("[$case/$op] Error без userMessage", v.userMessage.isNotBlank())
            if (v.cause is OutOfMemoryError) org.junit.Assert.assertEquals("[$case/$op] OOM має давати повідомлення про завеликий файл", "Файл завеликий для редагування", v.userMessage)
        }
        when (expect) {
            Expect.OK -> org.junit.Assert.assertTrue("[$case/$op] очікувався успіх, отримано ${row.outcome}: ${row.detail}", row.outcome == "OK")
            Expect.ERROR -> org.junit.Assert.assertTrue("[$case/$op] очікувався Error, отримано ${row.outcome}: ${row.detail}", row.outcome == "Error")
            Expect.OK_OR_ERROR -> Unit
        }
        org.junit.Assert.assertTrue("[$case/$op] витік файлів/тимчасових файлів: $leaks", leaks.isEmpty())
        return res to row
    }

    /** Файл, що ще відкритий, на Windows не можна перемістити; на інших ОС перевірка слабша. */
    private fun leakReport(sources: List<File>, out: File?): String {
        val sb = StringBuilder()
        for (s in sources + listOfNotNull(out)) {
            if (!s.exists()) continue
            val tmp = File(s.parentFile, s.name + ".leakcheck")
            try {
                Files.move(s.toPath(), tmp.toPath(), StandardCopyOption.ATOMIC_MOVE)
                Files.move(tmp.toPath(), s.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                sb.append("не переміщується ${s.name}: ${e.javaClass.simpleName}; ")
                if (tmp.exists() && !s.exists()) tmp.renameTo(s)
            }
        }
        val dirs = listOfNotNull(out?.parentFile, tempFolder.root).distinct()
        for (d in dirs) d.listFiles { f -> f.name.startsWith("volum_markup_") && f.name.endsWith(".tmp") }
            ?.forEach { sb.append("залишився тимчасовий ${it.name}; ") }
        return sb.toString()
    }

    protected fun newOut(name: String): File = File(tempFolder.root, name)
}
