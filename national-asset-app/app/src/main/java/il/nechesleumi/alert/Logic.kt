package il.nechesleumi.alert

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Israeli phone-number helpers. */
object Phones {
    /** "+972-52-123-4567" / "972521234567" / "0521234567" -> "0521234567" */
    fun local(p: String): String {
        var d = p.filter(Char::isDigit)
        if (d.startsWith("972")) d = "0" + d.substring(3)
        if (d.length == 9 && !d.startsWith("0")) d = "0$d"
        return d
    }

    /** To E.164 for dialing / SMS: "052-1234567" -> "+972521234567". Foreign "+1..." numbers are kept. */
    fun e164(p: String): String {
        val t = p.trim()
        if (t.startsWith("+")) return "+" + t.filter(Char::isDigit)
        if (t.startsWith("00")) return "+" + t.filter(Char::isDigit).substring(2)
        val l = local(t)
        return if (l.startsWith("0")) "+972" + l.substring(1) else "+$l"
    }

    /** Same subscriber? Compares the last 9 digits, so 052…, +97252… and 97252… all match. */
    fun same(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit)
        val y = b.filter(Char::isDigit)
        if (x.length < 7 || y.length < 7) return a.trim().equals(b.trim(), ignoreCase = true) && a.isNotBlank()
        return x.takeLast(9) == y.takeLast(9)
    }
}

/** Recognises the incoming "הופעל נכס לאומי תרגילי / אמת" message. */
object Trigger {
    data class Detection(val mode: Mode?)

    private val punct = Regex("[\"'׳״\\-–—_.,!?:;()\\[\\]]")
    private val activated = Regex("הופעל|הפעלת|הפעלה|מופעל")
    private val realWord = Regex("(?<![א-ת])[בהו]?אמת(?![א-ת])")

    fun detect(body: String): Detection? {
        val t = body.replace(punct, " ").replace(Regex("\\s+"), " ")
        if (!t.contains("נכס לאומי")) return null
        if (!activated.containsMatchIn(t)) return null
        val drill = t.contains("תרגיל")
        val real = realWord.containsMatchIn(t)
        return Detection(
            when {
                drill && !real -> Mode.DRILL
                real && !drill -> Mode.REAL
                else -> null // unclear: ask the user
            }
        )
    }
}

/** Decides how a phone call ended, from the call log. */
object CallLogic {
    /**
     * @param durationSec connected time from the call log (null = no log entry / no permission)
     * @param remoteHungUp the other side ended the call before our time window ran out
     * @param manual what the user tapped during the call (ענה / לא ענה), if anything
     */
    fun outcome(durationSec: Int?, remoteHungUp: Boolean, manual: CallState?): CallState = when {
        manual != null -> manual
        durationSec == null -> CallState.UNKNOWN
        durationSec <= 0 -> CallState.NO_ANSWER
        // They picked up, heard the announcement and hung up: a person, not a voicemail box.
        remoteHungUp -> CallState.ANSWERED
        // Connected until we hung up: a person who stayed on the line, or voicemail.
        else -> CallState.MAYBE
    }
}

/** Bulk import: one person per line, e.g. "דנה כהן, 052-1234567" or "0521234567 יוסי". */
object Importer {
    private val phoneRe = Regex("\\+?\\d[\\d\\- ]{6,}\\d")

    fun parse(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val m = phoneRe.find(line) ?: return@mapNotNull null
        val phone = m.value.filter { it.isDigit() || it == '+' }
        val name = line.removeRange(m.range).trim(' ', ',', ';', '\t', '-', '|', ':').ifBlank { phone }
        name to phone
    }
}

object Report {
    private fun time(t: Long?) = t?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: ""

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    fun text(e: EventData, now: Long = System.currentTimeMillis()): String = buildString {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(e.startedAt))
        appendLine("נכס לאומי – ${e.mode.label}")
        appendLine("התחלה: $date · משך: ${duration((e.endedAt ?: now) - e.startedAt)}")
        appendLine("נמצאו ${e.foundCount} מתוך ${e.entries.size}")
        appendLine()
        e.entries.sortedWith(compareBy<Entry> { !it.found }.thenBy { it.foundAt ?: Long.MAX_VALUE }).forEach { x ->
            if (x.found) {
                append("✅ ${x.name} – ${x.foundHow} ${time(x.foundAt)} (+${duration(x.foundAt!! - e.startedAt)})")
                x.replyText?.let { append(" \"${it.take(60)}\"") }
            } else {
                append("❌ ${x.name} ${x.phone} – שיחה: ${x.call.label}")
                if (x.attempts > 0) append(" (${x.attempts} ניסיונות)")
                append(", SMS: ${x.sms.label}")
            }
            appendLine()
        }
    }
}
