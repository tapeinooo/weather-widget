package com.example.weatherwidget

import android.content.Context
import android.util.Log
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

/** 予報を取り直して保存し、ウィジェットを描き直す。 */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = WeatherStore(applicationContext)
        return try {
            val place = store.place
            if (!store.jmaAreaChecked) store.saveJmaArea(JmaApi.resolveArea(place.lat, place.lon))
            val area = store.jmaArea
            // 日本の中は気象庁。日本の外か、気象庁から取れないときは Open-Meteo
            val forecast = area?.let { a ->
                runCatching { JmaApi.fetchForecast(a, store.loadForecast()) }
                    .onFailure { Log.w(TAG, "気象庁の予報を読めませんでした", it) }
                    .getOrNull()
            } ?: WeatherApi.fetchForecast(place.lat, place.lon)
            store.saveForecast(forecast.toJson(), System.currentTimeMillis())
            WeatherWidget.pushToAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "予報を更新できませんでした", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "WeatherWorker"
        const val PERIODIC = "weather_periodic"
        const val NOW = "weather_now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
