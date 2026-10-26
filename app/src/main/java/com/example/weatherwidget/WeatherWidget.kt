package com.example.weatherwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * ホーム画面のウィジェット。
 * 送りを速くするため、表示できる範囲（ページ）を全部 ViewFlipper に入れて渡しておき、
 * 押されたら「何枚目を見せるか」だけを送る（partiallyUpdateAppWidget）。
 */
class WeatherWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // 新しい版を入れたら、ウィジェットを新しい版で作り直す
            WidgetRenderer.updateAll(context)
            return
        }
        if (intent.action == ACTION_NOTHING) return
        if (intent.action == ACTION_SHIFT) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val delta = intent.getIntExtra(EXTRA_DELTA, 0)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && delta != 0) {
                WidgetRenderer.shift(context, id, delta)
                OffsetReset.arm(context)
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.render(context, manager, it) }
        WeatherWorker.schedulePeriodic(context)
        if (WeatherStore(context).loadForecast() == null) WeatherWorker.refreshNow(context, replace = false)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetRenderer.forget(context, it) }
    }

    override fun onEnabled(context: Context) {
        WeatherWorker.schedulePeriodic(context)
        WeatherWorker.refreshNow(context, replace = false)
    }

    override fun onDisabled(context: Context) {
        WeatherWorker.cancelPeriodic(context)
    }

    companion object {
        const val ACTION_SHIFT = "com.example.weatherwidget.SHIFT"
        const val ACTION_NOTHING = "com.example.weatherwidget.NOTHING"
        const val EXTRA_DELTA = "delta"
    }
}

private const val ARROW_WIDTH_DP = 22f
private const val PADDING_DP = 8f

/** この高さ（dp）より低いときは、ホーム画面の1行分の表示にする（地名と降水確率を隠す）。 */
private const val COMPACT_HEIGHT_DP = 105

/** 気温の数字の大きさ（日付の文字を 1 として）。 */
private const val TEMP_SCALE = 1.5f

object WidgetRenderer {
    private const val YAHOO_WEATHER = "jp.co.yahoo.android.weather.type1"

    private val WHITE = Color.parseColor("#EDEDED")
    private val GRAY = Color.parseColor("#BDBDBD")
    private val RED = Color.parseColor("#F2635B")
    private val BLUE = Color.parseColor("#5B95F2")

