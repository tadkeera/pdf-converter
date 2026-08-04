package com.tadkeera.pdfconverter.convert

import android.content.Context
import android.net.Uri
import com.tadkeera.pdfconverter.util.TextUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.InputStream

data class ConvertOptions(
    val maxPages: Int = 1000,
    val skipNumbers: Set<Int> = emptySet(),
    val skipKeywords: List<String> = emptyList(),
    val skipBlank: Boolean = true,
    val manualName: String? = null,
    val autoName: Boolean = true
)

data class FileResult(
    val inputName: String,
    val outputName: String,
    val pagesConverted: Int,
    val pagesSkipped: Int,
    val merchant: String?,
    val error: String?,
    val outputPath: String = "",
    val outputUri: Uri? = null
)

/**
 * Converts one PDF stream into one .xlsx file, saved automatically inside
 * the "PDF CONVERTER" folder on the device's local storage.
 * pageCallback reports 1-based page numbers as they are processed.
 */
object Converter {

    fun convert(
        context: Context,
        stream: InputStream,
        inputName: String,
        options: ConvertOptions,
        pageCallback: (Int) -> Unit = {}
    ): FileResult {
        return try {
            PDDocument.load(stream).use { doc ->
                val merchant = options.manualName?.takeIf { it.isNotBlank() }
                    ?: if (options.autoName) MerchantExtractor.extract(doc) else null

                val sheets = mutableListOf<XlsxWriter.SheetData>()
                var skipped = 0
                val pageCount = doc.numberOfPages
                val total = minOf(pageCount, options.maxPages)

                for (i in 0 until total) {
                    val pageNo = i + 1
                    if (pageNo in options.skipNumbers) {
                        skipped++
                        continue
                    }

                    val lines = runCatching { TextExtractor.extractPage(doc, i, pageCount) }
                        .getOrDefault(emptyList())
                    val texts = lines.map { it.text }

                    // blank pages
                    if (options.skipBlank && texts.isEmpty()) {
                        skipped++
                        continue
                    }
                    // skip by keyword in title / first lines
                    if (options.skipKeywords.isNotEmpty()) {
                        val probe = texts.take(15).joinToString(" ").lowercase()
                        if (options.skipKeywords.any { probe.contains(it) }) {
                            skipped++
                            continue
                        }
                    }

                    val title = detectTitle(texts, pageNo)
                    val rows = buildRows(lines)
                    sheets.add(
                        XlsxWriter.SheetData(
                            title = TextUtils.sanitizeSheetName(title),
                            rows = rows,
                            isTable = rows.any { it.size > 1 },
                            pageNumber = pageNo
                        )
                    )
                    pageCallback(pageNo)
                }

                if (sheets.isEmpty()) {
                    return FileResult(inputName, "", 0, skipped, merchant, "لا توجد صفحات للتحويل")
                }

                val base = TextUtils.sanitizeFileName(
                    merchant,
                    fallback = inputName.removeSuffix(".pdf").ifBlank { "output" }
                )
                val fileName = "$base.xlsx"

                val sink = StorageHelper.createSink(context, fileName)
                    ?: return FileResult(
                        inputName, fileName, 0, skipped, merchant,
                        "تعذر إنشاء مجلد «PDF CONVERTER» في ذاكرة التخزين"
                    )

                try {
                    val out = StorageHelper.openOutput(context, sink)
                        ?: return FileResult(
                            inputName, fileName, sheets.size, skipped, merchant,
                            "تعذر فتح مجلد «PDF CONVERTER» في ذاكرة التخزين"
                        )
                    out.use { XlsxWriter.write(it, sheets, merchant) }
                    StorageHelper.markPublished(context, sink)
                } catch (e: Exception) {
                    StorageHelper.deleteSink(context, sink)
                    throw e
                }

                FileResult(
                    inputName, fileName, sheets.size, skipped, merchant, null,
                    outputPath = sink.displayPath,
                    outputUri = (sink as? StorageHelper.Sink.MediaStoreUri)?.uri
                )
            }
        } catch (e: Exception) {
            FileResult(inputName, "", 0, 0, null, e.message ?: "خطأ غير متوقع")
        }
    }

    private fun detectTitle(texts: List<String>, pageNo: Int): String {
        for (t in texts) {
            val c = t.trim()
            if (c.isEmpty() || c.length <= 2) continue
            if (c.length <= 60) return c
            break
        }
        return "Page $pageNo"
    }

    /**
     * If a line spreads across the page with a clear large gap it is treated as
     * a table row (word per cell); otherwise the whole line becomes one cell.
     */
    private fun buildRows(lines: List<TextExtractor.Line>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (line in lines) {
            val words = line.words
            if (words.size >= 2) {
                val span = words.last().x - words.first().x
                var maxGap = 0f
                for (k in 1 until words.size) {
                    maxGap = maxOf(maxGap, words[k].x - words[k - 1].x)
                }
                if (span > 120f && maxGap > 30f) {
                    rows.add(words.map { it.text })
                    continue
                }
            }
            rows.add(listOf(line.text))
        }
        return rows
    }
}
