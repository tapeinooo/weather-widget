package com.example.weatherwidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Date
import java.util.Locale

class WeatherWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, WIDER))

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 新しく置いたウィジェットには、アプリが持っている最新の予報を渡す
        copyStoreToState(context, id)
        if (WeatherStore(context).forecastJson == null) WeatherWorker.refreshNow(context)
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val WIDER = DpSize(330.dp, 110.dp)

        val KEY_JSON = stringPreferencesKey("json")
        val KEY_PLACE = stringPreferencesKey("place")
        val KEY_FETCHED_AT = longPreferencesKey("fetchedAt")

        private suspend fun copyStoreToState(context: Context, id: GlanceId) {
            val store = WeatherStore(context)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_PLACE] = store.place.name
                    this[KEY_FETCHED_AT] = store.fetchedAt
                    val json = store.forecastJson
                    if (json != null) this[KEY_JSON] = json else remove(KEY_JSON)
                }
            }
        }

        /** 置かれているすべてのウィジェットに最新の予報を渡して描き直す。 */
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

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WeatherWorker.refreshNow(context)
    }
}

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val weather = prefs[WeatherWidget.KEY_JSON]?.let { runCatching { WeatherApi.parseForecast(it) }.getOrNull() }
    val place = prefs[WeatherWidget.KEY_PLACE] ?: WeatherStore.DEFAULT.name
    val fetchedAt = prefs[WeatherWidget.KEY_FETCHED_AT] ?: 0L
    val width = LocalSize.current.width

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (weather == null) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(place, style = textStyle(14, bold = true))
                Text("読み込み中…", style = subStyle(12))
            }
            return@Box
        }

        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            CurrentWeather(weather, place, fetchedAt)
            if (width >= 250.dp) {
                Spacer(GlanceModifier.width(8.dp))
                val days = if (width >= 330.dp) 7 else 5
                Row(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    weather.daily.take(days).forEachIndexed { i, day ->
                        DayColumn(day, isToday = i == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentWeather(weather: Weather, place: String, fetchedAt: Long) {
    val today = weather.daily.firstOrNull()
    Column(modifier = GlanceModifier.width(96.dp).fillMaxHeight()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(place, style = subStyle(12), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(
                "↻",
                style = subStyle(14),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WeatherCodes.emoji(weather.code, weather.isDay), style = textStyle(28))
            Spacer(GlanceModifier.width(4.dp))
            Text("${weather.temperature.toInt()}°", style = textStyle(28, bold = true))
        }
        Text(WeatherCodes.label(weather.code), style = textStyle(12), maxLines = 1)
        if (today != null) {
            Text("${today.max.toInt()}° / ${today.min.toInt()}°", style = subStyle(12))
        }
        if (fetchedAt > 0) {
            Text(
                SimpleDateFormat("H:mm 更新", Locale.JAPAN).format(Date(fetchedAt)),
                style = subStyle(10),
            )
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.DayColumn(day: DailyForecast, isToday: Boolean) {
    val label = if (isToday) {
        "今日"
    } else {
        runCatching {
            LocalDate.parse(day.date).dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.JAPAN)
        }.getOrDefault("")
    }
    Column(
        modifier = GlanceModifier.defaultWeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = subStyle(11))
        Text(WeatherCodes.emoji(day.code), style = textStyle(18))
        Text("${day.max.toInt()}°", style = textStyle(12, bold = true))
        Text("${day.min.toInt()}°", style = subStyle(11))
        day.precipProbability?.let { Text("$it%", style = subStyle(10)) }
    }
}

@Composable
private fun textStyle(size: Int, bold: Boolean = false) = TextStyle(
    color = GlanceTheme.colors.onSurface,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

@Composable
private fun subStyle(size: Int) = TextStyle(
    color = GlanceTheme.colors.onSurfaceVariant,
    fontSize = size.sp,
)
