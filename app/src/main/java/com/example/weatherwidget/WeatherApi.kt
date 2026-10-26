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
)

data class Weather(
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val daily: List<DailyForecast>,
)

/** Open-Meteo（無料・API キー不要）から天気と地名を取る。 */
object WeatherApi {

    suspend fun fetchForecastJson(lat: Double, lon: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=temperature_2m,weather_code,is_day" +
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
        val days = (0 until times.length()).map { i ->
            DailyForecast(
                date = times.getString(i),
                code = codes.getInt(i),
                max = maxes.getDouble(i),
                min = mins.getDouble(i),
                precipProbability = precips?.takeUnless { it.isNull(i) }?.getInt(i),
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
