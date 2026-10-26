package com.example.weatherwidget

/** ウィジェットに出すアイコンの種類。 */
enum class WeatherCategory(val icon: Int, val isPrecipitation: Boolean, val char: String) {
    SUN(R.drawable.wx_sun, false, "晴"),
    CLOUD(R.drawable.wx_cloud, false, "曇"),
    FOG(R.drawable.wx_fog, false, "霧"),
    RAIN(R.drawable.wx_rain, true, "雨"),
    SNOW(R.drawable.wx_snow, true, "雪"),
    THUNDER(R.drawable.wx_thunder, true, "雷雨"),
}

/** 1日のアイコン。later なら「後」（矢印でつなぐ）、そうでなければ「時々」「一時」（並べるだけ）。 */
data class WeatherIcons(val main: WeatherCategory, val sub: WeatherCategory?, val later: Boolean)

object WeatherCodes {

    /** 「曇後一時雨」「晴時々曇」「晴山沿い雷雨」などから、主なアイコンと2つ目のアイコンを決める。 */
    fun iconsFromLabel(label: String): WeatherIcons {
        val found = mutableListOf<Pair<Int, WeatherCategory>>()
        var i = 0
        while (i < label.length) {
            val category = when {
                label.startsWith("雷", i) -> WeatherCategory.THUNDER
                label.startsWith("晴", i) -> WeatherCategory.SUN
                label.startsWith("曇", i) -> WeatherCategory.CLOUD
                label.startsWith("霧", i) -> WeatherCategory.FOG
                label.startsWith("雨", i) -> WeatherCategory.RAIN
                label.startsWith("雪", i) -> WeatherCategory.SNOW
                else -> null
            }
            if (category != null) found += i to category
            // 「雷雨」は雷1つとして数える
            i += if (category == WeatherCategory.THUNDER && label.startsWith("雷雨", i)) 2 else 1
        }
        val distinct = found.distinctBy { it.second }
        val main = distinct.firstOrNull() ?: return WeatherIcons(WeatherCategory.CLOUD, null, false)
        val sub = distinct.getOrNull(1)
        val laterAt = label.indexOf('後')
        val later = sub != null && laterAt in main.first until sub.first
        return WeatherIcons(main.second, sub?.second, later)
    }

    /** Open-Meteo（WMO）の天気コードの種類。 */
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
}
