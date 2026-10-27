package com.example.weatherwidget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun day(date: String, observed: Boolean = false, label: String = "晴") =
        DayForecast(date, label, 20, 10, 10, observed = observed)

    @Test
    fun 過ぎた日は前の予報を残し10日より前は捨てる() {
        val previous = Forecast("jma", listOf(day("2026-10-10"), day("2026-10-20", label = "雨"), day("2026-10-25"), day("2026-10-26")))
        val new = Forecast("jma", listOf(day("2026-10-26", label = "曇"), day("2026-10-27")))
        val merged = new.withHistory(previous, "2026-10-26")
        assertEquals(listOf("2026-10-20", "2026-10-25", "2026-10-26", "2026-10-27"), merged.days.map { it.date })
        assertEquals("雨", merged.days[0].label)
        assertEquals("曇", merged.days[2].label)
    }

    @Test
    fun 送りの範囲() {
        val all = (16..31).map { day("2026-10-%02d".format(it)) } // 10/16〜10/31
        val home = DayWindow(all, "2026-10-26", 5, 0)
        assertEquals("2026-10-26", home.visible.first().date)
        assertTrue(home.canGoBack)
        assertTrue(home.canGoForward)
        // 先へは最後の日が右端に来るまで（10/27〜10/31）
        assertEquals(1, home.shifted(100))
        val end = DayWindow(all, "2026-10-26", 5, 1)
        assertEquals("2026-10-31", end.visible.last().date)
        assertFalse(end.canGoForward)
        // 過去へは持っている最初の日まで
        assertEquals(-10, home.shifted(-100))
        assertFalse(DayWindow(all, "2026-10-26", 5, -10).canGoBack)
    }

    @Test
    fun アメダスの観測を入れる() = runBlocking {
        val area = JmaApi.resolveArea(35.6544, 139.7369)!!
        val f = JmaApi.fetchForecast(area, null).withHistory(null, JmaApi.today())
        val withObs = JmaApi.addObservations(f, area)
        println("== 麻布十番（観測つき）")
        withObs.days.forEach { println("  ${it.date} ${it.label.ifEmpty { "（予報なし）" }} ${it.max}/${it.min} pop=${it.pop} rain=${it.rain} observed=${it.observed}") }
        val past = withObs.days.filter { it.date < JmaApi.today() }
        assertTrue("過ぎた日が7日以上（アメダスの観測は約9日分しか残らず、夜は古い日から消える）", past.size >= 7)
        assertTrue(past.all { it.observed && it.max != null && it.min != null && it.rain != null })
        assertNotNull("今日の最低気温を観測で補う", withObs.days.first { it.date == JmaApi.today() }.min)
    }
}
