package com.monpanier.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun DriveSyncObserver() {
    val context = LocalContext.current

    val database = remember {
        PurchaseDatabase.get(context)
    }

    DisposableEffect(database) {
        val observer =
            object : androidx.room.InvalidationTracker.Observer(
                "purchases",
                "stores",
                "monthly_budgets"
            ) {
                override fun onInvalidated(
                    tables: Set<String>
                ) {
                    DriveSyncManager.enqueueSync(context)
                }
            }

        database.invalidationTracker.addObserver(observer)

        onDispose {
            database.invalidationTracker.removeObserver(observer)
        }
    }
}
