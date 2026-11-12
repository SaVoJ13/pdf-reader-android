package com.savoj.volumpdf.core.pdf

import com.savoj.volumpdf.core.pdf.model.PdfPermissionException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Єдине відображення помилок у userMessage (OOM, права, пароль). */
class PdfIoErrorMappingTest {

    @Test
    fun outOfMemoryMapsToTooLargeMessage() {
        val e = PdfIo.mapToPdfError(OutOfMemoryError("Java heap space"), "Помилка")
        assertEquals("Файл завеликий для редагування", e.userMessage)
    }

    @Test
    fun permissionMapsToItsOwnMessage() {
        val e = PdfIo.mapToPdfError(PdfPermissionException("MODIFY"), "Помилка")
        assertEquals("Права документа забороняють зміну", e.userMessage)
    }

    @Test
    fun unknownErrorKeepsDefaultPrefix() {
        val e = PdfIo.mapToPdfError(IllegalStateException("x"), "Помилка")
        assertEquals("Помилка: x", e.userMessage)
    }
}
