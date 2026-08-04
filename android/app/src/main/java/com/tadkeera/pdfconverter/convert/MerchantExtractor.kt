package com.tadkeera.pdfconverter.convert

import com.tadkeera.pdfconverter.util.TextUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.regex.Pattern

/**
 * Best-effort extraction of the merchant / agent name from the first pages
 * of a PDF. Mirrors the desktop extractor.
 */
object MerchantExtractor {

    private val LABELS = listOf(
        "اسم\\s*التاجر", "اسم\\s*الوكيل", "اسم\\s*المورد", "اسم\\s*المستورد",
        "اسم\\s*الشركة", "اسم\\s*العميل", "اسم\\s*المتعامل", "الاسم\\s*التجاري",
        "اسم\\s*المؤسسة", "اسم\\s*المنشأة", "اسم\\s*المدير", "اسم\\s*صاحب",
        "تاجر", "وكيل", "مورد", "مستورد", "المستورد", "الشركة", "الاسم",
        "بيان\\s*الشركة", "جهة\\s*التوريد",
        "merchant\\s*name", "agent\\s*name", "trader\\s*name", "importer",
        "supplier\\s*name", "company\\s*name", "trade\\s*name", "dealer\\s*name",
        "merchant", "agent", "supplier", "consignee", "buyer", "customer",
        "company", "business\\s*name"
    )

    private val LABEL_RE = Pattern.compile(LABELS.joinToString("|"), Pattern.CASE_INSENSITIVE)

    fun extract(doc: PDDocument, firstPages: Int = 5): String? {
        val pageCount = doc.numberOfPages
        val limit = minOf(firstPages, pageCount)

        for (i in 0 until limit) {
            val lines = runCatching { TextExtractor.extractPage(doc, i, pageCount) }
                .getOrDefault(emptyList())
            if (lines.isEmpty()) continue

            for (idx in lines.indices) {
                val raw = lines[idx].text
                val m = LABEL_RE.matcher(raw)
                if (!m.find()) continue
                val after = raw.substring(m.end()).trim().trimStart(':', '–', '—', '-')
                val value = cleanName(after)
                if (value != null) return value

                // lookahead lines
                for (k in 1..3) {
                    val ni = idx + k
                    if (ni >= lines.size) break
                    val nxt = lines[ni].text
                    if (nxt.isBlank()) continue
                    if (LABEL_RE.matcher(nxt).find() && nxt.length < 40) break
                    val v = cleanName(nxt)
                    if (v != null) return v
                }
            }
        }

        // fallback: first meaningful line of page 1
        val first = runCatching { TextExtractor.extractPage(doc, 0, pageCount) }
            .getOrDefault(emptyList())
        for (ln in first) {
            val t = ln.text.trim()
            if (t.isEmpty() || LABEL_RE.matcher(t).find()) continue
            if (t.matches(Regex("[\\d\\s\\W]+"))) continue
            val v = cleanName(t)
            if (v != null && v.length in 3..80) return v
        }
        return null
    }

    private fun cleanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var name = raw.trim().trimStart(':', '–', '—', '-', '\t', ' ').trim()
        name = name.replace(Regex("\\s+"), " ")
        name = name.trimEnd(':', '–', '—', '-', '\t', ' ').trim().trimEnd('.')
        if (name.isEmpty()) return null
        if (name.length > 80) return null
        if (name.matches(Regex("[\\W_]+"))) return null
        if (!name.any { it.isLetterOrDigit() }) return null
        return name
    }
}
