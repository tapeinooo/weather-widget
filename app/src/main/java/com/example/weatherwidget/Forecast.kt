package com.example.weatherwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 1日分の予報。label は気象庁と同じ書き方（「曇一時雨」「晴後曇」など）。予報がなければ空。
 * 過ぎた日は observed になり、max・min・rain（降水量 mm）は観測の結果に置き換わる。
 */
data class DayForecast(
    val date: String,
    val label: String,
    val max: Int?,
    val min: Int?,
    val pop: Int?,
    val rain: Double? = null,
    val observed: Boolean = false,
) {
    val icons: WeatherIcons? get() = label.takeIf { it.isNotEmpty() }?.let { WeatherCodes.iconsFromLabel(it) }
}

/** ウィジェットとアプリが使う予報。source は "jma"（気象庁）か "open-meteo"。 */
data class Forecast(val source: String, val days: List<DayForecast>) {

    /** この予報の「今日」。気象庁は日本時間、Open-Meteo は端末の日付。 */
    fun today(): String = if (source == SOURCE_JMA) JmaApi.today() else WeatherApi.today()

    val sourceName: String get() = if (source == SOURCE_JMA) "気象庁" else "Open-Meteo"

    fun toJson(): String = JSONObject()
        .put("source", source)
        .put("days", JSONArray().apply {
            days.forEach { d ->
                put(JSONObject()
                    .put("date", d.date)
                    .put("label", d.label)
                    .put("max", d.max ?: JSONObject.NULL)
                    .put("min", d.min ?: JSONObject.NULL)
                    .put("pop", d.pop ?: JSONObject.NULL)
                    .put("rain", d.rain ?: JSONObject.NULL)
                    .put("observed", d.observed))
            }
        })
        .toString()

    /** 新しい予報で消えた値（今日の最低気温など）を、前の予報の同じ日の値で補う。 */
    fun fillFrom(previous: Forecast?): Forecast {
        if (previous == null) return this
        val old = previous.days.associateBy { it.date }
        return copy(days = days.map { d ->
            val p = old[d.date] ?: return@map d
            d.copy(max = d.max ?: p.max, min = d.min ?: p.min)
        })
    }

    /**
     * 新しい予報（this）に、前に持っていた過ぎた日をつなげる。keepDays 日より前は捨てる。
     * 過ぎた日は、新しい側に観測の結果があればそれを、なければ前の日の最後の予報を使う。
     */
    fun withHistory(previous: Forecast?, today: String, keepDays: Long = HISTORY_DAYS): Forecast {
        val oldest = java.time.LocalDate.parse(today).minusDays(keepDays).toString()
        val filled = fillFrom(previous).days
        val old = previous?.days.orEmpty().associateBy { it.date }
        val new = filled.associateBy { it.date }
        val past = (old.keys + new.keys).filter { it in oldest..<today }.sorted().mapNotNull { date ->
            val n = new[date]
            if (n != null && n.observed) n else old[date] ?: n
        }
        return copy(days = past + filled.filter { it.date >= today })
    }

    companion object {
        /** 過ぎた日を何日分残すか（アメダスの観測を読めるのが10日前まで）。 */
        const val HISTORY_DAYS = 10L
        const val SOURCE_JMA = "jma"
        const val SOURCE_OPEN_METEO = "open-meteo"

        fun fromJson(json: String): Forecast? = runCatching {
            val root = JSONObject(json)
            val days = root.getJSONArray("days")
            Forecast(
                source = root.getString("source"),
                days = (0 until days.length()).map { i ->
                    val d = days.getJSONObject(i)
                    DayForecast(
                        date = d.getString("date"),
                        label = d.getString("label"),
                        max = d.optIntOrNull("max"),
                        min = d.optIntOrNull("min"),
                        pop = d.optIntOrNull("pop"),
                        rain = if (d.isNull("rain")) null else d.optDouble("rain"),
                        observed = d.optBoolean("observed"),
                    )
                },
            )
        }.getOrNull()

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
    }
}
