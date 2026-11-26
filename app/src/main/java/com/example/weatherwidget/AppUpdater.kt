package com.example.weatherwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * アプリ自身の更新。GitHub の最新リリース（タグ「v1.22」など。タグは versionName と同じにする決まり）を見て、
 * 今の版より新しければ添付の APK を落として、Android の PackageInstaller で入れる。
 * 確認画面が要るとき（初めての更新や、端末が求めるとき）は、画面を開いていればそのまま出し、裏で見つけたときは通知で知らせる。
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val LATEST = "https://api.github.com/repos/tapeinooo/weather-widget/releases/latest"
    private const val PREFS = "app_update"
    private const val KEY_ATTEMPTED = "attempted_tag"
    private const val KEY_STATUS = "status"
    const val CHANNEL = "app_update"
    const val EXTRA_INTERACTIVE = "interactive"

    class Release(val tag: String, val version: String, val apkUrl: String)

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** "1.10" と "1.9" を数としてくらべる（a が新しければ true）。 */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** 最後の結果（入れたあとの失敗など、画面の外で起きたことも含む）。 */
    fun status(context: Context): String? = prefs(context).getString(KEY_STATUS, null)

    private fun setStatus(context: Context, text: String) = prefs(context).edit().putString(KEY_STATUS, text).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "tapei-weather")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val conn = open(LATEST)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            // 添付の名前は変わることがあるので、.apk で終わるものを使う
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw IOException("$tag に APK が添付されていません")
            Release(tag, tag.removePrefix("v"), apk.getString("browser_download_url"))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 新しい版があれば入れる。interactive は設定画面のボタンから（画面が見えている）。
     * 裏の確認では、一度試した版はもう試さない（壊れた添付で何度も落とさないように）。ボタンからは何度でも試す。
     * 結果を文章で返す（画面に出す）。
     */
    suspend fun check(context: Context, interactive: Boolean): String {
        val app = context.applicationContext
        val message = try {
            val release = latest()
            val current = currentVersion(app)
            when {
                !isNewer(release.version, current) -> "最新の版です（v$current）"
                !interactive && prefs(app).getString(KEY_ATTEMPTED, null) == release.tag ->
                    "${release.tag} は前に試したので、自動では入れません（「更新を確認」で入れられます）"
                else -> {
                    prefs(app).edit().putString(KEY_ATTEMPTED, release.tag).apply()
                    install(app, release, interactive)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新を確認できませんでした", e)
            "更新を確認できませんでした：${e.message ?: e}"
        }
        setStatus(app, message)
        return message
    }

    private suspend fun install(context: Context, release: Release, interactive: Boolean): String = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("ダウンロード：HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }

        // 入れる前に中身を確かめる（古い APK が添付されていると、入れても版が上がらない）
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.path, 0) ?: throw IOException("APK を読めませんでした")
        val installed = pm.getPackageInfo(context.packageName, 0)
        if (archive.packageName != context.packageName) throw IOException("別のアプリの APK です（${archive.packageName}）")
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        @Suppress("DEPRECATION")
        val oldCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode else installed.versionCode.toLong()
        if (newCode <= oldCode) throw IOException("${release.tag} の APK の中身が新しくありません（版番号 $newCode）")

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // このアプリ自身が前に入れた版なら、確認なしで入れられる（Android 12 以降）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("app.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // 結果を受け取る。システムが中身を書き込むので、Android 12 以降は MUTABLE にする
            val intent = Intent(context, UpdateReceiver::class.java).putExtra(EXTRA_INTERACTIVE, interactive)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
        file.delete()
        "${release.tag} を入れています…"
    }

    /** 確認画面を出せないとき（裏で見つけたとき）に、押すと確認画面が開く通知を出す。 */
    fun notifyConfirm(context: Context, confirm: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("たぺいの天気の新しい版があります")
            .setContentText("押すと更新します")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(1, notification) }.onFailure { Log.w(TAG, "通知を出せませんでした", it) }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** 入れた結果を受け取る。確認画面が要るときは、画面からなら開き、裏からなら通知を出す。 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = context.applicationContext.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                if (intent.getBooleanExtra(AppUpdater.EXTRA_INTERACTIVE, false)) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { AppUpdater.notifyConfirm(context, confirm) }
                } else {
                    AppUpdater.notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.edit().putString("status", "更新しました").apply()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "状態 $status"
                prefs.edit().putString("status", "更新できませんでした：$message").apply()
            }
        }
    }
}

/** 6時間ごとに、新しい版がないかを裏で確かめる。 */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppUpdater.check(applicationContext, interactive = false)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "app_update_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
