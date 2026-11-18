package com.shabbatguard.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object Scheduler {
    private const val REQ_ALARM = 1
    private const val REQ_SHOW = 2
    /** If exact alarms are not allowed, start this much earlier to be safe. */
    private const val INEXACT_MARGIN_MS = 15 * 60_000L

    fun computeNext(p: Prefs, now: Instant): HolyWindow? =
        HolyWindows.upcoming(now, ZoneId.systemDefault(), p.lat, p.lon, p.minutesBefore, p.minutesAfter) { d ->
            if (p.holidays) Holidays.name(d, p.diaspora) else null
        }

    /** Arms the alarm for the next Shabbat / Yom Tov, or starts the guard now if one is in progress. */
    fun reschedule(ctx: Context) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val alarmPi = alarmIntent(ctx)
        am.cancel(alarmPi)

        if (!p.autoEnabled) {
            p.nextStart = 0; p.nextEnd = 0; p.nextLabel = ""
            return
        }

        var now = Instant.now()
        var w = computeNext(p, now)
        // A window the user stopped by hand: look at the one after it.
        if (w != null && w.end.toEpochMilli() <= p.skipUntil) {
            now = w.end.plusSeconds(1)
            w = computeNext(p, now)
        }
        if (w == null) {
            p.nextStart = 0; p.nextEnd = 0; p.nextLabel = ""
            return
        }
        p.nextStart = w.start.toEpochMilli()
        p.nextEnd = w.end.toEpochMilli()
        p.nextLabel = w.label

        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val startAt = if (exact) w.start.toEpochMilli() else w.start.toEpochMilli() - INEXACT_MARGIN_MS
        val nowMs = System.currentTimeMillis()

        if (nowMs >= startAt) {
            try {
                GuardService.startWindow(ctx, w.end.toEpochMilli(), w.label)
            } catch (e: Exception) {
                // Background start refused: an alarm-clock alarm is always allowed to start it.
                p.addLog("הפעלה ישירה נחסמה (${e.javaClass.simpleName}), מנסה דרך שעון מעורר")
                setAlarm(ctx, am, nowMs + 5_000, exact, alarmPi)
            }
            return
        }
        setAlarm(ctx, am, startAt, exact, alarmPi)
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, at: Long, exact: Boolean, pi: PendingIntent) {
        if (exact) {
            val show = PendingIntent.getActivity(
                ctx, REQ_SHOW, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

object Ringer {
    fun current(ctx: Context): Int = ctx.getSystemService(AudioManager::class.java).ringerMode

    /** Returns an error message, or null on success. */
    fun set(ctx: Context, mode: RingMode): String? =
        setRaw(ctx, if (mode == RingMode.RING) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE)

    fun setRaw(ctx: Context, ringerMode: Int): String? = try {
        ctx.getSystemService(AudioManager::class.java).ringerMode = ringerMode
        null
    } catch (e: SecurityException) {
        "אין הרשאה לשנות מצב צלצול (כנראה 'נא לא להפריע' פעיל): ${e.message}"
    }

    fun name(ringerMode: Int) = when (ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "צלצול"
        AudioManager.RINGER_MODE_VIBRATE -> "רטט"
        AudioManager.RINGER_MODE_SILENT -> "שקט"
        else -> "?"
    }

    fun name(mode: RingMode) = if (mode == RingMode.RING) "צלצול 🔔" else "רטט 📳"
}
