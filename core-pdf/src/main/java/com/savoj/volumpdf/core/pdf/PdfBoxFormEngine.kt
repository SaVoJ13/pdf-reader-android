package com.savoj.volumpdf.core.pdf

import android.content.Context
import android.net.Uri
import com.savoj.volumpdf.core.pdf.model.FormField
import com.savoj.volumpdf.core.pdf.model.FormFieldUpdate
import com.savoj.volumpdf.core.pdf.model.PdfOperationResult
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Реалізація [FormEngine] на основі бібліотеки PdfBox-Android (Apache-2.0).
 * Забезпечує читання інтерактивних полів форми AcroForm (Text, CheckBox, Choice/ComboBox)
 * та запис оновлених значень з безпечним збереженням для SAF.
 * Типи полів поза контрактом (радіо, кнопки, підпис) пропускаються без помилки.
 */
class PdfBoxFormEngine : FormEngine {

    override suspend fun extractFormFields(
        context: Context,
        sourceUri: Uri,
        password: String?
    ): List<FormField> = withContext(Dispatchers.IO) {
        PdfBoxPageOperationsEngine.init(context)

        val doc = PdfIo.loadDocument(context, sourceUri, password)
        try {
            val acroForm = PdfIo.acroForm(doc) ?: return@withContext emptyList()
            val fieldsList = mutableListOf<FormField>()

            val iterator = try {
                acroForm.fieldTree.iterator()
            } catch (_: Throwable) {
                acroForm.fields?.iterator() ?: emptyList<PDField>().iterator()
            }

            while (iterator.hasNext()) {
                val field = try {
                    iterator.next()
                } catch (_: Throwable) {
                    break
                }
                val fqName = field.fullyQualifiedName ?: continue
                val partialName = field.partialName ?: fqName
                val alternateTitle = field.alternateFieldName
                val isReadOnly = field.isReadOnly

                when (field) {
                    is PDTextField -> {
                        fieldsList.add(
                            FormField.Text(
                                fullyQualifiedName = fqName,
                                partialName = partialName,
                                alternateTitle = alternateTitle,
                                isReadOnly = isReadOnly,
                                value = field.value ?: "",
                                isMultiline = field.isMultiline
                            )
                        )
                    }
                    is PDCheckBox -> {
                        fieldsList.add(
                            FormField.CheckBox(
                                fullyQualifiedName = fqName,
                                partialName = partialName,
                                alternateTitle = alternateTitle,
                                isReadOnly = isReadOnly,
                                isChecked = field.isChecked,
                                onValue = field.onValue?.takeIf { it.isNotEmpty() } ?: "Yes"
                            )
                        )
                    }
                    is PDChoice -> {
                        // Значення — зі списку field.value (valueAsString для порожнього списку дав би "[]");
                        // порожнє/відсутнє → null; для multi-select (поза MVP) — перший елемент.
                        val selected = field.value?.firstOrNull()?.takeIf { it.isNotEmpty() }
                        val isEditableCombo = field is PDComboBox && field.isEdit
                        fieldsList.add(
                            FormField.Choice(
                                fullyQualifiedName = fqName,
                                partialName = partialName,
                                alternateTitle = alternateTitle,
                                isReadOnly = isReadOnly,
                                options = field.options ?: emptyList(),
                                selectedValue = selected,
                                isEditableCombo = isEditableCombo
                            )
                        )
                    }
                }
            }

            fieldsList
        } finally {
            doc.close()
        }
    }

    override suspend fun fillFormFields(
        context: Context,
        sourceUri: Uri,
        updates: List<FormFieldUpdate>,
        outputUri: Uri,
        password: String?
    ): PdfOperationResult = withContext(Dispatchers.IO) {
        try {
            PdfBoxPageOperationsEngine.init(context)

            val doc = PdfIo.loadDocument(context, sourceUri, password)
            try {
                PdfIo.enforcePolicy(context, doc, sourceUri, outputUri, needsModify = false, needsFillForm = updates.isNotEmpty())
                val totalPages = doc.numberOfPages
                FormFiller.apply(doc, updates)
                PdfIo.keepEncryption(doc, password)
                PdfIo.saveSafely(context, doc, outputUri)
                PdfOperationResult.Success(outputUri = outputUri, pagesCount = totalPages)
            } finally {
                doc.close()
            }
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            PdfIo.mapToPdfError(cause, "Помилка заповнення форми PDF")
        }
    }
}

/** Заповнення полів AcroForm у вже завантаженому документі (спільне для FormEngine і MarkupEngine). */
internal object FormFiller {

