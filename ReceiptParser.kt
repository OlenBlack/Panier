package com.monpanier.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ParsedLine(val product: String, val quantity: Double, val unitPrice: Double, val total: Double)
object ReceiptParser {
    // Heuristic starter parser: OCR text always remains editable before saving.
    private val amountRegex = Regex("(?<![A-Za-z])([0-9]{1,7}(?:[.,][0-9]{1,2})?)\s*(?:DA|DZD|CAD|\$)?$", RegexOption.IGNORE_CASE)
    fun parse(text: String): List<ParsedLine> = text.lines().mapNotNull { raw ->
        val line = raw.trim().replace(Regex("\s+"), " ")
        val match = amountRegex.find(line) ?: return@mapNotNull null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        val name = line.substring(0, match.range.first).trim().trim('-', ':', '.')
        if (name.length < 2 || name.contains("TOTAL", true) || name.contains("TVA", true) || name.contains("REMISE", true)) return@mapNotNull null
        val qtyMatch = Regex("(?:x|\*)\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(name)
        val qty = qtyMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val cleanName = name.replace(Regex("(?:x|\*)\s*[0-9]+(?:[.,][0-9]+)?", RegexOption.IGNORE_CASE), "").trim()
        ParsedLine(cleanName.ifBlank { name }, qty, amount / qty, amount)
    }.distinctBy { it.product.lowercase(Locale.ROOT) to it.total }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun receiptId(): String = UUID.randomUUID().toString()
}
