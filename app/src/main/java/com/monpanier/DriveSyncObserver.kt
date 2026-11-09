
package com.monpanier.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.InvalidationTracker

@Composable
fun DriveSyncObserver() {

    val context = LocalContext.current

    val database = remember {
        PurchaseDatabase.get(context)
    }

    DisposableEffect(database) {

        DriveSyncManager.schedulePeriodic(context)

        val observer = object : InvalidationTracker.Observer(
            "purchases",
            "stores",
            "monthly_budgets"
        ) {
            override fun onInvalidated(tables: Set<String>) {
                DriveSyncManager.enqueueSync(context)
            }
        }

        database.invalidationTracker.addObserver(observer)

        onDispose {
            database.invalidationTracker.removeObserver(observer)
        }
    }
}
