package com.example.weatherwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** 地点に対応する気象庁の予報区域。 */
data class JmaArea(
    val office: String,     // 府県予報区（予報の JSON のファイル名）
    val class10: String,    // 一次細分区域（東京地方など）
    val amedas: String,     // 今日・明日の気温の地点
    val weekArea: String,   // 週間予報の区域
    val weekAmedas: String, // 週間予報の気温の地点
) {
    fun toJson(): String = JSONObject()
        .put("office", office).put("class10", class10).put("amedas", amedas)
        .put("weekArea", weekArea).put("weekAmedas", weekAmedas)
        .toString()

    companion object {
        fun fromJson(json: String): JmaArea? = runCatching {
            val o = JSONObject(json)
            JmaArea(
                o.getString("office"), o.getString("class10"), o.getString("amedas"),
                o.getString("weekArea"), o.getString("weekAmedas"),
            )
        }.getOrNull()
    }
}

/**
 * 気象庁ホームページの予報（https://www.jma.go.jp/bosai/forecast/）を読む。
 * 公式に案内された API ではないので、形が変わったら例外になり、Open-Meteo に切り替わる。
 */
object JmaApi {
    private const val BASE = "https://www.jma.go.jp/bosai"
    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

    /** 緯度経度から予報区域を引く。日本の外なら null。 */
    suspend fun resolveArea(lat: Double, lon: Double): JmaArea? {
        // 国土地理院の逆ジオコーダーで市区町村コードを得る
        val geo = JSONObject(Http.get(
            "https://mreversegeocoder.gsi.go.jp/reverse-geocoder/LonLatToAddress" +
                "?lat=${coord(lat)}&lon=${coord(lon)}",
        ))
        val muni = geo.optJSONObject("results")?.optString("muniCd").orEmpty()
        if (muni.isBlank()) return null
        val muniCd = muni.padStart(5, '0')

        val area = JSONObject(Http.get("$BASE/common/const/area.json"))
        val class20s = area.getJSONObject("class20s")
        val keys = class20s.keys().asSequence().toList()
        // 市区町村コード＋"00" が基本。政令市の区などは市のコード（先頭4桁）で探す
        val class20 = (muniCd + "00").takeIf { class20s.has(it) }
            ?: keys.firstOrNull { it.startsWith(muniCd) }
            ?: keys.firstOrNull { it.startsWith(muniCd.take(4)) }
            ?: return null
        val class15 = class20s.getJSONObject(class20).getString("parent")
        val class10 = area.getJSONObject("class15s").getJSONObject(class15).getString("parent")
        val office = area.getJSONObject("class10s").getJSONObject(class10).getString("parent")

        val short = JSONObject(Http.get("$BASE/forecast/const/forecast_area.json")).optJSONArray(office)
        val amedas = short?.objects()?.firstOrNull { it.optString("class10") == class10 }
            ?.optJSONArray("amedas")?.optString(0)
            ?: return null
        val week = JSONObject(Http.get("$BASE/forecast/const/week_area.json")).optJSONArray(office)
            ?.objects()?.firstOrNull { it.optString("srf") == class10 }
        return JmaArea(
            office = office,
            class10 = class10,
            amedas = amedas,
            weekArea = week?.optString("week")?.ifEmpty { null } ?: class10,
            weekAmedas = week?.optString("amedas")?.ifEmpty { null } ?: amedas,
        )
    }

