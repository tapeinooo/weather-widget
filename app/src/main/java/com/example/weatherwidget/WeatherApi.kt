package com.example.weatherwidget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val lat: Double, val lon: Double)

data class DailyForecast(
    val date: String,
    val code: Int,
    val max: Double,
    val min: Double,
    val precipProbability: Int?,
    /** 午前（6〜12時）と午後（12〜18時）の天気。同じなら1つのアイコンにする。 */
    val morning: WeatherCategory?,
    val afternoon: WeatherCategory?,
) {
    val icons: List<WeatherCategory>
        get() {
            val parts = listOfNotNull(morning, afternoon).distinct()
            return parts.ifEmpty { listOf(WeatherCodes.category(code)) }
        }
}

data class Weather(
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val daily: List<DailyForecast>,
)

/** Open-Meteo（無料・API キー不要）から天気と地名を取る。 */
object WeatherApi {
    private val MORNING = 6..11
    private val AFTERNOON = 12..17
    private val DAYTIME = 6..17

    suspend fun fetchForecastJson(lat: Double, lon: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        return get(url)
    }

    fun parseForecast(json: String): Weather {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val maxes = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val precips = daily.optJSONArray("precipitation_probability_max")
        val hourly = root.optJSONObject("hourly")
        val hourTimes = hourly?.optJSONArray("time")
        val hourCodes = hourly?.optJSONArray("weather_code")
        val hourPrecips = hourly?.optJSONArray("precipitation_probability")
        // "2026-10-26T13:00" → 日付ごとに (時, コード) と (時, 降水確率) を集める
        val byDate = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        val precipByDate = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        if (hourTimes != null && hourCodes != null) {
            for (i in 0 until minOf(hourTimes.length(), hourCodes.length())) {
                if (hourCodes.isNull(i)) continue
                val t = hourTimes.getString(i)
                val hour = t.substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
                byDate.getOrPut(t.substringBefore('T')) { mutableListOf() } += hour to hourCodes.getInt(i)
                if (hourPrecips != null && i < hourPrecips.length() && !hourPrecips.isNull(i)) {
                    precipByDate.getOrPut(t.substringBefore('T')) { mutableListOf() } += hour to hourPrecips.getInt(i)
                }
            }
        }
        fun period(date: String, hours: IntRange) =
            WeatherCodes.periodCategory(byDate[date].orEmpty().filter { it.first in hours }.map { it.second })
        // 降水確率はアイコンと同じ日中（6〜18時）の最大。時間ごとの値がなければ1日の最大
        fun daytimePrecip(date: String) =
            precipByDate[date].orEmpty().filter { it.first in DAYTIME }.maxOfOrNull { it.second }
        val days = (0 until times.length()).map { i ->
            val date = times.getString(i)
            DailyForecast(
                date = date,
                code = codes.getInt(i),
                max = maxes.getDouble(i),
                min = mins.getDouble(i),
                precipProbability = daytimePrecip(date) ?: precips?.takeUnless { it.isNull(i) }?.getInt(i),
                morning = period(date, MORNING),
                afternoon = period(date, AFTERNOON),
            )
        }
        return Weather(
            temperature = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            daily = days,
        )
    }

    suspend fun searchPlaces(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(query, "UTF-8")}&count=8&language=ja&format=json"
        val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val name = listOfNotNull(r.optString("name").ifEmpty { null }, r.optString("admin1").ifEmpty { null })
                .distinct()
                .joinToString("・")
            Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
