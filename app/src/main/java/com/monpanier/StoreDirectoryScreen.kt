
package com.monpanier.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StoreDirectoryScreen(
    pad: PaddingValues,
    stores: List<StoreEntity>,
    purchases: List<PurchaseEntity>,
    currencyName: String,
    fg: Color,
    card: Color,
    dao: PurchaseDao,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editingStore by remember { mutableStateOf<StoreEntity?>(null) }
    var deletingStore by remember { mutableStateOf<StoreEntity?>(null) }

    var storeName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    

    val currencyPurchases = remember(purchases, currencyName) {
        purchases.filter { it.currency == currencyName }
    }

    val storeTotals = remember(stores, currencyPurchases) {
    stores.associate { store ->
        store.name.trim().lowercase(Locale.ROOT) to
            currencyPurchases
                .filter {
                    it.store.trim().equals(
                        store.name.trim(),
                        ignoreCase = true
                    )
                }
                .sumOf { it.total }
    }
    }

    val overallTotal = storeTotals.values.sum()

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Répertoire des magasins",
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )

            Icon(
                Icons.Default.Store,
                contentDescription = null,
                tint = Color(0xFF078A4B)
            )
        }

        Text(
            "Les magasins restent enregistrés même si tu supprimes leurs tickets.",
            color = fg.copy(alpha = 0.7f)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "Dépenses dans les magasins",
                    color = fg.copy(alpha = 0.7f)
                )

                Text(
                    String.format(Locale.getDefault(), "%.2f", overallTotal) +
                        " $currencyName",
                    color = fg,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Button(
            onClick = {
                storeName = ""
                showAdd = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Ajouter un magasin")
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = Color(0xFF078A4B)
            )
        }

        if (stores.isEmpty()) {
            Text(
                "Aucun magasin enregistré.",
                color = fg.copy(alpha = 0.7f)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(stores, key = { it.id }) { store ->

                val total = storeTotals[
                    store.name.trim().lowercase(Locale.ROOT)
                ] ?: 0.0

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = card
                    )
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Store,
                                contentDescription = null,
                                tint = Color(0xFF078A4B)
                            )

                            Spacer(Modifier.width(12.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    store.name,
                                    color = fg,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    "Total des achats : " +
                                        String.format(
                                            Locale.getDefault(),
                                            "%.2f",
                                            total
                                        ) + " $currencyName",
                                    color = Color(0xFF078A4B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            IconButton(
                                onClick = {
                                    storeName = store.name
                                    editingStore = store
                                }
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Modifier"
                                )
                            }

                            IconButton(
                                onClick = {
                                    deletingStore = store
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Supprimer",
                                    tint = Color(0xFFD32F2F)
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retour aux paramètres")
        }
    }

    if (showAdd || editingStore != null) {
        val isEditing = editingStore != null

        AlertDialog(
            onDismissRequest = {
                showAdd = false
                editingStore = null
            },
            title = {
                Text(
                    if (isEditing) "Modifier le magasin"
                    else "Ajouter un magasin"
                )
            },
            text = {
                OutlinedTextField(
                    value = storeName,
                    onValueChange = { storeName = it },
                    label = { Text("Nom du magasin") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = storeName.trim()

                        if (name.isNotBlank()) {
                            scope.launch {
                                runCatching {
                                    if (isEditing) {
                                        val old = editingStore!!
                                        dao.updateStore(
                                            old.copy(name = name)
                                        )
                                    } else {
                                        dao.insertStoreIfAbsent(
                                            StoreEntity(name = name)
                                        )
                                    }
                                }.onSuccess {
                                    message = if (isEditing)
                                        "Magasin modifié."
                                    else
                                        "Magasin ajouté."

                                    showAdd = false
                                    editingStore = null
                                    storeName = ""
                                }.onFailure {
                                    message =
                                        "Erreur : ce nom existe peut-être déjà."
                                }
                            }
                        }
                    }
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        editingStore = null
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }

    deletingStore?.let { store ->
        AlertDialog(
            onDismissRequest = {
                deletingStore = null
            },
            title = {
                Text("Supprimer ce magasin ?")
            },
            text = {
                Text(
                    "Le magasin sera retiré du répertoire. " +
                        "Les anciens tickets et leurs achats resteront conservés."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                dao.deleteStore(store.id)
                            }.onSuccess {
                                message = "Magasin supprimé du répertoire."
                            }.onFailure {
                                message = "Erreur : ${it.message}"
                            }

                            deletingStore = null
                        }
                    }
                ) {
                    Text(
                        "Supprimer",
                        color = Color(0xFFD32F2F)
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingStore = null
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }
}
