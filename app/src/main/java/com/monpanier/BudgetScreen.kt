
package com.monpanier.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BudgetScreen(
    purchases: List<PurchaseEntity>,
    budgets: List<BudgetEntity>,
    dao: PurchaseDao,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val currentMonth = remember {
        SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
    }

    val currentBudget = budgets.firstOrNull {
        it.month == currentMonth
    }

    var amountText by remember(currentBudget?.amount) {
        mutableStateOf(
            currentBudget?.amount?.toString() ?: ""
        )
    }

    var message by remember {
        mutableStateOf("")
    }

    val spent = purchases
        .filter {
            it.receiptDate.startsWith(currentMonth) &&
            it.currency.equals("DZD", ignoreCase = true)
        }
        .sumOf { it.total }

    val budgetAmount = currentBudget?.amount ?: 0.0

    val remaining = budgetAmount - spent

    val progress = if (budgetAmount > 0) {
        (spent / budgetAmount).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    val numberFormat = remember {
        NumberFormat.getNumberInstance(Locale.FRANCE)
    }

    fun formatAmount(value: Double): String {
        return numberFormat.format(value) + " DZD"
    }

    val monthLabel = remember {
        SimpleDateFormat("MMMM yyyy", Locale.FRENCH)
            .format(Date())
            .replaceFirstChar { it.uppercase() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            text = "Budget mensuel",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "Définir mon budget (DZD)",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        message = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("Montant mensuel")
                    },
                    placeholder = {
                        Text("Exemple : 50000")
                    },
                    singleLine = true
                )

                Button(
                    onClick = {
                        val amount = amountText
                            .replace(" ", "")
                            .replace(",", ".")
                            .toDoubleOrNull()

                        if (amount == null || amount <= 0) {
                            message = "Veuillez saisir un montant valide."
                        } else {
                            scope.launch {
                                try {
                                    dao.saveBudget(
                                        BudgetEntity(
                                            id = currentBudget?.id ?: 0,
                                            month = currentMonth,
                                            amount = amount
                                        )
                                    )
                                    message = "Budget enregistré."
                                } catch (e: Exception) {
                                    message = "Erreur lors de l'enregistrement."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enregistrer le budget")
                }

                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "Suivi du mois",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Budget : ${formatAmount(budgetAmount)}")

                Text("Dépenses : ${formatAmount(spent)}")

                Text(
                    text = "Reste : ${formatAmount(remaining)}",
                    color = if (remaining < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )

                Text(
                    text = "Utilisation : ${(progress * 100).toInt()} %"
                )

                if (budgetAmount > 0 && spent >= budgetAmount) {
                    Text(
                        text = "Attention : votre budget mensuel est dépassé !",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (budgetAmount > 0 && spent >= budgetAmount * 0.8) {
                    Text(
                        text = "Attention : vous avez utilisé au moins 80 % de votre budget.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (budgetAmount <= 0) {
                    Text(
                        text = "Définissez votre budget pour commencer le suivi."
                    )
                }
            }
        }

        Text(
            text = "Historique des budgets",
            style = MaterialTheme.typography.titleLarge
        )

        if (budgets.isEmpty()) {
            Text("Aucun budget enregistré pour le moment.")
        } else {
            budgets.forEach { budget ->

                val budgetMonthLabel = try {
                    val date = SimpleDateFormat(
                        "yyyy-MM",
                        Locale.getDefault()
                    ).parse(budget.month)

                    date?.let {
                        SimpleDateFormat(
                            "MMMM yyyy",
                            Locale.FRENCH
                        ).format(it).replaceFirstChar { c ->
                            c.uppercase()
                        }
                    } ?: budget.month
                } catch (e: Exception) {
                    budget.month
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = budgetMonthLabel,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(formatAmount(budget.amount))
                        }

                        TextButton(
                            onClick = {
                                scope.launch {
                                    dao.deleteBudget(budget.id)
                                }
                            }
                        ) {
                            Text(
                                text = "Supprimer",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
