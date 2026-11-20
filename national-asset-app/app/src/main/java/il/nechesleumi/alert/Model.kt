package il.nechesleumi.alert

import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val label: String) { DRILL("תרגיל"), REAL("אמת") }

enum class SmsState(val label: String) {
    NONE("—"), SENDING("נשלח…"), SENT("נשלח"), DELIVERED("נמסר"), FAILED("נכשל")
}

enum class CallState(val label: String) {
    IDLE("—"),
    WAITING("בתור"),
    DIALING("מחייג…"),
    IN_CALL("בשיחה"),
    ANSWERED("ענה"),
    MAYBE("ענה? אולי תא קולי"),
    NO_ANSWER("לא ענה"),
    UNKNOWN("לא ידוע"),
    FAILED("שגיאה"),
}

data class Contact(val id: String, val name: String, val phone: String)

/** One person's status inside an event. */
data class Entry(
    val contactId: String,
    val name: String,
    val phone: String,
    val sms: SmsState = SmsState.NONE,
    val replyText: String? = null,
    val replyAt: Long? = null,
    val call: CallState = CallState.IDLE,
    val callSid: String? = null,
    val attempts: Int = 0,
    val answeredAt: Long? = null,
    val manualAt: Long? = null,
    val error: String? = null,
) {
    val found: Boolean get() = replyAt != null || answeredAt != null || manualAt != null
    val foundAt: Long? get() = listOfNotNull(replyAt, answeredAt, manualAt).minOrNull()
    val foundHow: String
        get() = buildList {
            if (answeredAt != null) add("ענה לשיחה")
            if (replyAt != null) add("השיב בהודעה")
            if (manualAt != null) add("סומן ידנית")
        }.joinToString(" · ")
}

data class EventData(
    val id: Long,
    val mode: Mode,
    val startedAt: Long,
    val endedAt: Long?,
    val entries: List<Entry>,
) {
    val active: Boolean get() = endedAt == null
    val foundCount: Int get() = entries.count { it.found }
}

data class Settings(
    val smsDrill: String = "הופעל נכס לאומי – תרגיל. נא להשיב להודעה זו לאישור קבלה.",
    val smsReal: String = "הופעל נכס לאומי – אמת! נא להשיב להודעה זו לאישור קבלה.",
    val callText: String = "נכס לאומי",
    val announce: Boolean = true,
    val callWindowSec: Int = 35,
    val maxAttempts: Int = 2,
    val retryDelaySec: Int = 60,
    val sendSms: Boolean = true,
    val makeCalls: Boolean = true,
    val autoStart: Boolean = false,
    val triggerSenders: String = "",
) {
    fun smsFor(mode: Mode) = if (mode == Mode.REAL) smsReal else smsDrill
    fun callTextFor(mode: Mode) = callText.replace("{mode}", mode.label)
}

// ---------------------------------------------------------------- JSON

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
private fun JSONObject.lng(k: String): Long? = if (isNull(k)) null else optLong(k)

object Json {
    fun contacts(list: List<Contact>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) }
    }.toString()

    fun contacts(s: String?): List<Contact> {
        if (s.isNullOrBlank()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Contact(it.getString("id"), it.optString("name"), it.optString("phone")) }
    }

    fun settings(s: Settings): String = JSONObject()
        .put("smsDrill", s.smsDrill).put("smsReal", s.smsReal)
        .put("callText", s.callText).put("announce", s.announce).put("callWindowSec", s.callWindowSec)
        .put("maxAttempts", s.maxAttempts).put("retryDelaySec", s.retryDelaySec)
        .put("sendSms", s.sendSms).put("makeCalls", s.makeCalls)
        .put("autoStart", s.autoStart).put("triggerSenders", s.triggerSenders)
        .toString()

    fun settings(str: String?): Settings {
        val d = Settings()
        if (str.isNullOrBlank()) return d
        val o = JSONObject(str)
        return Settings(
            smsDrill = o.optString("smsDrill", d.smsDrill),
            smsReal = o.optString("smsReal", d.smsReal),
            callText = o.optString("callText", d.callText),
            announce = o.optBoolean("announce", d.announce),
            callWindowSec = o.optInt("callWindowSec", d.callWindowSec),
            maxAttempts = o.optInt("maxAttempts", d.maxAttempts),
            retryDelaySec = o.optInt("retryDelaySec", d.retryDelaySec),
            sendSms = o.optBoolean("sendSms", d.sendSms),
            makeCalls = o.optBoolean("makeCalls", d.makeCalls),
            autoStart = o.optBoolean("autoStart", d.autoStart),
            triggerSenders = o.optString("triggerSenders", d.triggerSenders),
        )
    }

    fun event(e: EventData): String = JSONObject()
        .put("id", e.id).put("mode", e.mode.name).put("startedAt", e.startedAt)
        .put("endedAt", e.endedAt ?: JSONObject.NULL)
        .put("entries", JSONArray().apply {
            e.entries.forEach { x ->
                put(
                    JSONObject()
                        .put("contactId", x.contactId).put("name", x.name).put("phone", x.phone)
                        .put("sms", x.sms.name)
                        .put("replyText", x.replyText ?: JSONObject.NULL)
                        .put("replyAt", x.replyAt ?: JSONObject.NULL)
                        .put("call", x.call.name)
                        .put("callSid", x.callSid ?: JSONObject.NULL)
                        .put("attempts", x.attempts)
                        .put("answeredAt", x.answeredAt ?: JSONObject.NULL)
                        .put("manualAt", x.manualAt ?: JSONObject.NULL)
                        .put("error", x.error ?: JSONObject.NULL)
                )
            }
        }).toString()

    fun event(s: String?): EventData? {
        if (s.isNullOrBlank()) return null
        val o = JSONObject(s)
        val a = o.getJSONArray("entries")
        return EventData(
            id = o.getLong("id"),
            mode = Mode.valueOf(o.getString("mode")),
            startedAt = o.getLong("startedAt"),
            endedAt = o.lng("endedAt"),
            entries = (0 until a.length()).map { a.getJSONObject(it) }.map { x ->
                Entry(
                    contactId = x.getString("contactId"),
                    name = x.optString("name"),
                    phone = x.optString("phone"),
                    sms = SmsState.valueOf(x.optString("sms", "NONE")),
                    replyText = x.str("replyText"),
                    replyAt = x.lng("replyAt"),
                    call = runCatching { CallState.valueOf(x.optString("call", "IDLE")) }.getOrDefault(CallState.IDLE),
                    callSid = x.str("callSid"),
                    attempts = x.optInt("attempts"),
                    answeredAt = x.lng("answeredAt"),
                    manualAt = x.lng("manualAt"),
                    error = x.str("error"),
                )
            },
        )
    }
}
