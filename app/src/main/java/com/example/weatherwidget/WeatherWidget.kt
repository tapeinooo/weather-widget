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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
            WidgetRenderer.updateAll(context, "新しい版")
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
        ids.forEach { WidgetRenderer.render(context, manager, it, "onUpdate") }
        WeatherWorker.schedulePeriodic(context)
        AppUpdater.cancelAutoUpdate(context)
        if (WeatherStore(context).loadForecast() == null) WeatherWorker.refreshNow(context, replace = false)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.render(context, manager, id, "大きさの変更")
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

/** 左右の矢印の幅（dp）。矢印の字が入るぎりぎりの幅。 */
private const val ARROW_WIDTH_DP = 12f
/** ウィジェットの上下の余白（dp）。できる限り小さくする。 */
private const val PADDING_DP = 1f

/** この高さ（dp）より低いときは、ホーム画面の1行分の表示にする（地名と降水確率を隠す）。 */
private const val COMPACT_HEIGHT_DP = 105

/** 日付と曜日の文字の大きさ（unit を 1 として）。列の幅をいっぱいに使う大きさ。 */
private const val DATE_SCALE = 1.4f

private enum class DayKind { RED, BLUE }

/** 気温の数字の大きさ（unit を 1 として）。 */
private const val TEMP_SCALE = 1.8f

object WidgetRenderer {
    private const val YAHOO_WEATHER = "jp.co.yahoo.android.weather.type1"

    private val WHITE = Color.parseColor("#EDEDED")
    private val GRAY = Color.parseColor("#BDBDBD")
    private val RED = Color.parseColor("#F2635B")
    private val BLUE = Color.parseColor("#5B95F2")

    /** 置かれているすべてのウィジェットを描き直す。 */
    fun updateAll(context: Context, why: String) {
        val manager = AppWidgetManager.getInstance(context)
        ids(context, manager).forEach { render(context, manager, it, why) }
    }

