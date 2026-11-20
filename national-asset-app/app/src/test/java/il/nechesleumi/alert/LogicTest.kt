package il.nechesleumi.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun phones() {
        assertEquals("+972521234567", Phones.e164("052-123-4567"))
        assertEquals("+972521234567", Phones.e164("+972 52 123 4567"))
        assertEquals("+972521234567", Phones.e164("00972521234567"))
        assertEquals("0521234567", Phones.local("972521234567"))
        assertTrue(Phones.same("0521234567", "+972521234567"))
        assertFalse(Phones.same("0521234567", "0521234568"))
        assertFalse(Phones.same("", ""))
        assertTrue(Phones.same("PIKUD", "pikud"))
    }

    @Test fun trigger() {
        assertEquals(Mode.DRILL, Trigger.detect("הופעל נכס לאומי תרגילי")?.mode)
        assertEquals(Mode.DRILL, Trigger.detect("\"הופעל נכס לאומי תרגיל\"")?.mode)
        assertEquals(Mode.REAL, Trigger.detect("הופעל \" נכס לאומי אמת\"")?.mode)
        assertNull(Trigger.detect("נכס לאומי – תרגיל מחר בשעה 10"))
        assertNull(Trigger.detect("הופעל משהו אחר"))
        // "לאמת" (to verify) is not "אמת" (real)
        assertEquals(Mode.DRILL, Trigger.detect("הופעל נכס לאומי תרגיל, נא לאמת קבלה")?.mode)
        // ambiguous -> ask
        assertNull(Trigger.detect("הופעל נכס לאומי")!!.mode)
    }

    @Test fun callOutcome() {
        assertEquals(CallState.UNKNOWN, CallLogic.outcome(null, remoteHungUp = true, manual = null))
        assertEquals(CallState.NO_ANSWER, CallLogic.outcome(0, remoteHungUp = true, manual = null))
        assertEquals(CallState.NO_ANSWER, CallLogic.outcome(0, remoteHungUp = false, manual = null))
        // connected and they hung up -> a person
        assertEquals(CallState.ANSWERED, CallLogic.outcome(8, remoteHungUp = true, manual = null))
        // connected until we hung up -> maybe voicemail
        assertEquals(CallState.MAYBE, CallLogic.outcome(20, remoteHungUp = false, manual = null))
        // the user's tap wins
        assertEquals(CallState.ANSWERED, CallLogic.outcome(0, remoteHungUp = false, manual = CallState.ANSWERED))
        assertEquals(CallState.NO_ANSWER, CallLogic.outcome(20, remoteHungUp = false, manual = CallState.NO_ANSWER))
    }

    @Test fun import() {
        val rows = Importer.parse("דנה כהן, 052-1234567\n0541112222 יוסי\n\nשורה בלי מספר")
        assertEquals(listOf("דנה כהן" to "0521234567", "יוסי" to "0541112222"), rows)
    }

    @Test fun eventJsonRoundTrip() {
        val ev = EventData(1, Mode.REAL, 1000, null, listOf(
            Entry("a", "דנה", "0521234567", sms = SmsState.DELIVERED, replyText = "קיבלתי", replyAt = 2000),
            Entry("b", "יוסי", "0541112222", call = CallState.NO_ANSWER, attempts = 2),
        ))
        val back = Json.event(Json.event(ev))!!
        assertEquals(ev, back)
        assertEquals(1, back.foundCount)
        assertTrue(Report.text(back, 5000).contains("נמצאו 1 מתוך 2"))
    }
}
