package com.tadkeera.pdfconverter.util

import java.text.Normalizer

/**
 * Text helpers: Arabic/bidi normalisation, file-name sanitising,
 * page-number / keyword parsing. Mirrors the desktop engine.
 */
object TextUtils {

    private val ARABIC_RANGE = Regex("[\u0600-\u06FF]")

    private val COMMON_AR_WORDS = listOf(
        "اسم", "التاجر", "الوكيل", "المورد", "المستورد", "شركة", "مؤسسة", "فاتورة",
        "رقم", "تاريخ", "الكمية", "الإجمالي", "السعر", "الصنف", "الشهر", "القيمة",
        "عدد", "بضائع", "العميل", "التجارة", "العامة", "الدفع", "نقدا", "شروط",
        "الملخص", "ملخص", "جدول", "يناير", "فبراير", "مارس", "أبريل", "مايو",
        "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
        "البيان", "البيانات", "المجموع", "الصافي", "الضريبة", "الخصم"
    )

    fun containsArabic(text: String): Boolean = ARABIC_RANGE.containsMatchIn(text)

    /**
     * PDFBox / MuPDF often return Arabic in visual order with presentation-form
     * glyphs (U+FB50..). NFKC maps them back to base letters; we then reverse
     * the line when it is clearly RTL and repair digit runs.
     */
    fun toLogical(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace("\u00a0", " ")
        if (!containsArabic(s)) return s.trim()
        val rev = s.reversed()

        fun score(t: String): Int = COMMON_AR_WORDS.count { t.contains(it) }

        if (s.trimStart().startsWith(":") || score(rev) > score(s)) {
            return fixNumberRuns(rev).trim()
        }
        return s.trim()
    }

    /** After a whole-line reversal, mirrored digit runs ("100-4202") are fixed. */
    fun fixNumberRuns(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val isDigit = c.isDigit() && (i == 0 || !text[i - 1].isLetter())
            if (isDigit) {
                var j = i
                while (j < text.length &&
                    (text[j].isDigit() || text[j] in ".,:/-") &&
                    (j + 1 >= text.length || !text[j + 1].isLetter())
                ) j++
                sb.append(text.substring(i, j).reversed())
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun sanitizeFileName(name: String?, fallback: String = "output"): String {
        var n = name ?: return fallback
        n = n.replace(Regex("[\\\\/:*?\"<>|\r\n\t]+"), "_").trim().trim('.')
        n = n.replace(Regex("\\s+"), " ")
        if (n.isEmpty() || n.equals("con", true) || n.equals("prn", true) ||
            n.equals("aux", true) || n.equals("nul", true)
        ) return fallback
        if (n.length > 90) n = n.substring(0, 90)
        return n
    }

    fun sanitizeSheetName(name: String?, fallback: String = "ورقة"): String {
        var n = name ?: ""
        n = n.replace(Regex("[\\\\/?*\\[\\]:]"), "_").trim()
        n = n.replace(Regex("\\s+"), " ")
        if (n.isEmpty()) n = fallback
        if (n.length > 31) n = n.substring(0, 31)
        return n
    }

    /** Parse "1,3,5-9" into a set of page numbers. */
    fun parseSkipNumbers(spec: String?): Set<Int> {
        val out = mutableSetOf<Int>()
        if (spec.isNullOrBlank()) return out
        for (part in spec.split(",")) {
            val p = part.trim()
            val range = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(p)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                for (v in a..b) out.add(v)
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    fun parseKeywords(spec: String?): List<String> =
        spec?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
}
