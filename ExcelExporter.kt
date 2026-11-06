package com.monpanier.app

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a standards-based .xlsx workbook without requiring a third-party spreadsheet library. */
object ExcelExporter {
    fun export(rows: List<PurchaseEntity>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Achats" sheetId="1" r:id="rId1"/><sheet name="Statistiques" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""")
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace(""", "&quot;").replace("'", "&apos;")
            val headers = listOf("Date", "Magasin", "Produit", "Quantité", "Prix unitaire", "Total", "Devise", "Ticket")
            val xmlRows = buildString {
                append("<row r="1">")
                headers.forEachIndexed { i, h -> append("<c r="${col(i+1)}1" t="inlineStr"><is><t>${esc(h)}</t></is></c>") }
                append("</row>")
                rows.forEachIndexed { idx, p ->
                    val r = idx + 2; append("<row r="$r">")
                    val vals = listOf(p.receiptDate, p.store, p.product, p.quantity.toString(), p.unitPrice.toString(), p.total.toString(), p.currency, p.receiptId)
                    vals.forEachIndexed { i, v ->
                        val ref = "${col(i+1)}$r"
                        if (i == 3 || i == 4 || i == 5) append("<c r="$ref"><v>${esc(v)}</v></c>")
                        else append("<c r="$ref" t="inlineStr"><is><t>${esc(v)}</t></is></c>")
                    }
                    append("</row>")
                }
            }
            entry("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$xmlRows</sheetData></worksheet>""")
            val grouped = rows.groupBy { it.product }.mapValues { it.value.sumOf(PurchaseEntity::total) }.entries.sortedByDescending { it.value }
            val statsXml = buildString {
                append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Indicateur</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Valeur</t></is></c></row>")
                append("<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Nombre de lignes d'achat</t></is></c><c r=\"B2\"><v>${rows.size}</v></c></row>")
                append("<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Quantité totale</t></is></c><c r=\"B3\"><v>${rows.sumOf { it.quantity }}</v></c></row>")
                append("<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>Montant total</t></is></c><c r=\"B4\"><v>${rows.sumOf { it.total }}</v></c></row>")
                append("<row r=\"6\"><c r=\"A6\" t=\"inlineStr\"><is><t>Produit</t></is></c><c r=\"B6\" t=\"inlineStr\"><is><t>Montant cumulé</t></is></c></row>")
                grouped.forEachIndexed { i, item -> val r=i+7; append("<row r=\"$r\"><c r=\"A$r\" t=\"inlineStr\"><is><t>${esc(item.key)}</t></is></c><c r=\"B$r\"><v>${item.value}</v></c></row>") }
            }
            entry("xl/worksheets/sheet2.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$statsXml</sheetData></worksheet>""")
        }
    }
    private fun col(n: Int): String { var x=n; var out=""; while(x>0){ val r=(x-1)%26; out=('A'.code+r).toChar()+out; x=(x-1)/26 }; return out }
}
