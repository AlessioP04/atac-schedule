package io.github.alessiop04.atacschedule.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.alessiop04.atacschedule.App
import java.util.concurrent.TimeUnit

/** Una volta al giorno: nuovo database (scaricato solo se il feed è cambiato) e nuova versione dell'app. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        app.prefs.checkForNewRelease()
        return when (app.timetable.update()) {
            is UpdateResult.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("timetable-update", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
