package com.shabbatguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sin

class SunCalcTest {
    private val il = ZoneId.of("Asia/Jerusalem")

    private fun local(i: Instant) = i.atZone(il).toLocalDateTime()

    private fun assertNear(expected: LocalDateTime, actual: Instant, minutes: Long = 3) {
        val diff = abs(java.time.Duration.between(expected, local(actual)).toMinutes())
        assertTrue("expected ~$expected got ${local(actual)}", diff <= minutes)
    }

    @Test fun jerusalemSummerSunset() {
        // Jerusalem, 21 June 2024: sea-level sunset ≈ 19:47 IDT.
        assertNear(LocalDateTime.of(2024, 6, 21, 19, 47), SunCalc.sunset(LocalDate.of(2024, 6, 21), 31.778, 35.235, il)!!)
    }

    @Test fun telAvivWinterSunset() {
        // Tel Aviv, 21 Dec 2024: sunset ≈ 16:40 IST.
        assertNear(LocalDateTime.of(2024, 12, 21, 16, 40), SunCalc.sunset(LocalDate.of(2024, 12, 21), 32.08, 34.78, il)!!)
    }

    @Test fun newYorkSunsetIsOnTheRightDay() {
        val ny = ZoneId.of("America/New_York")
        val s = SunCalc.sunset(LocalDate.of(2024, 6, 21), 40.71, -74.0, ny)!!
        val l = s.atZone(ny).toLocalDateTime()
        assertEquals(LocalDate.of(2024, 6, 21), l.toLocalDate())
        assertTrue("got $l", abs(java.time.Duration.between(LocalDateTime.of(2024, 6, 21, 20, 31), l).toMinutes()) <= 3)
    }
}

class HolyWindowsTest {
    private val il = ZoneId.of("Asia/Jerusalem")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(il).toInstant()

    @Test fun plainShabbat() {
        // Wednesday 18 Nov 2026 → Shabbat 20-21 Nov.
        val w = HolyWindows.upcoming(at(2026, 11, 18, 12, 0), il, 31.778, 35.235, 40, 50) { null }!!
        val s = w.start.atZone(il).toLocalDateTime()
        val e = w.end.atZone(il).toLocalDateTime()
        assertEquals(LocalDate.of(2026, 11, 20), s.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 21), e.toLocalDate())
        assertTrue("start $s", s.hour == 15 || s.hour == 16)
        assertTrue("end $e", e.hour == 17)
        assertEquals("שבת", w.label)
    }

    @Test fun windowInProgressIsReturned() {
        val w = HolyWindows.upcoming(at(2026, 11, 21, 10, 0), il, 31.778, 35.235, 40, 50) { null }!!
        assertEquals(LocalDate.of(2026, 11, 21), w.end.atZone(il).toLocalDate())
    }

    @Test fun afterShabbatEndsGoesToNextWeek() {
        val w = HolyWindows.upcoming(at(2026, 11, 21, 21, 0), il, 31.778, 35.235, 40, 50) { null }!!
        assertEquals(LocalDate.of(2026, 11, 28), w.end.atZone(il).toLocalDate())
    }

    @Test fun roshHashanaAndShabbatMerge() {
        // Fake calendar: Thu 10 + Fri 11 Sep 2026 are Yom Tov, then Shabbat 12 Sep: one 3-day window.
        val yt = setOf(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))
        val w = HolyWindows.upcoming(at(2026, 9, 7, 12, 0), il, 31.778, 35.235, 40, 50) { if (it in yt) "ראש השנה" else null }!!
        assertEquals(LocalDate.of(2026, 9, 9), w.start.atZone(il).toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 12), w.end.atZone(il).toLocalDate())
        assertEquals("ראש השנה + שבת", w.label)
    }
}

class MotionLogicTest {
    private val cfg = MotionConfig(motionSeconds = 4, switchDelaySeconds = 3, stillMinutes = 5)

    /** Feeds one second (20 samples) and ticks. */
    private fun second(l: MotionLogic, t: Long, walking: Boolean, noise: Float = 0.02f): RingMode? {
        for (i in 0 until 20) {
            val wobble = if (walking) (3.0 * sin(i * Math.PI / 5)).toFloat() else noise * (if (i % 2 == 0) 1 else -1)
            l.onAccel(0.1f + wobble, wobble / 2, 9.81f + wobble)
        }
        return l.onTick(t)
    }

    @Test fun walkingSwitchesToVibrateAfterDelay() {
        var t = 0L
        val l = MotionLogic(cfg, RingMode.RING, t)
        second(l, t, false)
        val changes = mutableListOf<Pair<Long, RingMode>>()
        for (i in 1..20) {
            t += 1000
            second(l, t, walking = true)?.let { changes += t to it }
        }
        assertEquals(1, changes.size)
        assertEquals(RingMode.VIBRATE, changes[0].second)
        // 4 s of walking (t=4000) + 3 s delay = 7000.
        assertEquals(7000L, changes[0].first)
    }

    @Test fun shortPickupDoesNotSwitch() {
        var t = 0L
        val l = MotionLogic(cfg, RingMode.RING, t)
        for (i in 1..2) { t += 1000; assertNull(second(l, t, true)) }
        for (i in 1..30) { t += 1000; assertNull(second(l, t, false)) }
        assertEquals(RingMode.RING, l.mode)
    }

    @Test fun fiveStillMinutesGoBackToRing() {
        var t = 0L
        val l = MotionLogic(cfg, RingMode.VIBRATE, t)
        var back: Long? = null
        for (i in 1..400) {
            t += 1000
            if (second(l, t, false) == RingMode.RING) { back = t; break }
        }
        assertNotNull(back)
        assertEquals(300_000L, back)
    }

    @Test fun smallPocketMovementsKeepVibrate() {
        var t = 0L
        val l = MotionLogic(cfg, RingMode.VIBRATE, t)
        for (i in 1..900) {
            t += 1000
            // Sitting with the phone in a pocket: small movements (0.3 m/s²), well under walking.
            assertNull(second(l, t, false, noise = 0.3f))
        }
        assertEquals(RingMode.VIBRATE, l.mode)
    }

    @Test fun slowTiltCountsAsMovement() {
        var t = 0L
        val l = MotionLogic(cfg, RingMode.VIBRATE, t)
        // Phone slowly rotating by ~1° per second for 10 minutes, without any jolt.
        var angle = 0.0
        for (s in 1..600) {
            t += 1000
            for (i in 0 until 20) {
                angle += Math.toRadians(1.0 / 20)
                l.onAccel((9.81 * sin(angle)).toFloat(), 0f, (9.81 * kotlin.math.cos(angle)).toFloat())
            }
            assertNull("switched at $s", l.onTick(t))
        }
    }
}
