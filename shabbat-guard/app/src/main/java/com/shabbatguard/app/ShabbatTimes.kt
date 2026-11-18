package com.shabbatguard.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunset time (sea level, standard refraction), accurate to about a minute. */
object SunCalc {
    private const val ZENITH = 90.833

    fun sunset(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Instant? {
        val n = date.dayOfYear
        val lngHour = lon / 15.0
        val t = n + ((18.0 - lngHour) / 24.0)
        val m = 0.9856 * t - 3.289
        val l = norm360(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634)
        var ra = norm360(Math.toDegrees(atan(0.91764 * tanD(l))))
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra = (ra + lQuadrant - raQuadrant) / 15.0
        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(ZENITH) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH < -1.0 || cosH > 1.0) return null
        val h = Math.toDegrees(acos(cosH)) / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = ((localT - lngHour) % 24.0 + 24.0) % 24.0
        var instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            .plusMillis((ut * 3_600_000.0).toLong())
        val localDate = instant.atZone(zone).toLocalDate()
        if (localDate.isBefore(date)) instant = instant.plus(1, ChronoUnit.DAYS)
        else if (localDate.isAfter(date)) instant = instant.minus(1, ChronoUnit.DAYS)
        return instant
    }

    private fun norm360(x: Double) = ((x % 360.0) + 360.0) % 360.0
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
    private fun tanD(d: Double) = tan(Math.toRadians(d))
}

data class HolyWindow(val start: Instant, val end: Instant, val label: String)

object HolyWindows {
    /**
     * The current or next window in which the guard should run.
     * A holy day D runs from (sunset of D-1 − [beforeMin]) to (sunset of D + [afterMin]).
     * Back-to-back days (Yom Tov next to Shabbat, Rosh Hashana) are merged into one window.
     */
    fun upcoming(
        now: Instant,
        zone: ZoneId,
        lat: Double,
        lon: Double,
        beforeMin: Int,
        afterMin: Int,
        yomTov: (LocalDate) -> String?,
    ): HolyWindow? {
        val today = now.atZone(zone).toLocalDate()
        var cur: HolyWindow? = null
        for (i in -2L..70L) {
            val d = today.plusDays(i)
            val names = mutableListOf<String>()
            yomTov(d)?.let { names += it }
            if (d.dayOfWeek == DayOfWeek.SATURDAY) names += "שבת"
            if (names.isEmpty()) continue
            val w = HolyWindow(
                sunsetOrFallback(d.minusDays(1), lat, lon, zone).minus(beforeMin.toLong(), ChronoUnit.MINUTES),
                sunsetOrFallback(d, lat, lon, zone).plus(afterMin.toLong(), ChronoUnit.MINUTES),
                names.joinToString(" ו"),
            )
            val c = cur
            if (c != null && !w.start.isAfter(c.end)) {
                cur = HolyWindow(c.start, w.end, mergeLabels(c.label, w.label))
            } else {
                if (c != null && c.end.isAfter(now)) return c
                cur = w
            }
        }
        return cur?.takeIf { it.end.isAfter(now) }
    }

    private fun mergeLabels(a: String, b: String): String {
        val parts = (a.split(" + ") + b.split(" + ")).distinct()
        return parts.joinToString(" + ")
    }

    private fun sunsetOrFallback(d: LocalDate, lat: Double, lon: Double, zone: ZoneId): Instant =
        SunCalc.sunset(d, lat, lon, zone) ?: d.atTime(18, 0).atZone(zone).toInstant()
}
