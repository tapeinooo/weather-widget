package com.example.weatherwidget

/** ウィジェットに出すアイコンの種類。雨・雪・雷は少しでも降る時間があれば優先する。 */
enum class WeatherCategory(val icon: Int, val isPrecipitation: Boolean) {
    SUN(R.drawable.wx_sun, false),
    CLOUD(R.drawable.wx_cloud, false),
    FOG(R.drawable.wx_fog, false),
    RAIN(R.drawable.wx_rain, true),
    SNOW(R.drawable.wx_snow, true),
    THUNDER(R.drawable.wx_thunder, true),
}

/** WMO の天気コードを絵文字・日本語・アイコンの種類にする。 */
object WeatherCodes {
    fun category(code: Int): WeatherCategory = when (code) {
        0, 1, 2 -> WeatherCategory.SUN
        3 -> WeatherCategory.CLOUD
        45, 48 -> WeatherCategory.FOG
        71, 73, 75, 77, 85, 86 -> WeatherCategory.SNOW
        95, 96, 99 -> WeatherCategory.THUNDER
        in 51..67, in 80..82 -> WeatherCategory.RAIN
        else -> WeatherCategory.CLOUD
    }

    /** ある時間帯（数時間分のコード）を代表する種類。雨・雪・雷が2時間以上あればそれにする。 */
    fun periodCategory(codes: List<Int>): WeatherCategory? {
        if (codes.isEmpty()) return null
        val counts = codes.groupingBy { category(it) }.eachCount()
        val wet = counts.filterKeys { it.isPrecipitation }.filterValues { it >= 2 }
        val pool = wet.ifEmpty { counts }
        return pool.maxWithOrNull(compareBy({ it.value }, { it.key.ordinal }))?.key
    }

    fun emoji(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 80, 81 -> "🌦️"
        56, 57, 61, 63, 65, 66, 67, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "❔"
    }

    fun label(code: Int): String = when (code) {
        0 -> "快晴"
        1 -> "晴れ"
        2 -> "晴れ時々くもり"
        3 -> "くもり"
        45, 48 -> "霧"
        51, 53, 55 -> "霧雨"
        56, 57 -> "着氷性の霧雨"
        61 -> "小雨"
        63 -> "雨"
        65 -> "強い雨"
        66, 67 -> "着氷性の雨"
        71 -> "小雪"
        73 -> "雪"
        75 -> "大雪"
        77 -> "霧雪"
        80, 81 -> "にわか雨"
        82 -> "激しいにわか雨"
        85, 86 -> "にわか雪"
        95 -> "雷雨"
        96, 99 -> "ひょうを伴う雷雨"
        else -> "不明"
    }
}
