package com.savoj.volumpdf.core.pdf

import android.content.Context
import android.net.Uri
import com.savoj.volumpdf.core.pdf.model.DocumentEditability
import com.savoj.volumpdf.core.pdf.model.PdfOperationResult
import com.savoj.volumpdf.core.pdf.model.PdfPermissionException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream

/** Спільні операції введення/виведення PDF для двигунів розмітки й форм. */
internal object PdfIo {

    fun openInputStream(context: Context, uri: Uri): InputStream {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Некоректний file URI: $uri")
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("Неможливо відкрити файл: $uri")
        }
    }

    /** Завантажує документ (PDFBox читає потік повністю, тому потік закривається тут). */
    fun loadDocument(context: Context, uri: Uri, password: String?): PDDocument =
        openInputStream(context, uri).use { stream ->
            if (password != null) PDDocument.load(stream, password) else PDDocument.load(stream)
        }

    /**
     * AcroForm документа. Стандартний `getAcroForm()` застосовує fixup, що перебудовує appearance полів при
     * NeedAppearances=true, і падає на деяких (багатоядкових) полях; тоді читаємо форму без fixup (F1).
     */
    fun acroForm(doc: PDDocument): PDAcroForm? = try {
        doc.documentCatalog.acroForm
    } catch (_: RuntimeException) {
        doc.documentCatalog.getAcroForm(null)
    }

    /** Права поточного відкриття: пароль власника дає все. */
    fun editability(doc: PDDocument): DocumentEditability {
        val ap = doc.currentAccessPermission
        return DocumentEditability(
            isEncrypted = doc.isEncrypted,
            canMarkup = ap.isOwnerPermission || ap.canModify(),
            canFillForms = ap.isOwnerPermission || ap.canFillInForm() || ap.canModifyAnnotations()
        )
    }

    /**
     * Політика R15 (CONTRACTS §1.5.1 п. 8): відмова при забороні зміни; зашифрований документ
     * не можна зберегти в той самий URI. Викликається одразу після завантаження, до будь-яких змін.
     */
    fun enforcePolicy(
        context: Context, doc: PDDocument, sourceUri: Uri, outputUri: Uri, needsModify: Boolean, needsFillForm: Boolean
    ) {
        val e = editability(doc)
        if (needsModify && !e.canMarkup) throw PdfPermissionException("MODIFY")
        if (needsFillForm && !e.canFillForms) throw PdfPermissionException("FILL_FORM")
        if (e.isEncrypted && isSameTarget(context, sourceUri, outputUri)) {
            throw IllegalArgumentException(
                "Зашифрований документ можна зберегти лише як нову копію, а не поверх оригіналу"
            )
        }
    }

    /**
     * Найкраща спроба визначити, чи два URI вказують на той самий файл (CONTRACTS §1.5.1 п. 8в).
     * file:// — canonical path і Files.isSameFile; content:// — authority + documentId
     * (для tree+document URI — id документа); якщо нормалізація неможлива — порівняння рядків.
     * Вихідний файл тут НЕ відкривається (відкриття SAF-виходу з truncate знищило б дані).
     */
    @Suppress("UNUSED_PARAMETER")
    fun isSameTarget(context: Context, a: Uri, b: Uri): Boolean {
        if (a == b || a.toString() == b.toString()) return true
        val sa = a.scheme
        val sb = b.scheme
        if (sa == "file" && sb == "file") {
            val pa = a.path
            val pb = b.path
            if (pa != null && pb != null) {
                try {
                    val fa = File(pa)
                    val fb = File(pb)
                    if (fa.canonicalFile == fb.canonicalFile) return true
                    if (fa.exists() && fb.exists() &&
                        java.nio.file.Files.isSameFile(fa.toPath(), fb.toPath())
                    ) return true
                    return false
                } catch (_: Exception) {
                    // нормалізація неможлива — нижче порівняння рядків
                }
            }
        } else if (sa == "content" && sb == "content") {
            try {
                val authA = a.authority?.lowercase()
                val authB = b.authority?.lowercase()
                val idA = documentIdOf(a)
                val idB = documentIdOf(b)
                if (authA != null && authB != null && idA != null && idB != null) {
                    return authA == authB && idA == idB
                }
            } catch (_: Exception) {
                // нормалізація неможлива — нижче порівняння рядків
            }
        }
        return a.toString() == b.toString()
    }

    /** Для tree+document URI повертає id документа (не дерева). */
    private fun documentIdOf(uri: Uri): String? = try {
        android.provider.DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        null
    }

    /**
     * PDFBox не зберігає завантажений зашифрований документ без політики захисту. Копія шифрується
     * паролем, яким відкрито джерело (user = owner), з повними правами: пароль власника й обмеження
     * прав не переносяться. Рівень алгоритму зберігається (RC4-128 → AES-128 допустимо).
     */
    fun keepEncryption(doc: PDDocument, password: String?) {
        if (!doc.isEncrypted) return
        val pw = password ?: ""
        val keyLength = doc.encryption?.length?.takeIf { it == 40 || it == 128 || it == 256 } ?: 128
        val policy = StandardProtectionPolicy(pw, pw, AccessPermission())
        policy.encryptionKeyLength = keyLength
        policy.isPreferAES = keyLength >= 128
        doc.protect(policy)
    }

    /** Збереження через проміжний файл: оригінал не пошкоджується, якщо збереження не вдалося. */
    fun saveSafely(context: Context, doc: PDDocument, outputUri: Uri) {
        if (outputUri.scheme == "file") {
            val path = outputUri.path ?: throw IllegalArgumentException("Некоректний file URI: $outputUri")
            val targetFile = File(path)
            val parentDir = targetFile.parentFile ?: context.cacheDir
            parentDir.mkdirs()
            val tempFile = File.createTempFile("volum_markup_", ".tmp", parentDir)
            try {
                doc.save(tempFile)
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        } else {
            val tempFile = File.createTempFile("volum_markup_saf_", ".tmp", context.cacheDir)
            try {
                doc.save(tempFile)
                val outStream = context.contentResolver.openOutputStream(outputUri, "wt")
                    ?: context.contentResolver.openOutputStream(outputUri)
                    ?: throw FileNotFoundException("Неможливо відкрити потік для запису: $outputUri")
                tempFile.inputStream().use { input ->
                    outStream.use { output -> input.copyTo(output) }
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    fun mapToPdfError(cause: Throwable, defaultMessage: String): PdfOperationResult.Error {
        val userMessage = when (cause) {
            is OutOfMemoryError -> "Файл завеликий для редагування"
            is PdfPermissionException -> cause.message ?: defaultMessage
            is InvalidPasswordException -> "Невірний пароль для захищеного PDF-документа"
            is IllegalArgumentException -> cause.localizedMessage ?: defaultMessage
            is IndexOutOfBoundsException -> cause.localizedMessage ?: defaultMessage
            is FileNotFoundException -> cause.localizedMessage ?: defaultMessage
            else -> "$defaultMessage: ${cause.localizedMessage ?: cause.javaClass.simpleName}"
        }
        return PdfOperationResult.Error(cause = cause, userMessage = userMessage)
    }
}
