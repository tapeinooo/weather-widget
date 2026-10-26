package com.example.weatherwidget

import org.json.JSONArray
import org.json.JSONObject

/** 1日分の予報。label は気象庁と同じ書き方（「曇一時雨」「晴後曇」など）。 */
data class DayForecast(
    val date: String,
    val label: String,
    val max: Int?,
    val min: Int?,
    val pop: Int?,
) {
    val icons: WeatherIcons get() = WeatherCodes.iconsFromLabel(label)
}

/** ウィジェットとアプリが使う予報。source は "jma"（気象庁）か "open-meteo"。 */
data class Forecast(val source: String, val days: List<DayForecast>) {

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
                    .put("pop", d.pop ?: JSONObject.NULL))
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

    companion object {
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
                    )
                },
            )
        }.getOrNull()

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
    }
}
