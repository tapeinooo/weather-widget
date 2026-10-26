package com.example.weatherwidget

import com.example.weatherwidget.WeatherCategory.CLOUD
import com.example.weatherwidget.WeatherCategory.FOG
import com.example.weatherwidget.WeatherCategory.RAIN
import com.example.weatherwidget.WeatherCategory.SNOW
import com.example.weatherwidget.WeatherCategory.SUN
import com.example.weatherwidget.WeatherCategory.THUNDER
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCodesTest {
    private fun icons(label: String) = WeatherCodes.iconsFromLabel(label)

    @Test
    fun 文言からアイコンを決める() {
        assertEquals(WeatherIcons(SUN, null, false), icons("晴"))
        assertEquals(WeatherIcons(SUN, CLOUD, false), icons("晴時々曇"))
        assertEquals(WeatherIcons(CLOUD, RAIN, false), icons("曇一時雨"))
        assertEquals(WeatherIcons(CLOUD, RAIN, true), icons("曇後一時雨"))
        assertEquals(WeatherIcons(RAIN, SUN, true), icons("雨後晴"))
        assertEquals(WeatherIcons(SUN, THUNDER, false), icons("晴山沿い雷雨"))
        assertEquals(WeatherIcons(SNOW, RAIN, false), icons("雪か雨後曇"))
        assertEquals(WeatherIcons(CLOUD, FOG, false), icons("曇海上海岸は霧か霧雨"))
        assertEquals(WeatherIcons(RAIN, null, false), icons("雨で暴風を伴う"))
    }

    @Test
    fun 表のすべてのコードにアイコンがある() {
        (100..450).map { it.toString() }.forEach { code ->
            val label = JmaTelops.label(code)
            if (label != "不明") WeatherCodes.iconsFromLabel(label)
        }
    }
}