    /** 送った位置を元（今日が左端）に戻す。 */
    fun resetOffsets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val state = state(context)
        ids(context, manager).forEach { id ->
            if (state.getInt(offsetKey(id), 0) != 0) {
                state.edit().remove(offsetKey(id)).apply()
                render(context, manager, id, "位置を戻す")
            }
        }
    }

    fun forget(context: Context, id: Int) {
        state(context).edit().remove(offsetKey(id)).remove(versionKey(id)).remove(sizeKey(id)).apply()
    }

    /** ウィジェット全体を作って渡す。 */
    fun render(context: Context, manager: AppWidgetManager, id: Int, why: String) {
        val store = WeatherStore(context)
        val forecast = store.loadForecast()
        val m = metrics(context, manager, id, store, forecast, why)
        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setOnClickPendingIntent(R.id.root, yahooIntent(context))
        state(context).edit().putLong(versionKey(id), appVersion(context)).apply()
        rv.setTextViewText(R.id.place, store.place.name)
        rv.setTextViewTextSize(R.id.place, TypedValue.COMPLEX_UNIT_PX, m.textPx(1.15f))
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
            rv.setTextViewTextSize(R.id.message, TypedValue.COMPLEX_UNIT_PX, m.textPx(0.8f))
            manager.updateAppWidget(id, rv)
            return
        }

        val state = state(context)
        val window = DayWindow(forecast.days, forecast.today(), store.days, state.getInt(offsetKey(id), 0))
        // ページを全部入れて今の位置にそろえる。描き直しのたびに全部作り直させる
        // （番号付きでホーム画面に使い回させると、前の大きさが一部の列に残ることがあった）
        val sizes = DaySizes.of(forecast.days, m)
        rv.removeAllViews(R.id.flipper)
        window.pages.forEach { page -> rv.addView(R.id.flipper, pageView(context, page, forecast.today(), m, sizes)) }
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
            render(context, manager, id, "送り（前の版）")
            return
        }

        val rv = RemoteViews(context.packageName, R.layout.widget)
        rv.setDisplayedChild(R.id.flipper, next.start)
        setupZones(context, rv, id, next, metrics(context, manager, id, store, forecast), withIntents = false)
        manager.partiallyUpdateAppWidget(id, rv)
    }

    /**
     * 左右の押す場所。ウィジェットの高さいっぱいに、左右を幅の6分の1ずつ（7日並べたときの1日分）、真ん中を3分の2にとる。
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
            rv.setTextViewTextSize(onArrow, TypedValue.COMPLEX_UNIT_PX, m.textPx(1.8f))
            rv.setTextViewTextSize(offArrow, TypedValue.COMPLEX_UNIT_PX, m.textPx(1.8f))
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

    private fun pageView(context: Context, days: List<DayForecast>, today: String, m: Metrics, sizes: DaySizes): RemoteViews {
        val page = RemoteViews(context.packageName, R.layout.widget_page)
        page.removeAllViews(R.id.page)
        days.forEachIndexed { i, d -> page.addView(R.id.page, dayView(context, d, d.date == today, m, sizes, showMonth = i == 0)) }
        return page
    }

    private fun dayView(context: Context, day: DayForecast, isToday: Boolean, m: Metrics, sizes: DaySizes, showMonth: Boolean): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_day)
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
        // 月は1日と、ページの左端の日にだけ付ける（それ以外は日にちだけ）
        val dateText = SpannableStringBuilder(
            date?.let { if (it.dayOfMonth == 1 || showMonth) "${it.monthValue}/${it.dayOfMonth}" else "${it.dayOfMonth}" } ?: day.date,
        )
        // 日・祝日は赤、土は青（曜日の漢字の色と、その日の列の背景のうっすらした色）
        val kind = when {
            date == null -> null
            JapaneseHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> DayKind.RED
            date.dayOfWeek == DayOfWeek.SATURDAY -> DayKind.BLUE
            else -> null
        }
        // 背景は毎回指定する（ホーム画面が前の部品に上書きで当てはめるため、なしも 0 で指定する）
        rv.setInt(
            R.id.day, "setBackgroundResource",
            when {
                isToday && kind == DayKind.RED -> R.drawable.today_red_bg
                isToday && kind == DayKind.BLUE -> R.drawable.today_sat_bg
                isToday -> R.drawable.today_bg
                kind == DayKind.RED -> R.drawable.red_bg
                kind == DayKind.BLUE -> R.drawable.sat_bg
                else -> 0
            },
        )
        if (date != null) {
            // 曜日の漢字だけ色を付ける（大きさは日付と同じ）
            dateText.append(" (")
            val kanji = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPAN)
            when (kind) {
                DayKind.RED -> dateText.append(kanji, ForegroundColorSpan(RED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                DayKind.BLUE -> dateText.append(kanji, ForegroundColorSpan(BLUE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                null -> dateText.append(kanji)
            }
            dateText.append(")")
        }
        rv.setTextViewText(R.id.day_date, dateText)
        // 過ぎた日（観測の結果）は日付を少し暗くする
        rv.setTextColor(R.id.day_date, if (day.observed) GRAY else WHITE)
        rv.setTextViewTextSize(R.id.day_date, TypedValue.COMPLEX_UNIT_PX, sizes.datePx)

        val icons = day.icons
        val iconPx = sizes.iconPx
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
        rv.setTextViewTextSize(R.id.day_temps, TypedValue.COMPLEX_UNIT_PX, sizes.tempPx)

        val bottom = if (day.observed) {
            // 過ぎた日は降水確率の代わりに降った量
            day.rain?.let { r -> String.format(Locale.US, "%.1fmm", r) to (r >= 1.0) }
        } else {
            day.pop?.let { p -> "$p%" to (p >= 50) }
        }
        rv.setTextViewText(R.id.day_bottom, bottom?.first ?: "")
        rv.setTextColor(R.id.day_bottom, if (bottom?.second == true) BLUE else WHITE)
        rv.setTextViewTextSize(R.id.day_bottom, TypedValue.COMPLEX_UNIT_PX, m.textPx(1f))
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
     * レイアウトにも標準（1 単位＝8dp ほど）の大きさを書いておく（描画時の指定が効かなかったときに、Android の既定の大きな文字にならないように）。
     * 日付・アイコン・気温の大きさは DaySizes で全部の日にそろえる。
     */
    class Metrics(val unit: Float, val density: Float, val compact: Boolean, val columnWidth: Float) {
        /** 上下の余白。 */
        fun padding(): Int = dp(PADDING_DP)

        fun px(factor: Float): Int = (unit * factor * density).toInt().coerceAtLeast(1)

        /** 文字の大きさ（画素）。画素で渡すので、ホーム画面の文字の倍率や表示の倍率の読み違いに左右されない。 */
        fun textPx(factor: Float): Float = unit * factor * density

        /** dp を画素にする。 */
        fun dp(dp: Float): Int = (dp * density).toInt()

        /** 日の並びの上の余白（地名の行の分）。矢印も日の並びの高さの真ん中にそろえる。 */
        fun daysTop(): Int = if (compact) padding() else padding() + px(1.15f * 1.4f + 0.3f)
    }

    /** why があれば（全体を描くとき）、文字の大きさを決めた値を記録に残す。 */
    private fun metrics(
        context: Context, manager: AppWidgetManager, id: Int, store: WeatherStore, forecast: Forecast?, why: String? = null,
    ): Metrics {
        val options = manager.getAppWidgetOptions(id)
        // 縦向きのときの幅と高さ（dp）。ホーム画面から届いていないときは、前に使った大きさを使う
        // （仮の大きさを使うと、そのときだけ文字の大きさが変わってしまう）
        val state = state(context)
        val reportedWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val reportedHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val saved = state.getString(sizeKey(id), null)?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        val width = reportedWidth.takeIf { it > 0 } ?: saved?.get(0) ?: 320
        val height = reportedHeight.takeIf { it > 0 } ?: saved?.get(1) ?: 120
        if (reportedWidth > 0 && reportedHeight > 0) state.edit().putString(sizeKey(id), "${reportedWidth}x$reportedHeight").apply()
        val columns = store.days.coerceAtMost(forecast?.days?.size ?: store.days).coerceAtLeast(1)
        val columnWidth = (width - ARROW_WIDTH_DP * 2) / columns
        // 幅いっぱいに使う。いちばん幅をとるのは日付の行で、月がつく1日「11/1 (日)」が文字の大きさの 4.15 倍ほど（これより長い日付があれば DaySizes で全部の日付を縮める）。
        // 日の枠の左右のすきま（1dp ずつ）と、端末ごとの字体の違いの分に少し余裕を見る
        val byWidth = (columnWidth - 2f) / (4.15f * DATE_SCALE * 1.03f)
        // 1行分のときは地名と降水確率の行がないので、そのぶん高さを見込まない。
        // 行の高さは文字の大きさの 1.25 倍として、日付 1.25 ＋ すきま 0.4 ＋ アイコン 2.1 ＋ 気温（TEMP_SCALE 倍）
        val compact = height < COMPACT_HEIGHT_DP
        val rows = 1.25f * DATE_SCALE + 0.4f + 2.1f + 1.25f * TEMP_SCALE +
            (if (compact) 0f else (1.15f * 1.4f + 0.3f) + 1.25f) // 地名の行と降水確率の行
        val byHeight = (height - PADDING_DP * 2 - 2f) / rows // 2dp は日ごとの枠の上下の余白
        val unit = minOf(byWidth, byHeight).coerceIn(4f, 48f) * store.textScale / 100f
        if (why != null) {
            val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    ?.joinToString(",") { "${it.width.toInt()}x${it.height.toInt()}" }
            } else null
            val line = String.format(
                Locale.JAPAN, "%s #%d %s｜幅 %d〜%d 高さ %d〜%d%s｜使った %dx%d%s｜%d列%s｜文字 %.1fdp（%d%%）",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")), id, why,
                reportedWidth, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), reportedHeight,
                sizes?.let { " [$it]" } ?: "",
                width, height, if (reportedWidth > 0 && reportedHeight > 0) "" else "（前の値）",
                columns, if (compact) " 1行" else "", unit, store.textScale,
            )
            val log = (listOf(line) + sizeLog(context)).take(SIZE_LOG_MAX)
            state.edit().putString(SIZE_LOG_KEY, log.joinToString("\n")).apply()
        }
        return Metrics(unit, context.resources.displayMetrics.density, compact, columnWidth)
    }

    private fun ids(context: Context, manager: AppWidgetManager): IntArray =
        manager.getAppWidgetIds(ComponentName(context, WeatherWidgetReceiver::class.java))

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

    private fun appVersion(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }

    /** 文字の大きさを決めた値の記録（新しい順）。設定画面の確認用。 */
    fun sizeLog(context: Context): List<String> =
        state(context).getString(SIZE_LOG_KEY, null)?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()

    private const val SIZE_LOG_KEY = "size_log"
    private const val SIZE_LOG_MAX = 40

    private fun sizeKey(id: Int) = "size_$id"
    private fun versionKey(id: Int) = "version_$id"
    private fun offsetKey(id: Int) = "offset_$id"
}

