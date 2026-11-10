package com.example.weatherwidget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WeatherWorker.schedulePeriodic(this)
        // ウィジェットの「編集」から開かれたときは、閉じてもウィジェットが消えないよう OK を返しておく
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { WeatherStore(context) }
    val scope = rememberCoroutineScope()

    var place by remember { mutableStateOf(store.place) }
    var forecast by remember { mutableStateOf(store.loadForecast()) }
    var message by remember { mutableStateOf<String?>(store.lastError?.let { "前回の更新に失敗しました：$it" }) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var days by remember { mutableStateOf(store.days) }
    var textScale by remember { mutableStateOf(store.textScale) }

    fun applyDisplay() {
        store.days = days
        store.textScale = textScale
        WidgetRenderer.updateAll(context, "設定")
    }

    val workInfos by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WeatherWorker.NOW)
    }.collectAsState(initial = emptyList())
    val refreshing = workInfos.any { !it.state.isFinished }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            forecast = store.loadForecast()
            store.lastError?.let { message = "更新に失敗しました：$it" }
        }
    }

    fun choose(p: Place) {
        store.setPlace(p)
        place = p
        forecast = null
        results = emptyList()
        query = ""
        message = null
        WeatherWorker.refreshNow(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            message = "位置情報の許可がないため、現在地を使えません"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            message = "現在地を調べています…"
            val p = Locator.currentPlace(context)
            if (p != null) choose(p) else message = "現在地を取得できませんでした（位置情報がオフかもしれません）"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("たっぴぃず天気", style = MaterialTheme.typography.headlineSmall)

        Text("場所：${place.name}", style = MaterialTheme.typography.titleMedium)
        val today = forecast?.let { f -> f.days.firstOrNull { it.date == f.today() } }
        Text(
            when {
                refreshing -> "更新しています…"
                today == null -> "まだ予報がありません"
                else -> "今日：${today.label}　${today.max ?: "--"}℃ / ${today.min ?: "--"}℃"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        forecast?.let {
            Text(
                if (it.source == Forecast.SOURCE_JMA) "出典：気象庁ホームページ" else "出典：Open-Meteo（気象庁の予報がない地点のため）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                Text("現在地にする")
            }
            OutlinedButton(onClick = { message = null; WeatherWorker.refreshNow(context) }) {
                Text("今すぐ更新")
            }
        }

        HorizontalDivider()
        Text("表示", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("表示する日数（今日を含む）", modifier = Modifier.weight(1f))
            OutlinedButton(
                enabled = days > WeatherStore.MIN_DAYS,
                onClick = { days--; applyDisplay() },
            ) { Text("−") }
            Text("$days 日", modifier = Modifier.width(48.dp))
            OutlinedButton(
                enabled = days < WeatherStore.MAX_DAYS,
                onClick = { days++; applyDisplay() },
            ) { Text("＋") }
        }
        Text("文字の大きさ：$textScale%")
        Slider(
            value = textScale.toFloat(),
            onValueChange = { textScale = (it / 5).toInt() * 5 },
            onValueChangeFinished = { applyDisplay() },
            valueRange = WeatherStore.MIN_SCALE.toFloat()..WeatherStore.MAX_SCALE.toFloat(),
            steps = (WeatherStore.MAX_SCALE - WeatherStore.MIN_SCALE) / 5 - 1,
        )
        Text(
            "100% はウィジェットの幅と高さにちょうど収まる大きさです。端末の文字サイズの設定には左右されません。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { textScale = 100; applyDisplay() }) { Text("100% に戻す") }

        HorizontalDivider()
        Text("地名で探す", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("例：渋谷、札幌") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = query.isNotBlank(),
                onClick = {
                    scope.launch {
                        results = runCatching { WeatherApi.searchPlaces(query.trim()) }
                            .onFailure { message = "検索できませんでした" }
                            .getOrDefault(emptyList())
                        if (results.isEmpty() && message == null) message = "見つかりませんでした"
                    }
                },
            ) { Text("検索") }
        }
        results.forEach { p ->
            Text(
                p.name,
                modifier = Modifier.fillMaxWidth().clickable { choose(p) }.padding(vertical = 12.dp),
            )
            HorizontalDivider()
        }

        Text(
            "ホーム画面を長押し →「ウィジェット」→「たっぴぃず天気」で置けます。1時間ごとに自動で更新します。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 文字の大きさが勝手に変わる原因を調べるための記録（新しい順）
        HorizontalDivider()
        var showSizeLog by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showSizeLog = !showSizeLog }) {
            Text(if (showSizeLog) "文字の大きさの記録を閉じる" else "文字の大きさの記録（確認用）")
        }
        if (showSizeLog) {
            WidgetRenderer.sizeLog(context).ifEmpty { listOf("まだ記録がありません") }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
