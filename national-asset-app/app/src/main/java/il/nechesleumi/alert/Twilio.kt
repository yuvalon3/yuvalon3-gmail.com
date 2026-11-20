package il.nechesleumi.alert

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

class TwilioException(msg: String) : Exception(msg)

data class CallInfo(val status: String, val answeredBy: String?, val duration: Int)

/** Minimal Twilio REST client (blocking — call from Dispatchers.IO). */
class Twilio(private val sid: String, private val token: String) {
    private val base = "https://api.twilio.com/2010-04-01/Accounts/${sid.trim()}"

    private fun request(url: String, form: Map<String, String>? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            val auth = Base64.getEncoder().encodeToString("${sid.trim()}:${token.trim()}".toByteArray())
            c.setRequestProperty("Authorization", "Basic $auth")
            c.setRequestProperty("Accept", "application/json")
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                val body = form.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                val msg = json.optString("message").ifBlank { "HTTP $code" }
                throw TwilioException(if (code == 401) "פרטי Twilio שגויים (401)" else msg)
            }
            return json
        } finally {
            c.disconnect()
        }
    }

    fun accountName(): String = request("$base.json").optString("friendly_name")

    /** Places a call that plays [twiml]. Returns the call SID. */
    fun startCall(to: String, from: String, twiml: String, detectMachine: Boolean): String {
        val form = linkedMapOf(
            "To" to Phones.e164(to),
            "From" to Phones.e164(from),
            "Twiml" to twiml,
            "Timeout" to "30",
        )
        if (detectMachine) form["MachineDetection"] = "Enable"
        return request("$base/Calls.json", form).getString("sid")
    }

    fun fetchCall(callSid: String): CallInfo {
        val o = request("$base/Calls/$callSid.json")
        return CallInfo(
            status = o.optString("status"),
            answeredBy = if (o.isNull("answered_by")) null else o.optString("answered_by"),
            duration = if (o.isNull("duration")) 0 else o.optString("duration").toIntOrNull() ?: 0,
        )
    }

    fun hangup(callSid: String) {
        request("$base/Calls/$callSid.json", mapOf("Status" to "completed"))
    }
}