    /**
     * Застосовує оновлення до полів Text, CheckBox, Choice; радіо, кнопки, підпис
     * і невідомі імена пропускаються без помилки.
     * Choice: усі значення перевіряються ДО першого запису (атомарність); значення пишеться напряму
     * в /V (для списку ще /I) без побудови appearance шрифтом DA; відображення — через NeedAppearances.
     */
    fun apply(doc: PDDocument, updates: List<FormFieldUpdate>) {
        val acroForm = PdfIo.acroForm(doc)
        if (acroForm == null) {
            if (updates.isNotEmpty()) {
                throw IllegalArgumentException("Документ не містить форми AcroForm для заповнення")
            }
            return
        }

        // 1. Валідація Choice до будь-яких змін
        for (update in updates) {
            val field = acroForm.getField(update.fullyQualifiedName) as? PDChoice ?: continue
            validateChoice(field, update)
        }

        // 2. Запис
        for (update in updates) {
            val field = acroForm.getField(update.fullyQualifiedName) ?: continue

            when (field) {
                is PDTextField -> setValueTolerant(field, update.value.toString()) { field.setValue(it) }
                is PDCheckBox -> {
                    if (isTruthy(update.value, field.onValue)) {
                        try {
                            field.check()
                        } catch (_: Throwable) {
                            field.setValue(field.onValue?.takeIf { it.isNotEmpty() } ?: "Yes")
                        }
                    } else {
                        try {
                            field.unCheck()
                        } catch (_: Throwable) {
                            field.setValue(COSName.Off.name)
                        }
                    }
                }
                is PDChoice -> writeChoice(field, update.value.toString())
                else -> Unit // радіо, кнопки, підпис: поза контрактом
            }
        }

        try {
            acroForm.needAppearances = true
        } catch (_: Throwable) {
            // Не критично
        }
        // Appearance — по одному полю: збій одного не скасовує решту. Choice не чіпаємо (DA/Helvetica без кирилиці).
        val terminals = try {
            acroForm.fieldTree.filterIsInstance<PDTerminalField>().filter { it !is PDChoice }
        } catch (_: Throwable) {
            emptyList()
        }
        for (f in terminals) {
            try {
                acroForm.refreshAppearances(listOf<PDField>(f))
            } catch (_: Throwable) {
                // Не вдалося згенерувати AP цього поля; решта продовжується
            }
        }
    }

    private fun isFreeEntry(field: PDChoice): Boolean = field is PDComboBox && field.isEdit

    private fun validateChoice(field: PDChoice, update: FormFieldUpdate) {
        if (isFreeEntry(field)) return
        val value = update.value.toString()
        if (value.isEmpty()) return // очищення вибору
        if (value !in (field.options ?: emptyList())) {
            throw IllegalArgumentException(
                "Значення «$value» відсутнє у списку варіантів поля «${update.fullyQualifiedName}»"
            )
        }
    }

    private fun writeChoice(field: PDChoice, value: String) {
        val dict = field.cosObject
        if (value.isEmpty() && !isFreeEntry(field)) {
            dict.removeItem(COSName.V)
            dict.removeItem(COSName.I)
            return
        }
        dict.setString(COSName.V, value)
        val index = (field.options ?: emptyList()).indexOf(value)
        if (field !is PDComboBox && index >= 0) {
            dict.setItem(COSName.I, COSArray().also { it.add(COSInteger.get(index.toLong())) })
        } else {
            dict.removeItem(COSName.I)
        }
    }

    /**
     * PDFBox ставить значення, а потім будує appearance шрифтом з DA поля; якщо в тому шрифті немає
     * символа (кирилиця в Helvetica), кидає IllegalArgumentException уже ПІСЛЯ запису значення.
     * Тоді значення лишається, а справжні помилки значення передаються далі.
     */
    private fun setValueTolerant(field: PDField, value: String, set: (String) -> Unit) {
        try {
            set(value)
        } catch (e: RuntimeException) { // IllegalArgumentException (шрифт) і StringIndexOutOfBounds (багаторядкове поле, F1)
            if (field.valueAsString != value) throw e
        }
    }

    private fun isTruthy(value: Any, onValue: String?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) ||
                    value.equals("yes", ignoreCase = true) ||
                    value.equals("1", ignoreCase = true) ||
                    value.equals("on", ignoreCase = true) ||
                    (!onValue.isNullOrEmpty() && value.equals(onValue, ignoreCase = true))
            else -> false
        }
    }
}
