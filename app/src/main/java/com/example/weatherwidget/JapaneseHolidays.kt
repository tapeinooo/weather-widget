package com.example.weatherwidget

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.floor

/**
 * 日本の祝日（2000年以降）。通信せずに日付から求める。
 * 祝日法のとおり：固定の日、ハッピーマンデー、春分・秋分の日、振替休日、国民の休日。
 * 春分・秋分の日は 1980〜2099 年に使える近似式。
 */
object JapaneseHolidays {

    fun isHoliday(date: LocalDate): Boolean {
        if (date.year < 2000) return false
        return isBase(date) || isSubstitute(date) || isCitizensHoliday(date)
    }

    /** 祝日法で日が決まっている祝日（振替・国民の休日をのぞく）。 */
    private fun isBase(d: LocalDate): Boolean {
        val y = d.year
        val m = d.monthValue
        val day = d.dayOfMonth
        return when (m) {
            1 -> day == 1 || nthMonday(d, 2)
            2 -> day == 11 || (y >= 2020 && day == 23)
            3 -> day == springEquinox(y)
            4 -> day == 29
            5 -> day in 3..5
            7 -> when (y) {
                2020 -> day == 23
                2021 -> day == 22
                else -> y >= 2003 && nthMonday(d, 3)
            }
            8 -> when (y) {
                2020 -> day == 10
                2021 -> day == 8
                else -> y >= 2016 && day == 11
            }
            9 -> (y >= 2003 && nthMonday(d, 3)) || day == autumnEquinox(y)
            10 -> y != 2020 && y != 2021 && nthMonday(d, 2)
            11 -> day == 3 || day == 23
            12 -> y in 1989..2018 && day == 23
            else -> false
        }.let { it || specialDay(d) }
    }

    /** 2020・2021 年のオリンピックにともなう移動で、10 月でなく 7 月になったスポーツの日。 */
    private fun specialDay(d: LocalDate): Boolean =
        (d.year == 2020 && d.monthValue == 7 && d.dayOfMonth == 24) ||
            (d.year == 2021 && d.monthValue == 7 && d.dayOfMonth == 23)

    /** 日曜の祝日のあと、最初の祝日でない日（前に続く祝日の中に日曜があれば）。 */
    private fun isSubstitute(d: LocalDate): Boolean {
        if (isBase(d)) return false
        var p = d.minusDays(1)
        while (isBase(p)) {
            if (p.dayOfWeek == DayOfWeek.SUNDAY) return true
            p = p.minusDays(1)
        }
        return false
    }

    /** 前の日と次の日がどちらも祝日にはさまれた日（日曜をのぞく）。 */
    private fun isCitizensHoliday(d: LocalDate): Boolean =
        !isBase(d) && d.dayOfWeek != DayOfWeek.SUNDAY &&
            isBase(d.minusDays(1)) && isBase(d.plusDays(1))

    /** その月の第 n 月曜日か。 */
    private fun nthMonday(d: LocalDate, n: Int): Boolean =
        d.dayOfWeek == DayOfWeek.MONDAY && (d.dayOfMonth - 1) / 7 + 1 == n

    private fun springEquinox(year: Int): Int =
        floor(20.8431 + 0.242194 * (year - 1980) - floor((year - 1980) / 4.0)).toInt()

    private fun autumnEquinox(year: Int): Int =
        floor(23.2488 + 0.242194 * (year - 1980) - floor((year - 1980) / 4.0)).toInt()
}
