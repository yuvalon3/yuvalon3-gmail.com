package il.nechesleumi.alert

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

/** Incoming SMS: a reply from someone on the list, or the "הופעל נכס לאומי" trigger. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.init(ctx.applicationContext)
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        msgs.groupBy { it.originatingAddress.orEmpty() }.forEach { (from, parts) ->
            val body = parts.joinToString("") { it.messageBody.orEmpty() }

            // 1) A reply from someone on the list during an active event.
            if (Store.onIncomingSms(from, body)) return@forEach

            // 2) The activation message.
            val det = Trigger.detect(body) ?: return@forEach
            val s = Store.settings.value
            val allowed = s.triggerSenders.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (allowed.isNotEmpty() && allowed.none { Phones.same(it, from) }) return@forEach
            if (Store.event.value?.active == true) return@forEach

            var started = false
            if (s.autoStart && det.mode != null && Store.contacts.value.isNotEmpty()) {
                started = try {
                    EventService.start(ctx, det.mode)
                    true
                } catch (e: Exception) {
                    false // background start not allowed on this device: fall back to the alert
                }
            }
            Notifications.showTrigger(ctx, det.mode, body, started)
        }
    }
}

/** "Sent" / "delivered" reports for the SMS the app sends. */
class SmsStatusReceiver : BroadcastReceiver() {
    companion object {
        const val KIND_SENT = "il.nechesleumi.alert.SMS_SENT"
        const val KIND_DELIVERED = "il.nechesleumi.alert.SMS_DELIVERED"
        const val EXTRA_ID = "id"
        const val EXTRA_EVENT = "event"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx.applicationContext)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val evId = intent.getLongExtra(EXTRA_EVENT, -1)
        if (Store.event.value?.id != evId) return

        when (intent.action) {
            KIND_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                Store.updateEntry(id) {
                    when {
                        it.sms == SmsState.DELIVERED -> it
                        ok -> if (it.sms == SmsState.FAILED) it else it.copy(sms = SmsState.SENT)
                        else -> it.copy(sms = SmsState.FAILED, error = "SMS לא נשלח (קוד $resultCode)")
                    }
                }
            }
            KIND_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu")
                val format = intent.getStringExtra("format")
                val status = pdu?.let { runCatching { SmsMessage.createFromPdu(it, format).status }.getOrNull() }
                // GSM: 0x00–0x1F = delivered. Unknown/absent status: trust the report.
                val delivered = status == null || status in 0..0x1F
                if (delivered) Store.updateEntry(id) { it.copy(sms = SmsState.DELIVERED) }
            }
        }
    }
}
