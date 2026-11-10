package com.monpanier.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    val data = rows.filter { it.currency == money.name }

    val products = data.map { it.product }
        .distinct()
        .sorted()

    val selected = products.firstOrNull { it.equals(query, true) }
        ?: products.firstOrNull { it.contains(query, true) }

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
                Text(if (ar) "ابحث عن منتج" else "Rechercher un produit")
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
                colors = CardDefaults.cardColors(containerColor = card),
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
                        "Dernier prix : ${
                            "%.2f".format(Locale.US, history.last().unitPrice)
                        } ${money.name}",
                        color = Color(0xFF24C879),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "${history.size} relevé(s) • " +
                            "${history.map { it.store }.distinct().size} magasin(s)",
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
                        "Prix minimum : ${
                            "%.2f".format(
                                Locale.US,
                                history.minOf { it.unitPrice }
                            )
                        } ${money.name} • Maximum : ${
                            "%.2f".format(
                                Locale.US,
                                history.maxOf { it.unitPrice }
                            )
                        } ${money.name}",
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
                TextButton(onClick = { query = name }) {
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
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0

        val pts = values.mapIndexed { i, v ->
            Offset(
                size.width * i / (values.size - 1).toFloat(),
                size.height -
                    ((v - lo) / span).toFloat() *
                    (size.height - 16f) - 8f
            )
        }

        for (i in 0 until pts.lastIndex) {
            drawLine(color, pts[i], pts[i + 1], 4f)
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
    var showClearDialog by remember { mutableStateOf(false) }

    val known = rows.filter {
        it.currency == money.name && it.unitPrice > 0.0
    }

    // Construit les recommandations à partir des achats enregistrés.
    val recommendations = shoppingItems.map { item ->

        val matchingPurchases = known.filter {
            it.product.trim().equals(
                item.product.trim(),
                ignoreCase = true
            )
        }

        val purchasesWithStore = matchingPurchases.filter {
            it.store.isNotBlank()
        }

        // Meilleur prix par magasin.
        val cheapestByStore = purchasesWithStore
            .groupBy { it.store.trim() }
            .mapValues { (_, purchases) ->
                purchases.minByOrNull { it.unitPrice }!!
            }

        // Magasin proposant le prix historique le plus bas.
        val best = cheapestByStore.values.minByOrNull {
            it.unitPrice
        }

        // Dernier prix enregistré pour ce produit.
        val latest = matchingPurchases.maxByOrNull {
            it.receiptDate
        }

        // Économie potentielle par unité.
        val savingsPerUnit = if (
            latest != null && best != null
        ) {
            (latest.unitPrice - best.unitPrice).coerceAtLeast(0.0)
        } else {
            0.0
        }

        BasketRecommendation(
            item = item,
            store = best?.store ?: item.store.takeIf { it.isNotBlank() },
            price = best?.unitPrice ?: item.estimatedUnitPrice,
            date = best?.receiptDate ?: item.priceDate,
            latestPrice = latest?.unitPrice,
            savings = savingsPerUnit * item.quantity
        )
    }

    val storeGroups = recommendations
        .groupBy { it.store ?: "Prix inconnu" }
        .toSortedMap()

    val totalEstimated = recommendations.sumOf {
        (it.price ?: 0.0) * it.item.quantity
    }

    val totalSavings = recommendations.sumOf {
        it.savings
    }

    val unknownCount = recommendations.count {
        it.price == null
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // Titre.
        Text(
            if (ar) "السلة الذكية" else "Panier intelligent",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Text(
            if (ar) "أضف المنتجات لمعرفة أرخص متجر."
            else "Ajoute tes produits pour trouver le magasin le moins cher.",
            color = fg
        )

        // Ajout d'un produit.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = {
                    Text(if (ar) "اسم المنتج" else "Produit à ajouter")
                }
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
                                purchases.minByOrNull { it.unitPrice }!!
                            }
                            .values
                            .minByOrNull { it.unitPrice }

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
                    }
                },
                enabled = entry.isNotBlank()
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Ajouter"
                )
            }
        }

        // Résumé du panier.
        if (shoppingItems.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = card
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        "Résumé du panier",
                        color = fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )

                    Text(
                        "Produits : ${shoppingItems.size}",
                        color = fg
                    )

                    Text(
                        "Total estimatif : ${
                            "%.2f".format(Locale.US, totalEstimated)
                        } ${money.name}",
                        color = fg,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Économies potentielles : ${
                            "%.2f".format(Locale.US, totalSavings)
                        } ${money.name}",
                        color = Color(0xFF24C879),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (unknownCount > 0) {
                        Text(
                            "$unknownCount produit(s) sans prix historique fiable.",
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp
                        )
                    }

                    Text(
                        "L'économie est estimée en comparant le dernier prix enregistré au meilleur prix historique.",
                        color = fg.copy(alpha = .65f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Vider tout le panier.
        if (shoppingItems.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    showClearDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE57373)
                )
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null
                )

                Spacer(Modifier.width(8.dp))

                Text("Vider tout le panier")
            }
        }

        // Panier vide.
        if (shoppingItems.isEmpty()) {
            Text(
                "Ton panier est vide. Ajoute un produit pour commencer.",
                color = fg.copy(alpha = .7f)
            )
        }

        // Liste persistante des produits.
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            storeGroups.forEach { (storeName, products) ->

                item(key = "header_$storeName") {

                    val estimatedTotal = products.sumOf {
                        (it.price ?: 0.0) * it.item.quantity
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = card
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                if (storeName == "Prix inconnu")
                                    "AUTRES PRODUITS"
                                else
                                    "MAGASIN RECOMMANDÉ : $storeName",
                                color = if (storeName == "Prix inconnu")
                                    Color(0xFFFFB74D)
                                else
                                    Color(0xFF24C879),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )

                            if (storeName != "Prix inconnu") {
                                Text(
                                    "Total estimatif : ${
                                        "%.2f".format(
                                            Locale.US,
                                            estimatedTotal
                                        )
                                    } ${money.name}",
                                    color = fg,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            } else {
                                Text(
                                    "Impossible de déterminer un magasin avec l'historique disponible.",
                                    color = fg
                                )
                            }
                        }
                    }
                }

                items(
                    items = products,
                    key = { "product_${it.item.id}" }
                ) { recommendation ->

                    val item = recommendation.item

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = card
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {

                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {

                                // Cocher un produit sans le supprimer.
                                Checkbox(
                                    checked = item.checked,
                                    onCheckedChange = { checked ->
                                        onCheckItem(
                                            item.copy(checked = checked)
                                        )
                                    }
                                )

                                Text(
                                    item.product,
                                    Modifier.weight(1f),
                                    color = fg,
                                    fontWeight = FontWeight.Bold
                                )

                                // Suppression individuelle.
                                IconButton(
                                    onClick = {
                                        onDeleteItem(item.id)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Supprimer",
                                        tint = Color(0xFFE57373)
                                    )
                                }
                            }

                            Text(
                                "Quantité : ${item.quantity}",
                                color = fg.copy(alpha = .75f),
                                fontSize = 13.sp
                            )

                            if (recommendation.price != null) {

                                Text(
                                    "Meilleur prix historique : ${
                                        "%.2f".format(
                                            Locale.US,
                                            recommendation.price
                                        )
                                    } ${money.name}",
                                    color = Color(0xFF24C879),
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "Magasin : ${recommendation.store ?: "Inconnu"}",
                                    color = fg
                                )

                                Text(
                                    "Relevé du : ${
                                        recommendation.date ?: "Inconnu"
                                    }",
                                    color = fg.copy(alpha = .65f),
                                    fontSize = 12.sp
                                )

                                if (recommendation.latestPrice != null) {
                                    Text(
                                        "Dernier prix enregistré : ${
                                            "%.2f".format(
                                                Locale.US,
                                                recommendation.latestPrice
                                            )
                                        } ${money.name}",
                                        color = fg.copy(alpha = .8f),
                                        fontSize = 12.sp
                                    )
                                }

                                if (recommendation.savings > 0.0) {
                                    Text(
                                        "Économie potentielle : ${
                                            "%.2f".format(
                                                Locale.US,
                                                recommendation.savings
                                            )
                                        } ${money.name}",
                                        color = Color(0xFF24C879),
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                            } else {
                                Text(
                                    "Aucun prix disponible dans l'historique.",
                                    color = Color(0xFFFFB74D)
                                )
                            }

                            if (item.checked) {
                                Text(
                                    "Produit acheté",
                                    color = Color(0xFF24C879),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Les prix proviennent des anciens tickets enregistrés. " +
                "Ils peuvent avoir changé depuis le dernier achat.",
            color = fg.copy(alpha = .65f),
            fontSize = 11.sp
        )
    }

    // Confirmation avant de vider le panier.
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = {
                showClearDialog = false
            },
            title = {
                Text("Vider le panier ?")
            },
            text = {
                Text(
                    "Tous les produits de ton panier seront supprimés. " +
                        "Cette opération ne supprime pas les tickets enregistrés."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearBasket()
                        showClearDialog = false
                    }
                ) {
                    Text(
                        "Vider",
                        color = Color(0xFFE57373)
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }
}
