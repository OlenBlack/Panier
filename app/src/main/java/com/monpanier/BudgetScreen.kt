
package com.monpanier.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BudgetScreen(
    pad: PaddingValues,
    purchases: List<PurchaseEntity>,
    budgets: List<BudgetEntity>,
    fg: Color,
    card: Color,
    onSaveBudget: (BudgetEntity) -> Unit,
    onDeleteBudget: (BudgetEntity) -> Unit
) {
    val currentMonth = remember {
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }

    val currentBudget = budgets.firstOrNull {
        it.month == currentMonth
    }

    var amountText by remember(currentBudget?.id, currentBudget?.amount) {
        mutableStateOf(
            currentBudget?.amount?.toString() ?: ""
        )
    }

    var error by remember { mutableStateOf("") }

    val spent = purchases
        .filter {
            it.currency == "DZD" &&
            it.receiptDate.startsWith(currentMonth)
        }
        .sumOf { it.total }

    val budgetAmount = currentBudget?.amount ?: 0.0

    val remaining = budgetAmount - spent

    val progress = if (budgetAmount > 0) {
        (spent / budgetAmount).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    val percentage = if (budgetAmount > 0) {
        (spent / budgetAmount) * 100
    } else {
        0.0
    }

    val monthLabel = remember(currentMonth) {
        runCatching {
            val date = SimpleDateFormat(
                "yyyy-MM",
                Locale.US
            ).parse(currentMonth)

            SimpleDateFormat(
                "MMMM yyyy",
                Locale.FRANCE
            ).format(date ?: Date())
        }.getOrDefault(currentMonth)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text(
            "Budget mensuel",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Text(
            monthLabel.replaceFirstChar {
                it.uppercase()
            },
            color = fg.copy(alpha = 0.7f)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Définir mon budget du mois",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("Budget en DZD")
                    },
                    placeholder = {
                        Text("Ex. : 50000")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = Color.Red
                    )
                }

                Button(
                    onClick = {
                        val amount = amountText
                            .replace(',', '.')
                            .toDoubleOrNull()

                        if (amount == null || amount <= 0) {
                            error = "Saisis un montant supérieur à zéro."
                        } else {
                            onSaveBudget(
                                BudgetEntity(
                                    id = currentBudget?.id ?: 0,
                                    month = currentMonth,
                                    amount = amount
                                )
                            )
                            error = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enregistrer le budget")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color(0xFF078A4B)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        "Suivi de mes dépenses",
                        fontWeight = FontWeight.Bold,
                        color = fg
                    )
                }

                BudgetLine(
                    "Budget fixé",
                    budgetAmount,
                    fg
                )

                BudgetLine(
                    "Dépenses réalisées",
                    spent,
                    fg
                )

                BudgetLine(
                    "Montant restant",
                    remaining,
                    if (remaining < 0) Color(0xFFD32F2F)
                    else Color(0xFF078A4B)
                )

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = when {
                        percentage >= 100 -> Color(0xFFD32F2F)
                        percentage >= 80 -> Color(0xFFFF9800)
                        else -> Color(0xFF078A4B)
                    },
                    trackColor = fg.copy(alpha = 0.1f)
                )

                Text(
                    "Budget utilisé : ${"%.1f".format(Locale.FRANCE, percentage)} %",
                    color = fg,
                    fontWeight = FontWeight.SemiBold
                )

                when {
                    budgetAmount <= 0 -> {
                        Text(
                            "Définis ton budget pour commencer le suivi.",
                            color = fg.copy(alpha = 0.7f)
                        )
                    }

                    percentage >= 100 -> {
                        Text(
                            "Attention : ton budget mensuel est dépassé !",
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    percentage >= 80 -> {
                        Text(
                            "Attention : tu as utilisé au moins 80 % de ton budget.",
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    else -> {
                        Text(
                            "Tu respectes ton budget pour le moment.",
                            color = Color(0xFF078A4B)
                        )
                    }
                }
            }
        }

        Text(
            "Historique des budgets",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                budgets.filter { it.month != currentMonth },
                key = { it.id }
            ) { budget ->

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = card
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                budget.month,
                                fontWeight = FontWeight.Bold,
                                color = fg
                            )

                            Text(
                                "${"%.2f".format(Locale.FRANCE, budget.amount)} DZD",
                                color = fg.copy(alpha = 0.7f)
                            )
                        }

                        IconButton(
                            onClick = {
                                onDeleteBudget(budget)
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Supprimer ce budget",
                                tint = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
            }

            if (budgets.isEmpty() ||
                budgets.none { it.month != currentMonth }
            ) {
                item {
                    Text(
                        "Aucun ancien budget enregistré.",
                        color = fg.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetLine(
    label: String,
    amount: Double,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color)

        Text(
            "${"%.2f".format(Locale.FRANCE, amount)} DZD",
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
