
package com.monpanier.app

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ParsedLine(
    val product: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
)

/** Un mot reconnu par l'OCR, avec sa position dans l'image. */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
}

object ReceiptParser {

    private enum class Kind { PRODUCT, QTY, PRICE, TOTAL }

    private class Column(
        val kind: Kind,
        val left: Float,
        val right: Float
    ) {
        val cx: Float get() = (left + right) / 2f
    }

    private class RawRow(
        var product: String,
        var qty: Double?,
        var price: Double?,
        var total: Double?
    )

    private val accents = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]")
    private val currencyWord = Regex("(?i)\\b(da|dzd|cad)\\b")
    private val thousandsDot = Regex("(?<=\\d)\\.(?=\\d{3},\\d)")
    private val thousandsSpace = Regex("(?<=\\d) (?=\\d{3}(?!\\d))")
    private val numberRegex = Regex("\\d+(?:[.,]\\d+)?")

    private val extraHeaderWords = setOf(
        "ht", "ttc", "da", "dzd", "cad",
        "n", "no", "ref", "reference"
    )

    private fun norm(s: String): String =
        Normalizer.normalize(
            s.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(accents, "")
            .replace(nonAlnum, "")

    private fun kindOf(word: String): Kind? {
        val n = norm(word)
        if (n.isEmpty()) return null

        return when {
            n.startsWith("produit") ||
                n.startsWith("designation") ||
                n.startsWith("article") ||
                n.startsWith("libelle") ||
                n.startsWith("description") -> Kind.PRODUCT

            n.startsWith("quantit") ||
                n == "qte" ||
                n == "qtes" ||
                n == "qt" ||
                n == "qty" -> Kind.QTY

            n == "pu" ||
                n == "puht" ||
                n == "puttc" ||
                n == "prix" ||
                n == "unitaire" ||
                n.startsWith("prixunit") -> Kind.PRICE

            n == "total" ||
                n == "montant" ||
                n == "totalht" ||
                n == "totalttc" -> Kind.TOTAL

            else -> null
        }
    }

    private fun isHeaderLikeWord(word: String): Boolean {
        val n = norm(word)
        if (n.isEmpty()) return word.none { it.isLetterOrDigit() }
        return kindOf(word) != null || n in extraHeaderWords
    }

    private fun cleanProduct(text: String): String =
        text.trim()
            .trim('-', ':', '.', '|')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun appendProduct(first: String, second: String): String {
        val a = cleanProduct(first)
        val b = cleanProduct(second)

        if (a.isBlank()) return b
        if (b.isBlank()) return a

        // Évite de répéter exactement le même fragment OCR.
        if (a.equals(b, ignoreCase = true)) return a

        return "$a $b"
    }

    /** Regroupe les mots OCR par ligne. */
    private fun groupRows(words: List<OcrWord>): List<List<OcrWord>> {
        if (words.isEmpty()) return emptyList()

        val heights = words.map { it.height }.sorted()
        val tolerance = heights[heights.size / 2] * 0.6f

        val rows = ArrayList<MutableList<OcrWord>>()
        var currentCy = 0f

        for (w in words.sortedBy { it.cy }) {
            if (
                rows.isEmpty() ||
                Math.abs(w.cy - currentCy) > tolerance
            ) {
                rows.add(mutableListOf(w))
                currentCy = w.cy
            } else {
                val row = rows[rows.size - 1]
                row.add(w)
                currentCy = row.map { it.cy }.average().toFloat()
            }
        }

        return rows.map { row -> row.sortedBy { it.left } }
    }

    private fun numberIn(cell: List<OcrWord>): Double? {
        if (cell.isEmpty()) return null

        var s = cell.sortedBy { it.left }
            .joinToString(" ") { it.text }

        s = s.replace(currencyWord, " ")
            .replace(thousandsDot, "")
            .replace(thousandsSpace, "")

        val match = numberRegex.find(s) ?: return null

        return match.value
            .replace(',', '.')
            .toDoubleOrNull()
    }

    /**
     * Lecture par colonnes.
     * Les lignes descriptives sans prix sont rattachées
     * au produit précédent ou au prochain produit détecté.
     */
    fun parseColumns(words: List<OcrWord>): List<ParsedLine>? {

        val rows = groupRows(words)

        val headerIndex = rows.indexOfFirst { row ->
            val kinds = row.mapNotNull { kindOf(it.text) }.toSet()

            Kind.PRODUCT in kinds &&
                (Kind.QTY in kinds || Kind.PRICE in kinds)
        }

        if (headerIndex < 0) return null

        val header = rows[headerIndex]

        val byKind = LinkedHashMap<Kind, MutableList<OcrWord>>()

        for (i in header.indices) {
            val w = header[i]
            var kind = kindOf(w.text) ?: continue

            // "Prix total" appartient à la colonne Total.
            if (
                kind == Kind.PRICE &&
                norm(w.text) == "prix" &&
                i + 1 < header.size
            ) {
                val next = header[i + 1]

                if (
                    kindOf(next.text) == Kind.TOTAL &&
                    next.left - w.right < 2.5f * w.height
                ) {
                    kind = Kind.TOTAL
                }
            }

            byKind.getOrPut(kind) { mutableListOf() }.add(w)
        }

        val columns = byKind.map { entry ->
            Column(
                entry.key,
                entry.value.minOf { it.left },
                entry.value.maxOf { it.right }
            )
        }.sortedBy { it.cx }

        if (columns.isEmpty()) return emptyList()

        val bounds = FloatArray(columns.size - 1) { i ->
            val a = columns[i]
            val b = columns[i + 1]

            if (a.kind == Kind.PRODUCT) {
                b.left - 0.5f * (b.right - b.left)
            } else {
                (a.cx + b.cx) / 2f
            }
        }

        val out = ArrayList<RawRow>()

        // Texte produit en attente lorsqu'il apparaît avant son prix.
        var pendingProduct = ""

        var skippedHeaderRows = 0
        var started = false

        for (r in headerIndex + 1 until rows.size) {

            val row = rows[r]

            if (
                !started &&
                skippedHeaderRows < 2 &&
                row.all { isHeaderLikeWord(it.text) }
            ) {
                skippedHeaderRows++
                continue
            }

            started = true

            val joined = norm(row.joinToString("") { it.text })

            if (
                joined.contains("total") ||
                joined.contains("payer")
            ) {
                break
            }

            val cells = Array(columns.size) {
                mutableListOf<OcrWord>()
            }

            for (w in row) {
                var idx = 0

                while (
                    idx < bounds.size &&
                    w.cx > bounds[idx]
                ) {
                    idx++
                }

                cells[idx].add(w)
            }

            var product = ""
            var qty: Double? = null
            var price: Double? = null
            var total: Double? = null

            for (i in columns.indices) {
                when (columns[i].kind) {

                    Kind.PRODUCT -> {
                        product = cells[i]
                            .sortedBy { it.left }
                            .joinToString(" ") { it.text }

                        product = cleanProduct(product)
                    }

                    Kind.QTY -> qty = numberIn(cells[i])

                    Kind.PRICE -> price = numberIn(cells[i])

                    Kind.TOTAL -> total = numberIn(cells[i])
                }
            }

            val noNumbers =
                qty == null &&
                price == null &&
                total == null

            if (product.isBlank() && noNumbers) {
                continue
            }

            /*
             * CORRECTION PRINCIPALE :
             * Une ligne contenant du texte produit mais aucun prix
             * est considérée comme une continuation descriptive.
             */
            val hasNoPrice = price == null && total == null

            if (product.isNotBlank() && hasNoPrice) {

                if (out.isNotEmpty()) {
                    val last = out[out.size - 1]
                    last.product = appendProduct(last.product, product)
                } else {
                    pendingProduct = appendProduct(
                        pendingProduct,
                        product
                    )
                }

                continue
            }

            // Rattache le texte en attente au prochain article.
            if (pendingProduct.isNotBlank()) {
                product = appendProduct(pendingProduct, product)
                pendingProduct = ""
            }

            out.add(
                RawRow(
                    product,
                    qty,
                    price,
                    total
                )
            )
        }

        return out.map { r ->

            val q = r.qty ?: 1.0

            val p = r.price
                ?: r.total?.let {
                    if (q != 0.0) it / q else it
                }
                ?: 0.0

            val t = r.total ?: (q * p)

            ParsedLine(
                cleanProduct(r.product),
                q,
                p,
                t
            )
        }.filter {
            it.product.isNotBlank()
        }
    }

    // Lecture simple : utilisée lorsque les colonnes ne sont pas reconnues.

    private val amountRegex = Regex(
        "(?<![A-Za-z])([0-9]{1,7}(?:[.,][0-9]{1,2})?)\\s*(?:DA|DZD|CAD|\\$)?$",
        RegexOption.IGNORE_CASE
    )

    private fun isIgnoredLine(line: String): Boolean {
        val n = norm(line)

        if (n.length < 2) return true

        val ignoredWords = listOf(
            "total",
            "netapayer",
            "soustotal",
            "tva",
            "remise",
            "merci",
            "ticket",
            "caisse",
            "vendeur",
            "adresse",
            "telephone",
            "date",
            "heure",
            "quantite",
            "prixunitaire"
        )

        return ignoredWords.any { n.contains(it) }
    }

    fun parse(text: String): List<ParsedLine> {

        val result = ArrayList<ParsedLine>()

        // Texte sans prix en attente avant le premier produit.
        var pendingText = ""

        for (raw in text.lines()) {

            val line = raw.trim()
                .replace(Regex("\\s+"), " ")

            if (line.isBlank()) continue

            val match = amountRegex.find(line)

            if (match == null) {

                if (isIgnoredLine(line)) continue

                /*
                 * Une ligne sans montant est probablement
                 * une deuxième partie du nom du produit.
                 */
                if (result.isNotEmpty()) {

                    val last = result[result.size - 1]

                    result[result.size - 1] = last.copy(
                        product = appendProduct(
                            last.product,
                            line
                        )
                    )

                } else {

                    pendingText = appendProduct(
                        pendingText,
                        line
                    )
                }

                continue
            }

            val amount = match.groupValues[1]
                .replace(',', '.')
                .toDoubleOrNull()
                ?: continue

            var name = line
                .substring(0, match.range.first)
                .trim()
                .trim('-', ':', '.')

            if (
                name.length < 2 ||
                isIgnoredLine(name)
            ) {
                continue
            }

            // Fusionne une éventuelle première ligne sans prix.
            if (pendingText.isNotBlank()) {
                name = appendProduct(pendingText, name)
                pendingText = ""
            }

            val qtyMatch = Regex(
                "(?:x|\\*)\\s*([0-9]+(?:[.,][0-9]+)?)",
                RegexOption.IGNORE_CASE
            ).find(name)

            val qty = qtyMatch
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?: 1.0

            val cleanName = name
                .replace(
                    Regex(
                        "(?:x|\\*)\\s*[0-9]+(?:[.,][0-9]+)?",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()

            result.add(
                ParsedLine(
                    cleanName.ifBlank { name },
                    qty,
                    amount / qty,
                    amount
                )
            )
        }

        return result.distinctBy {
            it.product.lowercase(Locale.ROOT) to it.total
        }
    }

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun receiptId(): String =
        UUID.randomUUID().toString()
}