    /** 置かれているすべてのウィジェットを描き直す。 */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        ids(context, manager).forEach { render(context, manager, it) }
    }

    /** 送った位置を元（今日が左端）に戻す。 */
    fun resetOffsets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        ids(context, manager).forEach { id ->
            if (state.getInt(offsetKey(id), 0) != 0) {
                state.edit().remove(offsetKey(id)).apply()
                render(context, manager, id)
            }
        }
    }

    fun forget(context: Context, id: Int) {
        state(context).edit().remove(offsetKey(id)).remove(versionKey(id)).apply()
    }

    /** ウィジェット全体を作って渡す。 */
    fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val store = WeatherStore(context)
        val forecast = store.loadForecast()
        val m = metrics(context, manager, id, store, forecast)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setOnClickPendingIntent(R.id.root, yahooIntent(context))
        state(context).edit().putLong(versionKey(id), appVersion(context)).apply()
        rv.setTextViewText(R.id.place, store.place.name)
        rv.setTextViewTextSize(R.id.place, TypedValue.COMPLEX_UNIT_DIP, m.unit * 1.15f)
        rv.setViewVisibility(R.id.place, if (m.compact) View.GONE else View.VISIBLE)

        // ホーム画面は前に作った部品に上書きで当てはめるので、出す・隠すは毎回両方を指定する
        val ready = forecast != null && forecast.days.isNotEmpty()
        rv.setViewVisibility(R.id.days_frame, if (ready) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.zones, if (ready) View.VISIBLE else View.GONE)
        // 日の並びは、地名の行の下で、左右に矢印の分をあけて、残りの高さの真ん中に置く
        rv.setViewPadding(R.id.days_frame, m.dp(ARROW_WIDTH_DP), m.daysTop(), m.dp(ARROW_WIDTH_DP), m.padding())
        rv.setViewVisibility(R.id.message, if (ready) View.GONE else View.VISIBLE)
        if (forecast == null || !ready) {
            val error = store.lastError
            rv.setTextViewText(R.id.message, if (error == null) "読み込み中…" else "更新できませんでした\n$error")
            rv.setTextViewTextSize(R.id.message, TypedValue.COMPLEX_UNIT_DIP, m.unit * 0.8f)
            manager.updateAppWidget(id, rv)
            return
        }

        val state = state(context)
        val window = DayWindow(forecast.days, forecast.today(), store.days, state.getInt(offsetKey(id), 0))
        // ページを全部入れて今の位置にそろえる。Android 12 以降は番号付き（stable）で入れ、
        // 描き直しでもページを作り直さずに使い回させる（番号付きで使い回させるには、先に removeAllViews を置く決まり）
        rv.removeAllViews(R.id.flipper)
        window.pages.forEachIndexed { i, page -> rv.addChild(R.id.flipper, pageView(context, page, forecast.today(), m), i) }
        rv.setDisplayedChild(R.id.flipper, window.start)
        setupZones(context, rv, id, window, m, withIntents = true)
        manager.updateAppWidget(id, rv)
    }

    /** 1日送る。ページはもう渡してあるので、見せるページと押す場所だけを送る（アニメーションはなし。最速で切り替える）。 */
    fun shift(context: Context, id: Int, delta: Int) {
        val store = WeatherStore(context)
        val forecast = store.loadForecast() ?: return
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        val window = DayWindow(forecast.days, forecast.today(), store.days, state.getInt(offsetKey(id), 0))
        val offset = window.shifted(delta)
        val next = DayWindow(forecast.days, forecast.today(), store.days, offset)
        if (next.start == window.start) return
        state.edit().putInt(offsetKey(id), offset).apply()
        // ホーム画面にある部品が前の版のものなら、差分は効かないので全体を渡し直す
        if (state.getLong(versionKey(id), -1) != appVersion(context)) {
            render(context, manager, id)
            return
        }

        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setDisplayedChild(R.id.flipper, next.start)
        setupZones(context, rv, id, next, metrics(context, manager, id, store, forecast), withIntents = false)
        manager.partiallyUpdateAppWidget(id, rv)
    }

    /**
     * 左右の押す場所。ウィジェットの高さいっぱいに、左右を幅の3割ずつ、真ん中を4割にとる。
     * 真ん中は何もしない場所で、押すと下の Yahoo!天気 が開く。
     * 行き止まり（これ以上送れない側）は、押しても何も起きない（Yahoo!天気 も開かない）。
     * 押す場所はレイアウトに全部置いてあり、表示を切り替えるだけ（足したり消したりしない）。
     */
    private fun setupZones(context: Context, rv: RemoteViews, id: Int, window: DayWindow, m: Metrics, withIntents: Boolean) {
        fun side(delta: Int, enabled: Boolean, on: Int, off: Int, onArrow: Int, offArrow: Int) {
            rv.setViewVisibility(on, if (enabled) View.VISIBLE else View.GONE)
            rv.setViewVisibility(off, if (enabled) View.GONE else View.VISIBLE)
            if (!withIntents) return
            for (zone in intArrayOf(on, off)) rv.setViewPadding(zone, 0, m.daysTop(), 0, m.padding())
            rv.setTextViewTextSize(onArrow, TypedValue.COMPLEX_UNIT_DIP, m.unit * 1.8f)
            rv.setTextViewTextSize(offArrow, TypedValue.COMPLEX_UNIT_DIP, m.unit * 1.8f)
            val request = id * 4 + if (delta > 0) 1 else 0
            val shift = Intent(context, WeatherWidgetReceiver::class.java)
                .setAction(WeatherWidgetReceiver.ACTION_SHIFT)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(WeatherWidgetReceiver.EXTRA_DELTA, delta)
            val nothing = Intent(context, WeatherWidgetReceiver::class.java).setAction(WeatherWidgetReceiver.ACTION_NOTHING)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            rv.setOnClickPendingIntent(on, PendingIntent.getBroadcast(context, request, shift, flags))
            rv.setOnClickPendingIntent(off, PendingIntent.getBroadcast(context, request + 2, nothing, flags))
        }
        side(-1, window.canGoBack, R.id.zone_left_on, R.id.zone_left_off, R.id.zone_left_on_arrow, R.id.zone_left_off_arrow)
        side(1, window.canGoForward, R.id.zone_right_on, R.id.zone_right_off, R.id.zone_right_on_arrow, R.id.zone_right_off_arrow)
    }

    /** Android 12 以降は番号付きで入れる（描き直しで使い回される）。 */
    private fun RemoteViews.addChild(parent: Int, child: RemoteViews, stableId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addStableView(parent, child, stableId) else addView(parent, child)
    }

    private fun pageView(context: Context, days: List<DayForecast>, today: String, m: Metrics): RemoteViews {
        val page = RemoteViews(context.packageName, R.layout.widget_page)
        page.removeAllViews(R.id.page)
        days.forEachIndexed { i, d -> page.addChild(R.id.page, dayView(context, d, d.date == today, m), i) }
        return page
    }

    private fun dayView(context: Context, day: DayForecast, isToday: Boolean, m: Metrics): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_day)
        rv.setInt(R.id.day, "setBackgroundResource", if (isToday) R.drawable.today_bg else 0)

        val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
        val dateText = SpannableStringBuilder(date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: day.date)
        if (date != null) {
            // 曜日の漢字だけ色を付ける：日・祝日は赤、土は青（大きさは日付と同じ）
            dateText.append(" (")
            val kanji = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPAN)
            val color = when {
                JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> RED
                date.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
                else -> null
            }
            if (color != null) dateText.append(kanji, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else dateText.append(kanji)
            dateText.append(")")
        }
        rv.setTextViewText(R.id.day_date, dateText)
        // 過ぎた日（観測の結果）は日付を少し暗くする
        rv.setTextColor(R.id.day_date, if (day.observed) GRAY else WHITE)
        rv.setTextViewTextSize(R.id.day_date, TypedValue.COMPLEX_UNIT_DIP, m.unit)

        val icons = day.icons
        val iconPx = m.px(1.9f)
        rv.setImageViewResource(R.id.icon_main, icons?.main?.icon ?: R.drawable.wx_dash)
        rv.sizeImage(R.id.icon_main, iconPx)
        val sub = icons?.sub
        rv.setViewVisibility(R.id.icon_sub, if (sub != null) View.VISIBLE else View.GONE)
        // 「後」は矢印でつなぎ、「時々」「一時」は並べるだけ
        rv.setViewVisibility(R.id.icon_arrow, if (sub != null && icons.later) View.VISIBLE else View.GONE)
        if (sub != null) {
            rv.setImageViewResource(R.id.icon_sub, sub.icon)
            rv.sizeImage(R.id.icon_sub, iconPx)
            rv.sizeImage(R.id.icon_arrow, m.px(0.6f))
        }

        val temps = SpannableStringBuilder()
            .append("${day.max ?: "--"}", ForegroundColorSpan(RED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(" / ")
            .append("${day.min ?: "--"}", ForegroundColorSpan(BLUE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        rv.setTextViewText(R.id.day_temps, temps)
        // ℃ をなくした分、数字を大きくする
        rv.setTextViewTextSize(R.id.day_temps, TypedValue.COMPLEX_UNIT_DIP, m.unit * TEMP_SCALE)

        val bottom = if (day.observed) {
            // 過ぎた日は降水確率の代わりに降った量
            day.rain?.let { r -> String.format(Locale.US, "%.1fmm", r) to (r >= 1.0) }
        } else {
            day.pop?.let { p -> "$p%" to (p >= 50) }
        }
        rv.setTextViewText(R.id.day_bottom, bottom?.first ?: "")
        rv.setTextColor(R.id.day_bottom, if (bottom?.second == true) BLUE else WHITE)
        rv.setTextViewTextSize(R.id.day_bottom, TypedValue.COMPLEX_UNIT_DIP, m.unit)
        rv.setViewVisibility(R.id.day_bottom, if (m.compact) View.GONE else View.VISIBLE)
        return rv
    }

    private fun RemoteViews.sizeImage(viewId: Int, px: Int) {
        // 絵は 96dp で作ってあり、最大の大きさで縮めて合わせる
        setInt(viewId, "setMaxHeight", px)
        setInt(viewId, "setMaxWidth", px)
    }

    /** タップで Yahoo!天気 を開く。入っていなければこのアプリの設定画面を開く。 */
    private fun yahooIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(YAHOO_WEATHER)
            ?: Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 文字の大きさの決め方。1 単位＝日付の文字の大きさ（dp）。
     * 1列の幅に「20℃ / 10℃」が収まり、縦に全部の行が収まる大きさを基本にし、設定の％を掛ける。
     * 大きさは dp で指定するので、端末の文字サイズ設定には左右されない。
     */
    class Metrics(val unit: Float, val density: Float, val compact: Boolean) {
        /** 上下の余白。1行分のときは詰める。 */
        fun padding(): Int = dp(if (compact) 4f else PADDING_DP)

        fun px(factor: Float): Int = (unit * factor * density).toInt().coerceAtLeast(1)

        /** dp を画素にする。 */
        fun dp(dp: Float): Int = (dp * density).toInt()

        /** 日の並びの上の余白（地名の行の分）。矢印も日の並びの高さの真ん中にそろえる。 */
        fun daysTop(): Int = if (compact) padding() else padding() + px(1.15f * 1.4f + 0.3f)
    }

    private fun metrics(context: Context, manager: AppWidgetManager, id: Int, store: WeatherStore, forecast: Forecast?): Metrics {
        val options = manager.getAppWidgetOptions(id)
        // 縦向きのときの幅と高さ（dp）
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 120
        val columns = store.days.coerceAtMost(forecast?.days?.size ?: store.days).coerceAtLeast(1)
        val columnWidth = (width - ARROW_WIDTH_DP * 2) / columns
        val byWidth = columnWidth / 5.6f
        // 1行分のときは地名と降水確率の行がないので、そのぶん高さを見込まない。
        // 行の高さは文字の大きさの 1.25 倍として、日付 1.25 ＋ すきま 0.4 ＋ アイコン 2.1 ＋ 気温（TEMP_SCALE 倍）
        val compact = height < COMPACT_HEIGHT_DP
        val rows = 1.25f + 0.4f + 2.1f + 1.25f * TEMP_SCALE +
            (if (compact) 0f else (1.15f * 1.4f + 0.3f) + 1.25f) // 地名の行と降水確率の行
        val edge = if (compact) 4f else PADDING_DP
        val byHeight = (height - edge * 2 - 4f) / rows // 4dp は日ごとの枠の上下の余白
        val unit = minOf(byWidth, byHeight).coerceIn(4f, 48f) * store.textScale / 100f
        return Metrics(unit, context.resources.displayMetrics.density, compact)
    }

    private fun ids(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, WeatherWidgetReceiver::class.java))

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

    private fun appVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }

    private fun versionKey(id: Int) = "version_$id"
    private fun offsetKey(id: Int) = "offset_$id"
}

/**
 * 表示する日の範囲。offset は「今日を左端にした位置」からのずれ（日数）。
 * 過ぎた日は持っている分だけ、先の日は予報がある分だけ送れる。
 */
class DayWindow(private val all: List<DayForecast>, today: String, count: Int, offset: Int) {
    val count = count.coerceIn(1, maxOf(all.size, 1))
    private val todayIndex = all.indexOfFirst { it.date >= today }.takeIf { it >= 0 } ?: 0
    private val lastStart = maxOf(all.size - this.count, 0)
    val start = (todayIndex + offset).coerceIn(0, lastStart)

    val visible: List<DayForecast> get() = all.drop(start).take(count)
    val canGoBack get() = start > 0
    val canGoForward get() = start < lastStart

    /** 送れるすべての位置のページ（ViewFlipper の子の順）。 */
    val pages: List<List<DayForecast>> get() = (0..lastStart).map { all.drop(it).take(count) }

    /** delta 日送ったあとの offset（範囲の外には出さない）。 */
    fun shifted(delta: Int): Int = (start + delta).coerceIn(0, lastStart) - todayIndex
}
