package com.tadkeera.pdfconverter.convert

import com.tadkeera.pdfconverter.util.TextUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException

/**
 * Extracts text lines (with word x-positions) from a PDF page using PDFBox.
 * Word coordinates let us reconstruct table columns from whitespace gaps.
 */
object TextExtractor {

    data class Word(val x: Float, val text: String)

    data class Line(val words: List<Word>, val text: String)

    private class CharPos(val x: Float, val y: Float, val ch: String)

    private class Stripper : PDFTextStripper() {
        val chars = mutableListOf<CharPos>()

        @Throws(IOException::class)
        override fun writeString(text: String?, textPositions: List<TextPosition>?) {
            if (text.isNullOrEmpty() || textPositions == null) return
            val n = minOf(text.length, textPositions.size)
            for (i in 0 until n) {
                val tp = textPositions[i]
                chars.add(CharPos(tp.xDirAdj, tp.yDirAdj, text[i].toString()))
            }
        }
    }

    /** Extract lines from one page. Page index is 0-based. */
    @Throws(IOException::class)
    fun extractPage(doc: PDDocument, pageIndex: Int, pageCount: Int): List<Line> {
        val stripper = Stripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.sortByPosition = false
        stripper.getText(doc) // fills stripper.chars

        val pts = stripper.chars
        if (pts.isEmpty()) return emptyList()

        // cluster characters into lines (tolerance = 1.5x median char height)
        val sorted = pts.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = mutableListOf<MutableList<CharPos>>()
        for (p in sorted) {
            val cur = lines.lastOrNull()
            if (cur == null || kotlin.math.abs(cur.last().y - p.y) > 3.0f) {
                lines.add(mutableListOf(p))
            } else {
                cur.add(p)
            }
        }

        val result = mutableListOf<Line>()
        for (line in lines) {
            val ordered = line.sortedBy { it.x }
            // group into words by x gap
            val words = mutableListOf<Word>()
            var buf = StringBuilder()
            var lastX = Float.NaN
            var startX = 0f
            for (c in ordered) {
                if (buf.isEmpty()) {
                    startX = c.x
                    buf.append(c.ch)
                    lastX = c.x
                } else if (c.x - lastX > 3.5f) { // word gap
                    words.add(Word(startX, TextUtils.toLogical(buf.toString())))
                    startX = c.x
                    buf = StringBuilder(c.ch)
                    lastX = c.x
                } else {
                    buf.append(c.ch)
                    lastX = c.x
                }
            }
            if (buf.isNotEmpty()) words.add(Word(startX, TextUtils.toLogical(buf.toString())))

            val text = words.joinToString(" ") { it.text }.trim()
            if (text.isNotEmpty()) result.add(Line(words, text))
        }
        return result
    }
}
