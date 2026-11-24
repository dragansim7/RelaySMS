package com.example.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.RelayApplication
import java.util.concurrent.TimeUnit

class TempNumberSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RelayApplication ?: return Result.success()
        return try {
            app.repository.syncAllAutoTempNumbers(force = false)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

class TempSyncScheduler(private val context: Context) {

    fun syncSchedule(intervalMinutes: Int, hasAutoTempNumbers: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!hasAutoTempNumbers || intervalMinutes <= 0) {
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val clampedMinutes = intervalMinutes.coerceAtLeast(15).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TempNumberSyncWorker>(
            clampedMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "relay_temp_number_periodic_sync"
    }
}
