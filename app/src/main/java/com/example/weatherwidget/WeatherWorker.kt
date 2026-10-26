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
            val previous = store.loadForecast()
            // 日本の中は気象庁（過ぎた日はアメダスの観測）。日本の外か、気象庁から取れないときは Open-Meteo
            val jma = area?.let { a ->
                runCatching {
                    val f = JmaApi.fetchForecast(a, previous).withHistory(previous, JmaApi.today())
                    runCatching { JmaApi.addObservations(f, a) }
                        .onFailure { Log.w(TAG, "アメダスの観測を読めませんでした", it) }
                        .getOrDefault(f)
                }.onFailure { Log.w(TAG, "気象庁の予報を読めませんでした", it) }.getOrNull()
            }
            val forecast = jma
                ?: WeatherApi.fetchForecast(place.lat, place.lon).withHistory(previous, WeatherApi.today())
            store.saveForecast(forecast.toJson(), System.currentTimeMillis())
            store.lastError = null
            WeatherWidget.pushToAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "予報を更新できませんでした", e)
            store.lastError = e.toString().take(200)
            runCatching { WeatherWidget.pushToAll(applicationContext) }
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