/**
 * 日付・アイコン・気温の大きさ（画素）。全部の日（全部のページ）で同じ大きさにする。
 * 設定の大きさ（unit × 倍率）を基本に、いちばん長い日付（どの日もページの左端になりうるので、月を付けた形）と
 * いちばん長い気温が列の幅に入るまで縮める。アイコンは2つと矢印が列に並ぶまで縮める。
 */
class DaySizes(val datePx: Float, val tempPx: Float, val iconPx: Int) {
    companion object {
        /** 長さを測るための筆（大きさは比だけ使うので何でもよい）。 */
        private val PAINT = android.graphics.Paint().apply { textSize = 100f }

        fun of(days: List<DayForecast>, m: WidgetRenderer.Metrics): DaySizes {
            // 日の枠の左右のすきま（1dp ずつ）を除いた幅。端末ごとの字体の違いの分に 3% 余裕を見る
            val room = (m.columnWidth - 2f) / 1.03f
            fun fit(texts: List<String>, wanted: Float): Float {
                val widest = texts.maxOfOrNull { PAINT.measureText(it) / PAINT.textSize } ?: return wanted
                return minOf(wanted, room / widest)
            }
            val dates = days.mapNotNull { d ->
                runCatching { LocalDate.parse(d.date) }.getOrNull()?.let {
                    "${it.monthValue}/${it.dayOfMonth} (${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPAN)})"
                }
            }
            val temps = days.map { "${it.max ?: "--"} / ${it.min ?: "--"}" }
            val dateDp = fit(dates, m.unit * DATE_SCALE)
            val tempDp = fit(temps, m.unit * TEMP_SCALE)
            // アイコン2つ＋矢印（0.6）が列に入る大きさ
            val iconDp = minOf(m.unit * 1.9f, (room - m.unit * 0.6f) / 2f)
            return DaySizes(dateDp * m.density, tempDp * m.density, (iconDp * m.density).toInt().coerceAtLeast(1))
        }
    }
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
