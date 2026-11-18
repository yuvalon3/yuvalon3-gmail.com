package com.shabbatguard.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings, stored in device-protected storage so they can be read right after a
 * reboot, before the phone is unlocked (relevant if the phone restarts on Shabbat).
 */
class Prefs(context: Context) {
    private val sp = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("guard", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var cityIndex: Int
        get() = sp.getInt("city", 0)
        set(v) = sp.edit().putInt("city", v).apply()

    var lat: Double
        get() = sp.getString("lat", null)?.toDoubleOrNull() ?: Cities.ALL[0].lat
        set(v) = sp.edit().putString("lat", v.toString()).apply()

    var lon: Double
        get() = sp.getString("lon", null)?.toDoubleOrNull() ?: Cities.ALL[0].lon
        set(v) = sp.edit().putString("lon", v.toString()).apply()

    var minutesBefore: Int
        get() = sp.getInt("before", 40)
        set(v) = sp.edit().putInt("before", v).apply()

    var minutesAfter: Int
        get() = sp.getInt("after", 50)
        set(v) = sp.edit().putInt("after", v).apply()

    var holidays: Boolean
        get() = sp.getBoolean("holidays", true)
        set(v) = sp.edit().putBoolean("holidays", v).apply()

    var diaspora: Boolean
        get() = sp.getBoolean("diaspora", false)
        set(v) = sp.edit().putBoolean("diaspora", v).apply()

    var useProximity: Boolean
        get() = sp.getBoolean("proximity", false)
        set(v) = sp.edit().putBoolean("proximity", v).apply()

    var motionSeconds: Int
        get() = sp.getInt("motionSec", 4)
        set(v) = sp.edit().putInt("motionSec", v).apply()

    var switchDelaySeconds: Int
        get() = sp.getInt("delaySec", 3)
        set(v) = sp.edit().putInt("delaySec", v).apply()

    var stillMinutes: Int
        get() = sp.getInt("stillMin", 5)
        set(v) = sp.edit().putInt("stillMin", v).apply()

    var walkThreshold: Float
        get() = sp.getFloat("walkThr", 1.2f)
        set(v) = sp.edit().putFloat("walkThr", v).apply()

    var stillThreshold: Float
        get() = sp.getFloat("stillThr", 0.12f)
        set(v) = sp.edit().putFloat("stillThr", v).apply()

    var tiltDegrees: Float
        get() = sp.getFloat("tiltDeg", 5f)
        set(v) = sp.edit().putFloat("tiltDeg", v).apply()

    /** Ringer mode from before the guard started (-1 = nothing saved). */
    var savedRingerMode: Int
        get() = sp.getInt("savedRinger", -1)
        set(v) = sp.edit().putInt("savedRinger", v).commit().let { }

    var activeUntil: Long
        get() = sp.getLong("activeUntil", 0)
        set(v) = sp.edit().putLong("activeUntil", v).commit().let { }

    var activeLabel: String
        get() = sp.getString("activeLabel", "") ?: ""
        set(v) = sp.edit().putString("activeLabel", v).apply()

    /** A window that was stopped by hand is not restarted until this time. */
    var skipUntil: Long
        get() = sp.getLong("skipUntil", 0)
        set(v) = sp.edit().putLong("skipUntil", v).apply()

    var nextStart: Long
        get() = sp.getLong("nextStart", 0)
        set(v) = sp.edit().putLong("nextStart", v).apply()

    var nextEnd: Long
        get() = sp.getLong("nextEnd", 0)
        set(v) = sp.edit().putLong("nextEnd", v).apply()

    var nextLabel: String
        get() = sp.getString("nextLabel", "") ?: ""
        set(v) = sp.edit().putString("nextLabel", v).apply()

    fun motionConfig() = MotionConfig(
        motionSeconds = motionSeconds,
        switchDelaySeconds = switchDelaySeconds,
        stillMinutes = stillMinutes,
        walkThreshold = walkThreshold,
        stillThreshold = stillThreshold,
        tiltDegrees = tiltDegrees,
    )

    val log: List<String>
        get() = (sp.getString("log", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun addLog(msg: String) {
        val line = LOG_FMT.format(Instant.now().atZone(ZoneId.systemDefault())) + "  " + msg
        val lines = (listOf(line) + log).take(150)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().remove("log").apply()

    companion object {
        private val LOG_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
    }
}

data class City(val name: String, val lat: Double, val lon: Double)

object Cities {
    val ALL = listOf(
        City("ירושלים", 31.778, 35.235),
        City("תל אביב", 32.080, 34.780),
        City("חיפה", 32.794, 34.990),
        City("באר שבע", 31.252, 34.791),
        City("בני ברק", 32.084, 34.833),
        City("פתח תקווה", 32.087, 34.887),
        City("אשדוד", 31.804, 34.655),
        City("נתניה", 32.332, 34.860),
        City("ראשון לציון", 31.964, 34.804),
        City("מודיעין", 31.899, 35.010),
        City("בית שמש", 31.747, 34.988),
        City("רחובות", 31.894, 34.809),
        City("צפת", 32.965, 35.496),
        City("טבריה", 32.795, 35.531),
        City("אילת", 29.558, 34.952),
        City("אחר (קואורדינטות ידניות)", 31.778, 35.235),
    )
    val CUSTOM_INDEX get() = ALL.lastIndex
}
