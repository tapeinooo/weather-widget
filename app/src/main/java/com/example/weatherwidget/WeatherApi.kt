package com.example.weatherwidget

import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

data class Place(val name: String, val lat: Double, val lon: Double)

/** Open-Meteo（無料・API キー不要）。日本の外の地点と、気象庁から取れないときに使う。地名の検索にも使う。 */
object WeatherApi {
    private val MORNING = 6..11
    private val AFTERNOON = 12..17
    private val DAYTIME = 6..17

    suspend fun fetchForecast(lat: Double, lon: Double): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&hourly=weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum" +
            "&timezone=auto&forecast_days=7&past_days=${Forecast.HISTORY_DAYS}"
        return parseForecast(Http.get(url))
    }

    fun parseForecast(json: String): Forecast {
        val root = JSONObject(json)
        val daily = root.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val maxes = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val precips = daily.optJSONArray("precipitation_probability_max")
        val rains = daily.optJSONArray("precipitation_sum")
        // その地点の「今日」。それより前の日は観測（再解析）の値として扱う
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds"))
        val today = Instant.now().atOffset(offset).toLocalDate().toString()
        val hourly = root.optJSONObject("hourly")
        val hourTimes = hourly?.optJSONArray("time")
        val hourCodes = hourly?.optJSONArray("weather_code")
        val hourPrecips = hourly?.optJSONArray("precipitation_probability")
        // "2026-10-26T13:00" → 日付ごとに (時, コード) と (時, 降水確率) を集める
        val codesByDate = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        val precipByDate = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        if (hourTimes != null && hourCodes != null) {
            for (i in 0 until minOf(hourTimes.length(), hourCodes.length())) {
                val t = hourTimes.getString(i)
                val date = t.substringBefore('T')
                val hour = t.substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
                if (!hourCodes.isNull(i)) codesByDate.getOrPut(date) { mutableListOf() } += hour to hourCodes.getInt(i)
                if (hourPrecips != null && i < hourPrecips.length() && !hourPrecips.isNull(i)) {
                    precipByDate.getOrPut(date) { mutableListOf() } += hour to hourPrecips.getInt(i)
                }
            }
        }
        fun period(date: String, hours: IntRange) =
            WeatherCodes.periodCategory(codesByDate[date].orEmpty().filter { it.first in hours }.map { it.second })

        val days = (0 until times.length()).map { i ->
            val date = times.getString(i)
            // 午前と午後で天気が違えば「晴後雨」のように気象庁と同じ書き方にする
            val parts = listOfNotNull(period(date, MORNING), period(date, AFTERNOON)).distinct()
                .ifEmpty { listOf(WeatherCodes.category(codes.getInt(i))) }
            // 降水確率はアイコンと同じ日中（6〜18時）の最大。時間ごとの値がなければ1日の最大
            val pop = precipByDate[date].orEmpty().filter { it.first in DAYTIME }.maxOfOrNull { it.second }
                ?: precips?.takeUnless { it.isNull(i) }?.getInt(i)
            DayForecast(
                date = date,
                label = parts.joinToString("後") { it.char },
                max = maxes.getDouble(i).roundToInt(),
                min = mins.getDouble(i).roundToInt(),
                pop = pop,
                rain = rains?.takeUnless { it.isNull(i) }?.getDouble(i)?.takeIf { date < today },
                observed = date < today,
            )
        }
        return Forecast(Forecast.SOURCE_OPEN_METEO, days)
    }

    suspend fun searchPlaces(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=8&language=ja&format=json"
        val results = JSONObject(Http.get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val name = listOfNotNull(r.optString("name").ifEmpty { null }, r.optString("admin1").ifEmpty { null })
                .distinct()
                .joinToString("・")
            Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** 予報を読むときの「今日」（端末の日付）。 */
    fun today(): String = java.time.LocalDate.now().toString()

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)
}
