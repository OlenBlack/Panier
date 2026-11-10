
package com.monpanier.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun PriceHistoryScreen(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color
) {
    var query by remember { mutableStateOf("") }

    val data = rows.filter {
        it.currency == money.name
    }

    val products = data.map { it.product }
        .distinct()
        .sorted()

    val selected = products.firstOrNull {
        it.equals(query, true)
    } ?: products.firstOrNull {
        it.contains(query, true)
    }

    val history = data
        .filter { it.product == selected }
        .sortedBy { it.receiptDate }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (ar) "تطور الأسعار" else "Évolution des prix",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (ar) "ابحث عن منتج"
                    else "Rechercher un produit"
                )
            }
        )

        if (selected == null) {
            Text(
                if (ar) "امسح تذكرة لتكوين سجل الأسعار."
                else "Scanne des tickets pour commencer le suivi des prix.",
                color = fg
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = card
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        selected,
                        color = fg,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Dernier prix : ${"%.2f".format(Locale.US, history.last().unitPrice)} ${money.name}",
                        color = Color(0xFF24C879),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "${history.size} relevé(s) • ${history.map { it.store }.distinct().size} magasin(s)",
                        color = fg
                    )

                    if (history.size >= 2) {
                        PriceTrend(
                            history.map { it.unitPrice },
                            Color(0xFF24C879)
                        )

                        history.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${p.receiptDate} • ${p.store}",
                                    color = fg
                                )

                                Text(
                                    "${"%.2f".format(Locale.US, p.unitPrice)} ${money.name}",
                                    color = fg
                                )
                            }
                        }
                    } else {
                        Text(
                            "Deux achats au minimum sont nécessaires pour afficher la courbe.",
                            color = fg
                        )
                    }

                    Text(
                        "Prix minimum : ${"%.2f".format(Locale.US, history.minOf { it.unitPrice })} ${money.name} • Maximum : ${"%.2f".format(Locale.US, history.maxOf { it.unitPrice })} ${money.name}",
                        color = fg
                    )
                }
            }
        }

        Text(
            "Les prix sont issus des tickets enregistrés, pas de catalogues en temps réel.",
            color = fg.copy(alpha = .65f),
            fontSize = 12.sp
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(products) { name ->
                TextButton(
                    onClick = { query = name }
                ) {
                    Text(name, color = fg)
                }
            }
        }
    }
}

@Composable
private fun PriceTrend(
    values: List<Double>,
    color: Color
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(135.dp)
    ) {
        if (values.size < 2) return@Canvas

        val lo = values.minOrNull() ?: 0.0
        val hi = values.maxOrNull() ?: 1.0
        val span = (hi - lo).takeIf {
            it > 0.0
        } ?: 1.0

        val pts = values.mapIndexed { i, v ->
            Offset(
                size.width * i / (values.size - 1).toFloat(),
                size.height - ((v - lo) / span).toFloat() *
                    (size.height - 16f) - 8f
            )
        }

        for (i in 0 until pts.lastIndex) {
            drawLine(
                color,
                pts[i],
                pts[i + 1],
                4f
            )
        }

        pts.forEach {
            drawCircle(color, 5f, it)
        }
    }
}

private data class BasketRecommendation(
    val item: ShoppingListEntity,
    val store: String?,
    val price: Double?,
    val date: String?,
    val latestPrice: Double?,
    val savings: Double
)

