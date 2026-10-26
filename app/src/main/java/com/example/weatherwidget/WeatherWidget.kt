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
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.width
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
        // 読める予報がなければ（古い版の形のままのときも）取り直す
        if (WeatherStore(context).loadForecast() == null) WeatherWorker.refreshNow(context)
        provideContent { WidgetContent() }
    }

    companion object {
        val KEY_JSON = stringPreferencesKey("json")
        val KEY_PLACE = stringPreferencesKey("place")
        val KEY_FETCHED_AT = longPreferencesKey("fetchedAt")
        val KEY_DAYS = intPreferencesKey("days")
        val KEY_TEXT_SCALE = intPreferencesKey("textScale")
        val KEY_OFFSET = intPreferencesKey("offset")
        val KEY_ERROR = stringPreferencesKey("error")

        private suspend fun copyStoreToState(context: Context, id: GlanceId) {
            val store = WeatherStore(context)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_PLACE] = store.place.name
                    this[KEY_FETCHED_AT] = store.fetchedAt
                    this[KEY_DAYS] = store.days
                    this[KEY_TEXT_SCALE] = store.textScale
                    val error = store.lastError
                    if (error != null) this[KEY_ERROR] = error else remove(KEY_ERROR)
                    val json = store.forecastJson
                    if (json != null) this[KEY_JSON] = json else remove(KEY_JSON)
                }
            }
        }

        /** 送った位置を元（今日が左端）に戻す。送っていたウィジェットだけ描き直す。 */
        suspend fun resetOffsets(context: Context) {
            val widget = WeatherWidget()
            GlanceAppWidgetManager(context).getGlanceIds(WeatherWidget::class.java).forEach { id ->
                var changed = false
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                    changed = (prefs[KEY_OFFSET] ?: 0) != 0
                    prefs.toMutablePreferences().apply { remove(KEY_OFFSET) }
                }
                if (changed) widget.update(context, id)
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
private val DIM = ColorProvider(Color(0x55EDEDED))
private val TODAY_BACKGROUND = Color(0x33FFFFFF)

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

/** 送りボタンの幅。 */
private val ARROW_WIDTH = 22.dp

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val forecast = prefs[WeatherWidget.KEY_JSON]?.let { Forecast.fromJson(it) }
    val place = prefs[WeatherWidget.KEY_PLACE] ?: WeatherStore.DEFAULT.name
    val all = forecast?.days.orEmpty()
    val today = forecast?.today().orEmpty()
    val window = DayWindow(all, today, prefs[WeatherWidget.KEY_DAYS] ?: WeatherStore.DEFAULT_DAYS, prefs[WeatherWidget.KEY_OFFSET] ?: 0)
    val days = window.visible
    val size = LocalSize.current
    val context = LocalContext.current
    val m = Metrics(
        width = size.width - ARROW_WIDTH * 2,
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
            .padding(vertical = m.padding)
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
            val error = prefs[WeatherWidget.KEY_ERROR]
            Text(
                if (error == null) "読み込み中…" else "更新できませんでした",
                style = TextStyle(color = GRAY, fontSize = m.text(1f)),
            )
            if (error != null) {
                Text(error, maxLines = 3, style = TextStyle(color = DIM, fontSize = m.text(0.7f), textAlign = TextAlign.Center))
            }
            return@Column
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShiftButton("‹", -1, window.canGoBack, m)
            Row(modifier = GlanceModifier.defaultWeight()) {
                days.forEach { day ->
                    val isToday = day.date == today
                    Box(
                        modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier = (if (isToday) GlanceModifier.background(TODAY_BACKGROUND).cornerRadius(10.dp) else GlanceModifier)
                                .fillMaxWidth()
                                .padding(vertical = m.dp(0.2f)),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            DayColumn(day, m)
                        }
                    }
                }
            }
            ShiftButton("›", 1, window.canGoForward, m)
        }
    }
}

/** 左右の送りボタン。送れないときは薄くして押せないようにする。 */
@Composable
private fun ShiftButton(label: String, delta: Int, enabled: Boolean, m: Metrics) {
    val modifier = GlanceModifier.width(ARROW_WIDTH).height(m.dp(5.6f))
    Box(
        modifier = if (enabled) {
            modifier.clickable(actionRunCallback<ShiftAction>(actionParametersOf(ShiftAction.DELTA to delta)))
        } else {
            modifier
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = if (enabled) WHITE else DIM, fontSize = m.text(1.8f)))
    }
}

/**
 * 表示する日の範囲。offset は「今日を左端にした位置」からのずれ（日数）。
 * 過ぎた日は持っている分だけ、先の日は予報がある分だけ送れる。
 */
