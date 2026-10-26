package com.example.weatherwidget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {
    private fun holidays(year: Int): List<String> =
        generateSequence(LocalDate.of(year, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year == year }
            .filter { JapaneseHolidays.isHoliday(it) }
            .map { "${it.monthValue}/${it.dayOfMonth}" }
            .toList()

    // 内閣府の「国民の祝日」の一覧（振替休日・国民の休日を含む）
    @Test
    fun `2025年`() = assertEquals(
        "1/1 1/13 2/11 2/23 2/24 3/20 4/29 5/3 5/4 5/5 5/6 7/21 8/11 9/15 9/23 10/13 11/3 11/23 11/24".split(" "),
        holidays(2025),
    )

    @Test
    fun `2026年`() = assertEquals(
        "1/1 1/12 2/11 2/23 3/20 4/29 5/3 5/4 5/5 5/6 7/20 8/11 9/21 9/22 9/23 10/12 11/3 11/23".split(" "),
        holidays(2026),
    )

    @Test
    fun `2027年`() = assertEquals(
        "1/1 1/11 2/11 2/23 3/21 3/22 4/29 5/3 5/4 5/5 7/19 8/11 9/20 9/23 10/11 11/3 11/23".split(" "),
        holidays(2027),
    )

    @Test
    fun `2020年はオリンピックで移動`() = assertEquals(
        "1/1 1/13 2/11 2/23 2/24 3/20 4/29 5/3 5/4 5/5 5/6 7/23 7/24 8/10 9/21 9/22 11/3 11/23".split(" "),
        holidays(2020),
    )
}
