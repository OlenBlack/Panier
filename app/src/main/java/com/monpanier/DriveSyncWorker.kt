package com.monpanier.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!DriveSyncManager.isConnected(applicationContext)) {
            return Result.success()
        }

        return try {
            val token = DriveSyncManager.getSilentAccessToken(
                applicationContext
            )

            DriveSyncManager.synchronize(
                applicationContext,
                token
            )

            Result.success()

        } catch (e: DriveAuthorizationRequiredException) {
            DriveSyncManager.setStatus(
                applicationContext,
                "Reconnecte Google Drive dans les paramètres"
            )
            Result.success()

        } catch (e: Exception) {
            DriveSyncManager.setStatus(
                applicationContext,
                "Synchronisation en attente : ${e.localizedMessage ?: "erreur réseau"}"
            )
            Result.retry()
        }
    }

    companion object {

        private const val UNIQUE_ONETIME = "mon_panier_drive_sync"
        private const val UNIQUE_PERIODIC = "mon_panier_drive_periodic"

        private fun networkConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONETIME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<DriveSyncWorker>(
                    12,
                    TimeUnit.HOURS
                )
                    .setConstraints(networkConstraints())
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
