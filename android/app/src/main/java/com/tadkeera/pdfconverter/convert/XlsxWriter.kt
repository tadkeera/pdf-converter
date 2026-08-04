package com.tadkeera.pdfconverter.convert

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free .xlsx writer (OOXML is a zip of XML parts).
 * Produces a fully valid workbook that Excel / LibreOffice / Google Sheets open.
 */
object XlsxWriter {

    data class SheetData(
        val title: String,
        val rows: List<List<String>>,
        val isTable: Boolean,
        val pageNumber: Int
    )

    fun write(file: File, sheets: List<SheetData>, merchant: String?) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write(contentTypes(sheets.size)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels")); zip.write(rootRels()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("docProps/core.xml")); zip.write(coreProps(merchant)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml")); zip.write(workbook(sheets)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); zip.write(workbookRels(sheets.size)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/styles.xml")); zip.write(styles()); zip.closeEntry()
            sheets.forEachIndexed { i, s ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                zip.write(sheetXml(s, merchant))
                zip.closeEntry()
            }
        }
    }

    private fun contentTypes(n: Int): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        for (i in 1..n) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("</Types>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun rootRels(): ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)

    private fun coreProps(merchant: String?): ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:creator>PDF Converter</dc:creator>
<dc:title>${esc(merchant ?: "PDF Converter")}</dc:title>
<cp:lastModifiedBy>PDF Converter</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">2024-01-01T00:00:00Z</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">2024-01-01T00:00:00Z</dcterms:modified>
</cp:coreProperties>""".toByteArray(Charsets.UTF_8)

    private fun workbook(sheets: List<SheetData>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        sheets.forEachIndexed { i, s ->
            sb.append("<sheet name=\"${esc(s.title)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        sb.append("</sheets></workbook>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun workbookRels(n: Int): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..n) {
            sb.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("<Relationship Id=\"rId${n + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        sb.append("</Relationships>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun styles(): ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="3">
<font><sz val="10"/><name val="Arial"/></font>
<font><b/><color rgb="FFFFFFFF"/><sz val="14"/><name val="Arial"/></font>
<font><b/><color rgb="FF1F4E79"/><sz val="11"/><name val="Arial"/></font>
</fonts>
<fills count="4">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFD9E2F3"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color rgb="FFB0B0B0"/></left><right style="thin"><color rgb="FFB0B0B0"/></right><top style="thin"><color rgb="FFB0B0B0"/></top><bottom style="thin"><color rgb="FFB0B0B0"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>""".toByteArray(Charsets.UTF_8)

    private fun sheetXml(sheet: SheetData, merchant: String?): ByteArray {
        val maxCols = sheet.rows.fold(1) { acc, r -> maxOf(acc, r.size) }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"1\"/></sheetViews>")

        // column widths
        sb.append("<cols>")
        for (c in 1..maxCols) {
            var best = 8
            for (row in sheet.rows) {
                if (c - 1 < row.size) best = maxOf(best, row[c - 1].length)
            }
            val w = (best * 1.35f).coerceIn(9f, 60f)
            sb.append("<col min=\"$c\" max=\"$c\" width=\"$w\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")

        sb.append("<sheetData>")
        // title band (row 1, merged across maxCols): merchant | page title
        val titleText = listOfNotNull(merchant?.takeIf { it.isNotBlank() })
            .plus("${sheet.title}  (الصفحة ${sheet.pageNumber})")
            .joinToString("  |  ")
        sb.append("<row r=\"1\" ht=\"30\" customHeight=\"1\">")
        for (c in 1..maxCols) {
            val ref = "${col(c)}1"
            if (c == 1) {
                sb.append("<c r=\"$ref\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(titleText)}</t></is></c>")
            } else {
                sb.append("<c r=\"$ref\" s=\"1\"/>")
            }
        }
        sb.append("</row>")

        // data rows
        var r = 2
        for (row in sheet.rows) {
            sb.append("<row r=\"$r\">")
            for (c in 1..maxCols) {
                val value = if (c - 1 < row.size) row[c - 1] else ""
                if (value.isEmpty()) continue
                val ref = "${col(c)}$r"
                val style = when {
                    sheet.isTable && r == 2 -> 2 // header row
                    else -> 3
                }
                sb.append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(value)}</t></is></c>")
            }
            sb.append("</row>")
            r++
        }
        sb.append("</sheetData>")

        if (maxCols > 1) {
            sb.append("<mergeCells count=\"1\"><mergeCell ref=\"A1:${col(maxCols)}1\"/></mergeCells>")
        }
        sb.append("</worksheet>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun col(c: Int): String {
        var n = c
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.append(('A' + (n % 26)))
            n /= 26
        }
        return sb.reverse().toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
