package com.example.weatherwidget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本物の気象庁・国土地理院につなぐ。結果は標準出力に出す。 */
class JmaApiLiveTest {

    private fun check(name: String, lat: Double, lon: Double, expectedOffice: String) = runBlocking {
        val area = JmaApi.resolveArea(lat, lon)
        assertNotNull("$name の区域", area)
        assertEquals(name, expectedOffice, area!!.office)
        val forecast = JmaApi.fetchForecast(area, null)
        println("== $name $area")
        forecast.days.forEach { println("  ${it.date} ${it.label} ${it.icons} ${it.max}/${it.min} ${it.pop}%") }
        assertTrue("$name は6日以上", forecast.days.size >= 6)
        assertTrue("$name の明日の最高・最低", forecast.days[1].max != null && forecast.days[1].min != null)
        // JSON に書いて読み戻しても同じ
        assertEquals(forecast, Forecast.fromJson(forecast.toJson()))
    }

    @Test fun 麻布十番() = check("麻布十番", 35.6544, 139.7369, "130000")
    @Test fun 横浜市西区() = check("横浜市西区", 35.4437, 139.6380, "140000")
    @Test fun 札幌() = check("札幌", 43.0621, 141.3544, "016000")
    @Test fun 奄美() = check("奄美", 28.3769, 129.4938, "460040")
    @Test fun 那覇() = check("那覇", 26.2124, 127.6809, "471000")

    @Test
    fun 日本の外は区域なし() = runBlocking {
        assertNull(JmaApi.resolveArea(40.7128, -74.0060))
    }

    @Test
    fun 消えた今日の最低気温を前の予報で補う() {
        val old = Forecast("jma", listOf(DayForecast("2026-10-26", "雨", 20, 16, 90)))
        val new = Forecast("jma", listOf(DayForecast("2026-10-26", "雨", 21, null, 80)))
        assertEquals(DayForecast("2026-10-26", "雨", 21, 16, 80), new.fillFrom(old).days[0])
    }

    @Test
    fun OpenMeteoも同じ形になる() = runBlocking {
        val f = WeatherApi.fetchForecast(40.7128, -74.0060)
        println("== New York (Open-Meteo)")
        f.days.forEach { println("  ${it.date} ${it.label} ${it.icons} ${it.max}/${it.min} ${it.pop}%") }
        assertEquals("過去10日＋7日", 17, f.days.size)
        assertTrue(f.days.take(10).all { it.observed })
    }
}
