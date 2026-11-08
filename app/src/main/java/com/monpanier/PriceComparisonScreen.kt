
package com.monpanier.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class StorePrice(
    val store: String,
    val price: Double,
    val date: String,
    val currency: String
)

private data class ProductComparison(
    val name: String,
    val prices: List<StorePrice>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceComparisonScreen(
    purchases: List<PurchaseEntity>,
    onBack: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var selectedCurrency by remember { mutableStateOf("DZD") }

    val comparisons = remember(purchases, selectedCurrency) {
        purchases
            .filter {
                it.currency.equals(selectedCurrency, ignoreCase = true) &&
                    it.product.isNotBlank() &&
                    it.unitPrice > 0.0
            }
            .groupBy {
                it.product.trim().replace(Regex("\\s+"), " ")
                    .lowercase(Locale.ROOT)
            }
            .mapNotNull { (_, rows) ->
                val productName = rows
                    .maxByOrNull { it.receiptDate }
                    ?.product?.trim() ?: return@mapNotNull null

                val storePrices = rows
                    .groupBy { it.store.trim().ifBlank { "Magasin inconnu" } }
                    .mapNotNull { (store, storeRows) ->
                        val latest = storeRows.maxByOrNull { it.receiptDate }
                            ?: return@mapNotNull null

                        StorePrice(
                            store = store,
                            price = latest.unitPrice,
                            date = latest.receiptDate,
                            currency = latest.currency
                        )
                    }
                    .sortedBy { it.price }

                if (storePrices.isEmpty()) null
                else ProductComparison(productName, storePrices)
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    val filtered = comparisons.filter {
        it.name.contains(search.trim(), ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comparateur de prix") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {

            Text(
                text = "Compare les prix de tes produits entre les magasins.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("DZD", "CAD").forEachIndexed { index, currency ->
                    SegmentedButton(
                        selected = selectedCurrency == currency,
                        onClick = { selectedCurrency = currency },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 2
                        )
                    ) {
                        Text(currency)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Rechercher un produit") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null
                    )
                },
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (comparisons.isEmpty())
                                "Aucun prix enregistré en $selectedCurrency."
                            else
                                "Aucun produit correspondant à ta recherche.",
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "Enregistre des tickets provenant de plusieurs magasins pour commencer les comparaisons.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                Text(
                    text = "${filtered.size} produit(s) trouvé(s)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = filtered,
                        key = { it.name.lowercase(Locale.ROOT) }
                    ) { comparison ->

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = comparison.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(12.dp))

                                val cheapest = comparison.prices.minByOrNull {
                                    it.price
                                }

                                comparison.prices.forEach { price ->
                                    val isCheapest =
                                        price == cheapest &&
                                            comparison.prices.size > 1

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = price.store,
                                                fontWeight = if (isCheapest)
                                                    FontWeight.Bold
                                                else
                                                    FontWeight.Normal
                                            )

                                            Text(
                                                text = price.date,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Column(
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            Text(
                                                text = String.format(
                                                    Locale.FRANCE,
                                                    "%.2f %s",
                                                    price.price,
                                                    price.currency
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCheapest)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )

                                            if (isCheapest) {
                                                Text(
                                                    text = "Moins cher",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider()
                                }

                                if (comparison.prices.size == 1) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Ajoute ce produit dans un autre magasin pour comparer les prix.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    val difference =
                                        comparison.prices.maxOf { it.price } -
                                            comparison.prices.minOf { it.price }

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        text = "Écart entre magasins : " +
                                            String.format(
                                                Locale.FRANCE,
                                                "%.2f %s",
                                                difference,
                                                selectedCurrency
                                            ),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