    suspend fun fetchForecast(area: JmaArea, previous: Forecast?): Forecast {
        // 奄美地方は鹿児島県のファイルに入っている
        val path = if (area.office == "460040") "460100" else area.office
        val root = JSONArray(Http.get("$BASE/forecast/data/forecast/$path.json"))
        val today = today()

        class Day(var code: String? = null, var max: Int? = null, var min: Int? = null, var pop: Int? = null)
        val days = sortedMapOf<String, Day>()
        fun day(time: String) = days.getOrPut(time.take(10)) { Day() }

        // 今日・明日・明後日（府県天気予報）
        val short = root.getJSONObject(0).getJSONArray("timeSeries")
        short.seriesArea(0, area.class10)?.let { (times, a) ->
            val codes = a.getJSONArray("weatherCodes")
            times.forEachIndexed { i, t -> codes.optString(i).ifEmpty { null }?.let { day(t).code = it } }
        }
        short.seriesArea(1, area.class10)?.let { (times, a) ->
            val pops = a.getJSONArray("pops")
            times.forEachIndexed { i, t ->
                pops.optString(i).toIntOrNull()?.let { p -> day(t).pop = maxOf(day(t).pop ?: 0, p) }
            }
        }
        short.seriesArea(2, area.amedas)?.let { (times, a) ->
            val temps = a.getJSONArray("temps")
            times.forEachIndexed { i, t ->
                val v = temps.optString(i).toIntOrNull() ?: return@forEachIndexed
                val d = day(t)
                when {
                    // 今日の分は日中の最高気温だけが出る（朝の最低気温は発表後に消える）
                    t.startsWith(today) -> d.max = maxOf(d.max ?: v, v)
                    t.substring(11, 13) == "00" -> d.min = v
                    else -> d.max = v
                }
            }
        }

        // 週間天気予報（明日から7日分）。府県天気予報にない日・値だけ使う
        val week = root.optJSONObject(1)?.optJSONArray("timeSeries")
        week?.seriesArea(0, area.weekArea)?.let { (times, a) ->
            val codes = a.getJSONArray("weatherCodes")
            val pops = a.optJSONArray("pops")
            times.forEachIndexed { i, t ->
                val d = day(t)
                if (d.code == null) d.code = codes.optString(i).ifEmpty { null }
                if (d.pop == null) d.pop = pops?.optString(i)?.toIntOrNull()
            }
        }
        week?.seriesArea(1, area.weekAmedas)?.let { (times, a) ->
            val maxes = a.optJSONArray("tempsMax")
            val mins = a.optJSONArray("tempsMin")
            times.forEachIndexed { i, t ->
                val d = day(t)
                if (d.max == null) d.max = maxes?.optString(i)?.toIntOrNull()
                if (d.min == null) d.min = mins?.optString(i)?.toIntOrNull()
            }
        }

        val result = days.filterKeys { it >= today }.mapNotNull { (date, d) ->
            val code = d.code ?: return@mapNotNull null
            DayForecast(date, JmaTelops.label(code), d.max, d.min, d.pop)
        }
        if (result.isEmpty()) throw IllegalStateException("気象庁の予報に $today 以降の日がない")
        return Forecast(Forecast.SOURCE_JMA, result).fillFrom(previous)
    }

    /** 予報を読むときの「今日」（日本時間）。 */
    fun today(): String = LocalDate.now(JST).toString()

    /**
     * アメダスの観測を入れる。
     * - 過ぎた日（10日前まで）で、まだ観測を入れていない日：その日の最高・最低気温と降水量
     * - 今日：最高・最低気温の予報がなければ、今までの観測で補う（朝の最低気温は予報から消えるため）
     * 読めなかった日はそのままにして、次の更新でまた試す。
     */
    suspend fun addObservations(forecast: Forecast, area: JmaArea): Forecast {
        val today = LocalDate.now(JST)
        val days = forecast.days.associateBy { it.date }.toMutableMap()
        for (back in Forecast.HISTORY_DAYS downTo 1) {
            val date = today.minusDays(back)
            val key = date.toString()
            if (days[key]?.observed == true) continue
            val obs = runCatching { observe(area.amedas, date, hour = 21) }.getOrNull() ?: continue
            val base = days[key] ?: DayForecast(key, "", null, null, null)
            days[key] = base.copy(max = obs.max ?: base.max, min = obs.min ?: base.min, rain = obs.rain, observed = true)
        }
        val todayKey = today.toString()
        days[todayKey]?.takeIf { it.max == null || it.min == null }?.let { d ->
            val latest = Http.get("$BASE/amedas/data/latest_time.txt").trim()
            val hour = latest.substring(11, 13).toIntOrNull() ?: return@let
            val obs = runCatching { observe(area.amedas, today, hour = hour / 3 * 3) }.getOrNull() ?: return@let
            days[todayKey] = d.copy(max = d.max ?: obs.max, min = d.min ?: obs.min)
        }
        return forecast.copy(days = days.values.sortedBy { it.date })
    }

    private class Observation(val max: Int?, val min: Int?, val rain: Double?)

    /** アメダスの3時間分のファイルの最後の値（その日のそれまでの最高・最低気温と24時間降水量）。 */
    private suspend fun observe(amedas: String, date: LocalDate, hour: Int): Observation? {
        val file = date.toString().replace("-", "") + "_" + "%02d".format(hour)
        val data = JSONObject(Http.get("$BASE/amedas/data/point/$amedas/$file.json"))
        val last = data.keys().asSequence().maxOrNull()?.let { data.getJSONObject(it) } ?: return null
        fun value(name: String): Double? = last.optJSONArray(name)?.takeUnless { it.isNull(0) }?.optDouble(0)
        return Observation(
            max = value("maxTemp")?.roundToInt(),
            min = value("minTemp")?.roundToInt(),
            rain = value("precipitation24h"),
        )
    }

    /** timeSeries[index] の中から code の区域を探し、(時刻の一覧, 区域) を返す。 */
    private fun JSONArray.seriesArea(index: Int, code: String): Pair<List<String>, JSONObject>? {
        val series = optJSONObject(index) ?: return null
        val defines = series.getJSONArray("timeDefines")
        val times = (0 until defines.length()).map { defines.getString(it) }
        val a = series.getJSONArray("areas").objects()
            .firstOrNull { it.getJSONObject("area").getString("code") == code } ?: return null
        return times to a
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun coord(value: Double) = String.format(Locale.US, "%.5f", value)
}
