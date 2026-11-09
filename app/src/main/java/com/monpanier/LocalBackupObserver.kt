
package com.monpanier.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LocalBackupObserver() {

    val context = LocalContext.current.applicationContext

    val database = remember {
        PurchaseDatabase.get(context)
    }

    DisposableEffect(database) {

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

        var pendingJob: Job? = null

        val observer = object : InvalidationTracker.Observer(
            "purchases",
            "stores",
            "monthly_budgets"
        ) {

            override fun onInvalidated(tables: Set<String>) {

                if (!LocalBackupManager.hasBackupFolder(context)) {
                    return
                }

                pendingJob?.cancel()

                pendingJob = scope.launch {

                    delay(1000)

                    try {
                        LocalBackupManager.backup(context)

                    } catch (e: Exception) {

                        android.util.Log.e(
                            "LocalBackup",
                            "Sauvegarde automatique impossible",
                            e
                        )
                    }
                }
            }
        }

        database.invalidationTracker.addObserver(observer)

        onDispose {
            pendingJob?.cancel()
            database.invalidationTracker.removeObserver(observer)
            scope.cancel()
        }
    }
}
