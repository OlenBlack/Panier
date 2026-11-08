package com.monpanier.app

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ParsedLine(val product: String, val quantity: Double, val unitPrice: Double, val total: Double)

/** Un mot reconnu par l'OCR, avec sa position (en pixels) dans l'image lue. */
data class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
}

object ReceiptParser {
    private enum class Kind { PRODUCT, QTY, PRICE, TOTAL }

    private class Column(val kind: Kind, val left: Float, val right: Float) {
        val cx: Float get() = (left + right) / 2f
    }

    private class RawRow(var product: String, var qty: Double?, var price: Double?, var total: Double?)

    private val accents = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]")
    private val currencyWord = Regex("(?i)\\b(da|dzd|cad)\\b")
    private val thousandsDot = Regex("(?<=\\d)\\.(?=\\d{3},\\d)")
    private val thousandsSpace = Regex("(?<=\\d) (?=\\d{3}(?!\\d))")
    private val numberRegex = Regex("\\d+(?:[.,]\\d+)?")
    private val extraHeaderWords = setOf("ht", "ttc", "da", "dzd", "cad", "n", "no", "ref", "reference")

    /** Minuscules, sans accents ni ponctuation : "P.U" -> "pu", "Qté" -> "qte". */
    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(accents, "")
            .replace(nonAlnum, "")

    /** Reconnaît un mot d'en-tête : Produit / Qté / P.U / Total. */
    private fun kindOf(word: String): Kind? {
        val n = norm(word)
        if (n.isEmpty()) return null
        return when {
            n.startsWith("produit") || n.startsWith("designation") || n.startsWith("article") ||
                n.startsWith("libelle") || n.startsWith("description") -> Kind.PRODUCT
            n.startsWith("quantit") || n == "qte" || n == "qtes" || n == "qt" || n == "qty" -> Kind.QTY
            n == "pu" || n == "puht" || n == "puttc" || n == "prix" || n == "unitaire" || n.startsWith("prixunit") -> Kind.PRICE
            n == "total" || n == "montant" || n == "totalht" || n == "totalttc" -> Kind.TOTAL
            else -> null
        }
    }

    private fun isHeaderLikeWord(word: String): Boolean {
        val n = norm(word)
        if (n.isEmpty()) return word.none { it.isLetterOrDigit() }
        return kindOf(word) != null || n in extraHeaderWords
    }

    /** Regroupe les mots en lignes (même hauteur), triées de haut en bas puis de gauche à droite. */
    private fun groupRows(words: List<OcrWord>): List<List<OcrWord>> {
        if (words.isEmpty()) return emptyList()
        val heights = words.map { it.height }.sorted()
        val tolerance = heights[heights.size / 2] * 0.6f
        val rows = ArrayList<MutableList<OcrWord>>()
        var currentCy = 0f
        for (w in words.sortedBy { it.cy }) {
            if (rows.isEmpty() || Math.abs(w.cy - currentCy) > tolerance) {
                rows.add(mutableListOf(w))
                currentCy = w.cy
            } else {
                val row = rows[rows.size - 1]
                row.add(w)
                currentCy = row.map { it.cy }.average().toFloat()
            }
        }
        return rows.map { r -> r.sortedBy { it.left } }
    }

    private fun numberIn(cell: List<OcrWord>): Double? {
        if (cell.isEmpty()) return null
        var s = cell.sortedBy { it.left }.joinToString(" ") { it.text }
        s = s.replace(currencyWord, " ").replace(thousandsDot, "").replace(thousandsSpace, "")
        val m = numberRegex.find(s) ?: return null
        return m.value.replace(',', '.').toDoubleOrNull()
    }

    /**
     * Lecture par colonnes d'après la position des mots :
     * - on cherche la ligne d'en-têtes (Produit + Qté et/ou P.U),
     * - chaque colonne est lue uniquement sous son en-tête,
     * - la lecture s'arrête avant la ligne "Total" / "Net à payer".
     * Retourne null si les en-têtes ne sont pas trouvés.
     */
    fun parseColumns(words: List<OcrWord>): List<ParsedLine>? {
        val rows = groupRows(words)
        val headerIndex = rows.indexOfFirst { row ->
            val kinds = row.mapNotNull { kindOf(it.text) }.toSet()
            Kind.PRODUCT in kinds && (Kind.QTY in kinds || Kind.PRICE in kinds)
        }
        if (headerIndex < 0) return null
        val header = rows[headerIndex]

        val byKind = LinkedHashMap<Kind, MutableList<OcrWord>>()
        for (i in header.indices) {
            val w = header[i]
            var kind = kindOf(w.text) ?: continue
            // "Prix total" : ici "Prix" fait partie de l'en-tête Total, pas de P.U
            if (kind == Kind.PRICE && norm(w.text) == "prix" && i + 1 < header.size) {
                val next = header[i + 1]
                if (kindOf(next.text) == Kind.TOTAL && next.left - w.right < 2.5f * w.height) kind = Kind.TOTAL
            }
            byKind.getOrPut(kind) { mutableListOf() }.add(w)
        }
        val columns = byKind.map { entry ->
            Column(entry.key, entry.value.minOf { it.left }, entry.value.maxOf { it.right })
        }.sortedBy { it.cx }

        // Frontières entre colonnes : la colonne Produit s'arrête juste avant la colonne suivante,
        // les colonnes de chiffres se partagent l'espace au milieu de leurs en-têtes.
        val bounds = FloatArray(columns.size - 1) { i ->
            val a = columns[i]
            val b = columns[i + 1]
            if (a.kind == Kind.PRODUCT) b.left - 0.5f * (b.right - b.left) else (a.cx + b.cx) / 2f
        }

        val out = ArrayList<RawRow>()
        var skippedHeaderRows = 0
        var started = false
        for (r in headerIndex + 1 until rows.size) {
            val row = rows[r]
            // suite de l'en-tête sur une 2e ligne ("unitaire", "HT"...)
            if (!started && skippedHeaderRows < 2 && row.all { isHeaderLikeWord(it.text) }) {
                skippedHeaderRows++
                continue
            }
            started = true

            // arrêt avant "Total" / "Net à payer"
            val joined = norm(row.joinToString("") { it.text })
            if (joined.contains("total") || joined.contains("payer")) break

            val cells = Array(columns.size) { mutableListOf<OcrWord>() }
            for (w in row) {
                var idx = 0
                while (idx < bounds.size && w.cx > bounds[idx]) idx++
                cells[idx].add(w)
            }

            var product = ""
            var qty: Double? = null
            var price: Double? = null
            var total: Double? = null
            for (i in columns.indices) {
                when (columns[i].kind) {
                    Kind.PRODUCT -> product = cells[i].sortedBy { it.left }.joinToString(" ") { it.text }
                        .trim().trim('-', ':', '.', '|').trim()
                    Kind.QTY -> qty = numberIn(cells[i])
                    Kind.PRICE -> price = numberIn(cells[i])
                    Kind.TOTAL -> total = numberIn(cells[i])
                }
            }

            val noNumbers = qty == null && price == null && total == null
            if (product.isBlank() && noNumbers) continue
            if (noNumbers && out.isNotEmpty()) {
                // nom de produit écrit sur 2 lignes : on complète le produit précédent
                val last = out[out.size - 1]
                last.product = (last.product + " " + product).trim()
                continue
            }
            out.add(RawRow(product, qty, price, total))
        }

        return out.map { r ->
            val q = r.qty ?: 1.0
            val p = r.price ?: r.total?.let { if (q != 0.0) it / q else it } ?: 0.0
            val t = r.total ?: (q * p)
            ParsedLine(r.product, q, p, t)
        }
    }

    // Lecture simple (secours si les en-têtes de colonnes ne sont pas trouvés).
    private val amountRegex = Regex("(?<![A-Za-z])([0-9]{1,7}(?:[.,][0-9]{1,2})?)\\s*(?:DA|DZD|CAD|\\$)?$", RegexOption.IGNORE_CASE)
    fun parse(text: String): List<ParsedLine> = text.lines().mapNotNull { raw ->
        val line = raw.trim().replace(Regex("\\s+"), " ")
        val match = amountRegex.find(line) ?: return@mapNotNull null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        val name = line.substring(0, match.range.first).trim().trim('-', ':', '.')
        if (name.length < 2 || name.contains("TOTAL", true) || name.contains("TVA", true) || name.contains("REMISE", true)) return@mapNotNull null
        val qtyMatch = Regex("(?:x|\\*)\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(name)
        val qty = qtyMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val cleanName = name.replace(Regex("(?:x|\\*)\\s*[0-9]+(?:[.,][0-9]+)?", RegexOption.IGNORE_CASE), "").trim()
        ParsedLine(cleanName.ifBlank { name }, qty, amount / qty, amount)
    }.distinctBy { it.product.lowercase(Locale.ROOT) to it.total }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun receiptId(): String = UUID.randomUUID().toString()
}
