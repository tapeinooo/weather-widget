package com.example.weatherwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 送った位置を、画面が消えたら元に戻す。
 * 画面が消えた知らせはアプリが動いている間しか受け取れないので、
 * アプリが止められていた場合に備えて、最後に送ってから一定時間で戻す予約もしておく。
 */
object OffsetReset {
    private const val FALLBACK = "reset_offset"
    private const val FALLBACK_MINUTES = 10L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registered = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            unregister(app)
            WorkManager.getInstance(app).cancelUniqueWork(FALLBACK)
            val pending = goAsync()
            scope.launch {
                try {
                    WeatherWidget.resetOffsets(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    @Synchronized
    fun arm(context: Context) {
        val app = context.applicationContext
        if (!registered) {
            ContextCompat.registerReceiver(
                app, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        val request = OneTimeWorkRequestBuilder<ResetWorker>()
            .setInitialDelay(FALLBACK_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(FALLBACK, ExistingWorkPolicy.REPLACE, request)
    }

    @Synchronized
    private fun unregister(app: Context) {
        if (!registered) return
        runCatching { app.unregisterReceiver(screenOff) }
        registered = false
    }

    class ResetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            unregister(applicationContext)
            WeatherWidget.resetOffsets(applicationContext)
            return Result.success()
        }
    }
}