@Composable
fun SmartBasketScreen(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    shoppingItems: List<ShoppingListEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color,
    onAddItem: (ShoppingListEntity) -> Unit,
    onCheckItem: (ShoppingListEntity) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearBasket: () -> Unit
) {
    var entry by remember { mutableStateOf("") }
    var suggestionSelected by remember { mutableStateOf(false) }

    // Historique des achats avec prix et devise correspondants
    val known = rows.filter {
        it.currency == money.name &&
            it.unitPrice > 0.0
    }

    // Suggestions de produits déjà achetés
    val productSuggestions = if (
        entry.isBlank() || suggestionSelected
    ) {
        emptyList()
    } else {
        rows.asSequence()
            .filter {
                it.product.isNotBlank() &&
                    it.product.contains(
                        entry.trim(),
                        ignoreCase = true
                    )
            }
            .sortedByDescending {
                it.receiptDate
            }
            .distinctBy {
                it.product.trim().lowercase(Locale.ROOT)
            }
            .filterNot { purchase ->
                shoppingItems.any {
                    it.product.trim().equals(
                        purchase.product.trim(),
                        ignoreCase = true
                    )
                }
            }
            .take(6)
            .toList()
    }

    // Calcul des recommandations et des meilleurs prix
    val recommendations = shoppingItems.map { item ->

        val matchingPurchases = known.filter {
            it.product.trim().equals(
                item.product.trim(),
                ignoreCase = true
            )
        }

        val best = matchingPurchases
            .filter { it.store.isNotBlank() }
            .groupBy { it.store.trim() }
            .mapValues { (_, purchases) ->
                purchases.minByOrNull {
                    it.unitPrice
                }!!
            }
            .values
            .minByOrNull {
                it.unitPrice
            }

        val latest = matchingPurchases.maxByOrNull {
            it.receiptDate
        }

        val savingsPerUnit = if (
            latest != null && best != null
        ) {
            (latest.unitPrice - best.unitPrice)
                .coerceAtLeast(0.0)
        } else {
            0.0
        }

        BasketRecommendation(
            item = item,
            store = best?.store
                ?: item.store.takeIf { it.isNotBlank() },
            price = best?.unitPrice
                ?: item.estimatedUnitPrice,
            date = best?.receiptDate ?: item.priceDate,
            latestPrice = latest?.unitPrice,
            savings = savingsPerUnit * item.quantity
        )
    }

    val storeGroups = recommendations
        .groupBy {
            it.store ?: "Prix inconnu"
        }
        .toSortedMap()

    val totalSavings = recommendations.sumOf {
        it.savings
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {

        Text(
            if (ar) "السلة الذكية"
            else "Panier intelligent",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Spacer(Modifier.height(5.dp))

        // Champ de saisie et bouton d'ajout
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = entry,
                onValueChange = {
                    entry = it
                    suggestionSelected = false
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = {
                    Text(
                        if (ar) "اسم المنتج"
                        else "Produit à ajouter"
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = {
                    val productName = entry.trim()

                    if (productName.isNotBlank()) {

                        val matchingPurchases = known.filter {
                            it.product.trim().equals(
                                productName,
                                ignoreCase = true
                            )
                        }

                        val best = matchingPurchases
                            .filter { it.store.isNotBlank() }
                            .groupBy { it.store.trim() }
                            .mapValues { (_, purchases) ->
                                purchases.minByOrNull {
                                    it.unitPrice
                                }!!
                            }
                            .values
                            .minByOrNull {
                                it.unitPrice
                            }

                        onAddItem(
                            ShoppingListEntity(
                                product = productName,
                                quantity = 1.0,
                                currency = money.name,
                                store = best?.store ?: "",
                                estimatedUnitPrice = best?.unitPrice,
                                priceDate = best?.receiptDate ?: "",
                                checked = false
                            )
                        )

                        entry = ""
                        suggestionSelected = false
                    }
                },
                enabled = entry.isNotBlank(),
                contentPadding = PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Ajouter"
                )
            }
        }

        // Liste des suggestions
        if (productSuggestions.isNotEmpty()) {

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 3.dp,
                        bottom = 4.dp
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = card
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {

                    Text(
                        if (ar) "المنتجات المشتراة سابقا"
                        else "Produits déjà achetés",
                        color = fg.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 5.dp
                        )
                    )

                    productSuggestions.forEach { purchase ->

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    entry = purchase.product.trim()
                                    suggestionSelected = true
                                }
                                .padding(
                                    horizontal = 12.dp,
                                    vertical = 9.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = Color(0xFF24C879),
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(Modifier.width(10.dp))

                            Text(
                                purchase.product,
                                color = fg,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )

                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Sélectionner",
                                tint = fg.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Affichage du panier
        if (shoppingItems.isEmpty()) {

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ton panier est vide. Ajoute un produit.",
                    color = fg.copy(alpha = .7f)
                )
            }

        } else {

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {

                storeGroups.forEach { (storeName, products) ->

                    item(key = "header_$storeName") {

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = 3.dp,
                                    bottom = 1.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                if (storeName == "Prix inconnu")
                                    "AUTRES PRODUITS"
                                else
                                    storeName.uppercase(),
                                color = if (
                                    storeName == "Prix inconnu"
                                ) {
                                    Color(0xFFFFB74D)
                                } else {
                                    Color(0xFF24C879)
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )

                            if (storeName != "Prix inconnu") {

                                val groupTotal = products.sumOf {
                                    (it.price ?: 0.0) *
                                        it.item.quantity
                                }

                                Text(
                                    "${"%.2f".format(Locale.US, groupTotal)} ${money.name}",
                                    color = fg.copy(alpha = .75f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    items(
                        items = products,
                        key = { "product_${it.item.id}" }
                    ) { recommendation ->

                        val item = recommendation.item

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = card
                            ),
                            shape = RoundedCornerShape(11.dp)
                        ) {

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 7.dp,
                                        vertical = 4.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Checkbox(
                                    checked = item.checked,
                                    onCheckedChange = { checked ->
                                        onCheckItem(
                                            item.copy(checked = checked)
                                        )
                                    },
                                    modifier = Modifier.size(36.dp)
                                )

                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(start = 5.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {

                                    Text(
                                        item.product,
                                        color = fg,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )

                                    if (recommendation.price != null) {

                                        Text(
                                            "${"%.2f".format(Locale.US, recommendation.price)} ${money.name} • ${recommendation.store ?: "Magasin inconnu"}",
                                            color = Color(0xFF24C879),
                                            fontSize = 12.sp
                                        )

                                    } else {

                                        Text(
                                            "Prix inconnu",
                                            color = Color(0xFFFFB74D),
                                            fontSize = 12.sp
                                        )
                                    }

                                    if (item.checked) {
                                        Text(
                                            "Acheté",
                                            color = Color(0xFF24C879),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        onDeleteItem(item.id)
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer",
                                        tint = Color(0xFFE57373),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = fg.copy(alpha = .15f)
        )

        // Économies potentielles
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    top = 8.dp,
                    bottom = 4.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "Économies potentielles",
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                "${"%.2f".format(Locale.US, totalSavings)} ${money.name}",
                color = Color(0xFF24C879),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
