package com.shabbatguard.app

import android.icu.util.Calendar
import android.icu.util.HebrewCalendar
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.ZoneOffset

/** Yom Tov days (days with Shabbat-like restrictions), using the built-in Hebrew calendar. */
object Holidays {
    fun name(date: LocalDate, diaspora: Boolean): String? {
        val cal = HebrewCalendar(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return when (month) {
            HebrewCalendar.TISHRI -> when (day) {
                1, 2 -> "ראש השנה"
                10 -> "יום כיפור"
                15 -> "סוכות"
                16 -> if (diaspora) "סוכות" else null
                22 -> if (diaspora) "שמיני עצרת" else "שמיני עצרת ושמחת תורה"
                23 -> if (diaspora) "שמחת תורה" else null
                else -> null
            }
            HebrewCalendar.NISAN -> when (day) {
                15 -> "פסח"
                16 -> if (diaspora) "פסח" else null
                21 -> "שביעי של פסח"
                22 -> if (diaspora) "אחרון של פסח" else null
                else -> null
            }
            HebrewCalendar.SIVAN -> when (day) {
                6 -> "שבועות"
                7 -> if (diaspora) "שבועות" else null
                else -> null
            }
            else -> null
        }
    }
}
