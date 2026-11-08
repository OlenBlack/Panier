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
fun PriceHistoryScreen(pad: PaddingValues, rows: List<PurchaseEntity>, money: Money, ar: Boolean, fg: Color, card: Color) {
    var query by remember { mutableStateOf("") }
    val data = rows.filter { it.currency == money.name }
    val products = data.map { it.product }.distinct().sorted()
    val selected = products.firstOrNull { it.equals(query, true) }
        ?: products.firstOrNull { it.contains(query, true) }
    val history = data.filter { it.product == selected }.sortedBy { it.receiptDate }
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ar) "تطور الأسعار" else "Évolution des prix", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = fg)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (ar) "ابحث عن منتج" else "Rechercher un produit") })
        if (selected == null) {
            Text(if (ar) "امسح تذكرة لتكوين سجل الأسعار." else "Scanne des tickets pour commencer le suivi des prix.", color = fg)
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected, color = fg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Dernier prix : ${"%.2f".format(Locale.US, history.last().unitPrice)} ${money.name}",
                        color = Color(0xFF24C879), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${history.size} relevé(s) • ${history.map { it.store }.distinct().size} magasin(s)", color = fg)
                    if (history.size >= 2) {
                        PriceTrend(history.map { it.unitPrice }, Color(0xFF24C879))
                        history.forEach { p ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${p.receiptDate} • ${p.store}", color = fg)
                                Text("${"%.2f".format(Locale.US, p.unitPrice)} ${money.name}", color = fg)
                            }
                        }
                    } else Text("Deux achats au minimum sont nécessaires pour afficher la courbe.", color = fg)
                    Text("Prix minimum : ${"%.2f".format(Locale.US, history.minOf { it.unitPrice })} ${money.name}  •  Maximum : ${"%.2f".format(Locale.US, history.maxOf { it.unitPrice })} ${money.name}",
                        color = fg)
                }
            }
        }
        Text("Les prix sont issus des tickets enregistrés, pas de catalogues en temps réel.", color = fg.copy(alpha = .65f), fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(products) { name -> TextButton(onClick = { query = name }) { Text(name, color = fg) } }
        }
    }
}

@Composable
private fun PriceTrend(values: List<Double>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(135.dp)) {
        if (values.size < 2) return@Canvas
        val lo = values.minOrNull() ?: 0.0
        val hi = values.maxOrNull() ?: 1.0
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val pts = values.mapIndexed { i, v ->
            Offset(size.width * i / (values.size - 1).toFloat(),
                size.height - ((v - lo) / span).toFloat() * (size.height - 16f) - 8f)
        }
        for (i in 0 until pts.lastIndex) drawLine(color, pts[i], pts[i + 1], 4f)
        pts.forEach { drawCircle(color, 5f, it) }
    }
}

private fun familyOf(product: String): String {
    val n = product.lowercase(Locale.ROOT)
    return when {
        listOf("lait", "milk", "حليب").any(n::contains) -> "lait"
        listOf("eau", "water", "ماء", "مياه").any(n::contains) -> "eau"
        listOf("huile", "oil", "زيت").any(n::contains) -> "huile"
        listOf("sucre", "sugar", "سكر").any(n::contains) -> "sucre"
        listOf("pain", "bread", "خبز").any(n::contains) -> "pain"
        listOf("pate", "pâtes", "pasta", "معكرونة").any(n::contains) -> "pates"
        else -> n
    }
}

@Composable
fun SmartBasketScreen(pad: PaddingValues, rows: List<PurchaseEntity>, money: Money, ar: Boolean, fg: Color, card: Color) {
    var entry by remember { mutableStateOf("") }
    var basket by remember { mutableStateOf(listOf<String>()) }
    val known = rows.filter { it.currency == money.name }
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ar) "السلة الذكية" else "Panier intelligent", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = fg)
        Text(if (ar) "أضف منتجات سبق شراؤها." else "Ajoute les produits que tu souhaites acheter.", color = fg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(entry, { entry = it }, modifier = Modifier.weight(1f), singleLine = true,
                label = { Text(if (ar) "Nom du produit" else "Produit à ajouter") })
            Button(onClick = { if (entry.isNotBlank()) { basket = basket + entry.trim(); entry = "" } }) {
                Icon(Icons.Default.Add, contentDescription = "Ajouter")
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(basket) { wanted ->
                val exact = known.filter { it.product.equals(wanted, true) }.minByOrNull { it.unitPrice }
                val family = familyOf(wanted)
                val equivalent = known.filter { it.product != wanted && familyOf(it.product) == family && family != wanted.lowercase(Locale.ROOT) }
                    .minByOrNull { it.unitPrice }
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row {
                            Text(wanted, Modifier.weight(1f), color = fg, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { basket = basket.filterNot { it == wanted } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        }
                        if (exact != null) {
                            Text("PRODUIT IDENTIQUE • ${exact.store}", color = Color(0xFF24C879), fontWeight = FontWeight.Bold)
                            Text("${"%.2f".format(Locale.US, exact.unitPrice)} ${money.name} • ${exact.receiptDate}", color = fg)
                        } else Text("Aucun prix exact dans l'historique.", color = fg)
                        if (equivalent != null) {
                            Text("PRODUIT ÉQUIVALENT • ${equivalent.product}", color = Color(0xFF24C879), fontWeight = FontWeight.Bold)
                            Text("${equivalent.store} • ${"%.2f".format(Locale.US, equivalent.unitPrice)} ${money.name}", color = fg)
                            Text("Suggestion approximative : vérifie marque, volume, poids et caractéristiques.", color = fg.copy(alpha = .65f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Text("Comparaison fondée sur tes anciens tickets. Les prix ne sont pas vérifiés en direct auprès des magasins.", color = fg.copy(alpha = .65f), fontSize = 12.sp)
    }
}
