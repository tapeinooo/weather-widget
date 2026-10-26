package com.example.weatherwidget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

class WeatherWidget : GlanceAppWidget() {

    // 文字の大きさを実際の幅・高さから決めるので、大きさはそのまま受け取る
    override val sizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 新しく置いたウィジェットには、アプリが持っている最新の予報と設定を渡す
        copyStoreToState(context, id)
        if (WeatherStore(context).forecastJson == null) WeatherWorker.refreshNow(context)
        provideContent { WidgetContent() }
    }

    companion object {
        val KEY_JSON = stringPreferencesKey("json")
        val KEY_PLACE = stringPreferencesKey("place")
        val KEY_FETCHED_AT = longPreferencesKey("fetchedAt")
        val KEY_DAYS = intPreferencesKey("days")
        val KEY_TEXT_SCALE = intPreferencesKey("textScale")

        private suspend fun copyStoreToState(context: Context, id: GlanceId) {
            val store = WeatherStore(context)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_PLACE] = store.place.name
                    this[KEY_FETCHED_AT] = store.fetchedAt
                    this[KEY_DAYS] = store.days
                    this[KEY_TEXT_SCALE] = store.textScale
                    val json = store.forecastJson
                    if (json != null) this[KEY_JSON] = json else remove(KEY_JSON)
                }
            }
        }

        /** 置かれているすべてのウィジェットに最新の予報と設定を渡して描き直す。 */
        suspend fun pushToAll(context: Context) {
            val widget = WeatherWidget()
            GlanceAppWidgetManager(context).getGlanceIds(WeatherWidget::class.java).forEach { id ->
                copyStoreToState(context, id)
                widget.update(context, id)
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WeatherWorker.schedulePeriodic(context)
        WeatherWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WeatherWorker.cancelPeriodic(context)
    }
}

private val BACKGROUND = Color(0xE6202124)
private val WHITE = ColorProvider(Color(0xFFEDEDED))
private val GRAY = ColorProvider(Color(0xFFBDBDBD))
private val RED = ColorProvider(Color(0xFFF2635B))
private val BLUE = ColorProvider(Color(0xFF5B95F2))

/**
 * 文字の大きさの決め方。1 単位＝日付の文字の大きさ（dp）。
 * 1列の幅に「20℃ / 10℃」が収まり、縦に全部の行が収まる大きさを基本にし、設定の％を掛ける。
 * 端末の文字サイズ設定（fontScale）で割り戻して、設定に左右されないようにする。
 */
private class Metrics(width: Dp, height: Dp, columns: Int, scalePercent: Int, private val fontScale: Float) {
    val padding = 8.dp
    val unit: Float = run {
        val columnWidth = (width.value - padding.value * 2) / columns.coerceAtLeast(1)
        val byWidth = columnWidth / 5.6f
        val byHeight = (height.value - padding.value * 2) / 7.4f
        minOf(byWidth, byHeight).coerceIn(6f, 48f) * scalePercent / 100f
    }

    fun text(factor: Float): TextUnit = (unit * factor / fontScale).sp
    fun dp(factor: Float): Dp = (unit * factor).dp
}

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val weather = prefs[WeatherWidget.KEY_JSON]?.let { runCatching { WeatherApi.parseForecast(it) }.getOrNull() }
    val place = prefs[WeatherWidget.KEY_PLACE] ?: WeatherStore.DEFAULT.name
    val days = weather?.daily?.take(prefs[WeatherWidget.KEY_DAYS] ?: WeatherStore.DEFAULT_DAYS).orEmpty()
    val size = LocalSize.current
    val context = LocalContext.current
    val m = Metrics(
        width = size.width,
        height = size.height,
        columns = days.size.coerceAtLeast(1),
        scalePercent = prefs[WeatherWidget.KEY_TEXT_SCALE] ?: 100,
        fontScale = context.resources.configuration.fontScale,
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(BACKGROUND)
            .padding(m.padding)
            .clickable(tapAction(context)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            place,
            maxLines = 1,
            style = TextStyle(color = WHITE, fontSize = m.text(1.15f), textAlign = TextAlign.Center),
            modifier = GlanceModifier.fillMaxWidth(),
        )
        Spacer(GlanceModifier.height(m.dp(0.3f)))
        if (days.isEmpty()) {
            Text("読み込み中…", style = TextStyle(color = GRAY, fontSize = m.text(1f)))
            return@Column
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            days.forEach { day ->
                Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.TopCenter) {
                    DayColumn(day, m)
                }
            }
        }
    }
}

private const val YAHOO_WEATHER = "jp.co.yahoo.android.weather.type1"

/** タップで Yahoo!天気 を開く。入っていなければこのアプリの設定画面を開く。 */
private fun tapAction(context: Context) =
    context.packageManager.getLaunchIntentForPackage(YAHOO_WEATHER)
        ?.let { actionStartActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        ?: actionStartActivity<MainActivity>()

@Composable
private fun DayColumn(day: DailyForecast, m: Metrics) {
    val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: day.date,
                maxLines = 1,
                style = TextStyle(color = WHITE, fontSize = m.text(1f)),
            )
            if (date != null) {
                Text(
                    " (" + date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.JAPAN) + ")",
                    maxLines = 1,
                    style = TextStyle(color = WHITE, fontSize = m.text(0.8f)),
                )
            }
        }
        Spacer(GlanceModifier.height(m.dp(0.2f)))
        WeatherIcons(day.icons, m)
        Spacer(GlanceModifier.height(m.dp(0.2f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day.max.toInt()}℃", maxLines = 1, style = TextStyle(color = RED, fontSize = m.text(1f)))
            Text(" / ", maxLines = 1, style = TextStyle(color = WHITE, fontSize = m.text(1f)))
            Text("${day.min.toInt()}℃", maxLines = 1, style = TextStyle(color = BLUE, fontSize = m.text(1f)))
        }
        day.precipProbability?.let { p ->
            Text(
                "$p%",
                maxLines = 1,
                style = TextStyle(
                    color = if (p >= 50) BLUE else WHITE,
                    fontSize = m.text(1f),
                    fontWeight = FontWeight.Normal,
                ),
            )
        }
    }
}

@Composable
private fun WeatherIcons(icons: List<WeatherCategory>, m: Metrics) {
    val iconSize = m.dp(if (icons.size > 1) 1.9f else 2.1f)
    Row(modifier = GlanceModifier.height(m.dp(2.1f)), verticalAlignment = Alignment.CenterVertically) {
        icons.forEachIndexed { i, category ->
            if (i > 0) {
                Image(
                    provider = ImageProvider(R.drawable.wx_arrow),
                    contentDescription = null,
                    modifier = GlanceModifier.size(m.dp(0.6f)),
                )
            }
            Image(
                provider = ImageProvider(category.icon),
                contentDescription = null,
                modifier = GlanceModifier.size(iconSize),
            )
        }
    }
}
