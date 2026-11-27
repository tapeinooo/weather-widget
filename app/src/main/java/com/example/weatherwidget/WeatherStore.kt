package com.example.weatherwidget

import android.content.Context

/** 場所と最後に取った予報（JSON のまま）をアプリ内に保存する。 */
class WeatherStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("weather", Context.MODE_PRIVATE)

    val place: Place
        get() = Place(
            name = prefs.getString(KEY_NAME, null) ?: DEFAULT.name,
            lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT.lat,
            lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: DEFAULT.lon,
        )

    val forecastJson: String? get() = prefs.getString(KEY_JSON, null)
    val fetchedAt: Long get() = prefs.getLong(KEY_FETCHED_AT, 0L)

    /** ウィジェットに並べる日数（今日を含む）。 */
    var days: Int
        get() = prefs.getInt(KEY_DAYS, DEFAULT_DAYS).coerceIn(MIN_DAYS, MAX_DAYS)
        set(value) = prefs.edit().putInt(KEY_DAYS, value.coerceIn(MIN_DAYS, MAX_DAYS)).apply()

    /** 文字の大きさ（％）。ウィジェットの大きさに合わせた大きさに掛ける。 */
    var textScale: Int
        get() = prefs.getInt(KEY_TEXT_SCALE, 100).coerceIn(MIN_SCALE, MAX_SCALE)
        set(value) = prefs.edit().putInt(KEY_TEXT_SCALE, value.coerceIn(MIN_SCALE, MAX_SCALE)).apply()

    fun setPlace(place: Place) {
        prefs.edit()
            .putString(KEY_NAME, place.name)
            .putString(KEY_LAT, place.lat.toString())
            .putString(KEY_LON, place.lon.toString())
            .remove(KEY_JSON)
            .remove(KEY_FETCHED_AT)
            .remove(KEY_JMA_AREA)
            .apply()
    }

    fun saveForecast(json: String, fetchedAt: Long) {
        prefs.edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .apply()
    }

    /** 最後の更新が失敗したときの理由。成功したら消す。 */
    var lastError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(value) = prefs.edit().putString(KEY_ERROR, value).apply()

    fun loadForecast(): Forecast? = forecastJson?.let { Forecast.fromJson(it) }

    /** 気象庁の予報区域。まだ調べていなければ checked が false。日本の外なら area が null。 */
    val jmaAreaChecked: Boolean get() = prefs.contains(KEY_JMA_AREA)
    val jmaArea: JmaArea? get() = prefs.getString(KEY_JMA_AREA, null)?.let { JmaArea.fromJson(it) }

    fun saveJmaArea(area: JmaArea?) {
        prefs.edit().putString(KEY_JMA_AREA, area?.toJson() ?: "").apply()
    }

    companion object {
        val DEFAULT = Place("東京", 35.6812, 139.7671)
        const val DEFAULT_DAYS = 5
        const val MIN_DAYS = 1
        const val MAX_DAYS = 7
        const val MIN_SCALE = 50
        const val MAX_SCALE = 100
        private const val KEY_DAYS = "days"
        private const val KEY_JMA_AREA = "jmaArea"
        private const val KEY_ERROR = "lastError"
        private const val KEY_TEXT_SCALE = "textScale"
        private const val KEY_NAME = "name"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_JSON = "json"
        private const val KEY_FETCHED_AT = "fetchedAt"
    }
}