class DayWindow(all: List<DayForecast>, today: String, count: Int, offset: Int) {
    private val n = count.coerceIn(1, maxOf(all.size, 1))
    private val todayIndex = all.indexOfFirst { it.date >= today }.takeIf { it >= 0 } ?: 0
    private val lastStart = maxOf(all.size - n, 0)
    private val start = (todayIndex + offset).coerceIn(0, lastStart)

    val visible: List<DayForecast> = all.drop(start).take(n)
    val canGoBack = start > 0
    val canGoForward = start < lastStart

    /** delta 日送ったあとの offset（範囲の外には出さない）。 */
    fun shifted(delta: Int): Int = (start + delta).coerceIn(0, lastStart) - todayIndex
}

class ShiftAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA] ?: return
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val forecast = prefs[WeatherWidget.KEY_JSON]?.let { Forecast.fromJson(it) }
            val window = DayWindow(
                forecast?.days.orEmpty(),
                forecast?.today().orEmpty(),
                prefs[WeatherWidget.KEY_DAYS] ?: WeatherStore.DEFAULT_DAYS,
                prefs[WeatherWidget.KEY_OFFSET] ?: 0,
            )
            prefs.toMutablePreferences().apply { this[WeatherWidget.KEY_OFFSET] = window.shifted(delta) }
        }
        WeatherWidget().update(context, glanceId)
        OffsetReset.arm(context)
    }

    companion object {
        val DELTA = ActionParameters.Key<Int>("delta")
    }
}

private const val YAHOO_WEATHER = "jp.co.yahoo.android.weather.type1"

/** タップで Yahoo!天気 を開く。入っていなければこのアプリの設定画面を開く。 */
private fun tapAction(context: Context) =
    context.packageManager.getLaunchIntentForPackage(YAHOO_WEATHER)
        ?.let { actionStartActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        ?: actionStartActivity<MainActivity>()

@Composable
private fun DayColumn(day: DayForecast, m: Metrics) {
    val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
    // 過ぎた日（観測の結果）は日付を少し暗くする
    val dateColor = if (day.observed) GRAY else WHITE
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: day.date,
                maxLines = 1,
                style = TextStyle(color = dateColor, fontSize = m.text(1f)),
            )
            if (date != null) {
                Text(
                    " (" + date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.JAPAN) + ")",
                    maxLines = 1,
                    style = TextStyle(color = dateColor, fontSize = m.text(0.8f)),
                )
            }
        }
        Spacer(GlanceModifier.height(m.dp(0.2f)))
        IconRow(day.icons, m)
        Spacer(GlanceModifier.height(m.dp(0.2f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day.max ?: "--"}℃", maxLines = 1, style = TextStyle(color = RED, fontSize = m.text(1f)))
            Text(" / ", maxLines = 1, style = TextStyle(color = WHITE, fontSize = m.text(1f)))
            Text("${day.min ?: "--"}℃", maxLines = 1, style = TextStyle(color = BLUE, fontSize = m.text(1f)))
        }
        val bottom = if (day.observed) {
            // 過ぎた日は降水確率の代わりに降った量
            day.rain?.let { r -> String.format(Locale.US, "%.1fmm", r) to (r >= 1.0) }
        } else {
            day.pop?.let { p -> "$p%" to (p >= 50) }
        }
        bottom?.let { (text, wet) ->
            Text(
                text,
                maxLines = 1,
                style = TextStyle(color = if (wet) BLUE else WHITE, fontSize = m.text(1f)),
            )
        }
    }
}

@Composable
private fun IconRow(icons: WeatherIcons?, m: Metrics) {
    if (icons == null) {
        // 予報を持っていなかった日（観測だけ）
        Box(modifier = GlanceModifier.height(m.dp(2.1f)), contentAlignment = Alignment.Center) {
            Text("－", style = TextStyle(color = DIM, fontSize = m.text(1.2f)))
        }
        return
    }
    val sub = icons.sub
    val iconSize = m.dp(if (sub != null) 1.9f else 2.1f)
    Row(modifier = GlanceModifier.height(m.dp(2.1f)), verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(icons.main.icon),
            contentDescription = null,
            modifier = GlanceModifier.size(iconSize),
        )
        if (sub != null) {
            // 「後」は矢印でつなぎ、「時々」「一時」は並べるだけ
            if (icons.later) {
                Image(
                    provider = ImageProvider(R.drawable.wx_arrow),
                    contentDescription = null,
                    modifier = GlanceModifier.size(m.dp(0.6f)),
                )
            }
            Image(
                provider = ImageProvider(sub.icon),
                contentDescription = null,
                modifier = GlanceModifier.size(iconSize),
            )
        }
    }
}
