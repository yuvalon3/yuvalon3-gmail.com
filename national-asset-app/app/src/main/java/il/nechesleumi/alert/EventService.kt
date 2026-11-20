package il.nechesleumi.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs an event in the foreground: sends the SMS from the phone's SIM, places the Twilio
 * announcement calls, polls each call until it's answered / not answered, and retries.
 */
class EventService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_RETRY = "retry"
        const val ACTION_CALL_ONE = "call_one"
        const val ACTION_SMS_ONE = "sms_one"
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "id"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, mode: Mode) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, EventService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODE, mode.name)
        )

        fun send(ctx: Context, action: String, contactId: String? = null) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, EventService::class.java).setAction(action).putExtra(EXTRA_ID, contactId)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callJobs = ConcurrentHashMap<String, Job>()
    private val dialGate = Mutex() // Twilio accepts ~1 new call per second by default
    private val piCounter = AtomicInteger((System.currentTimeMillis() % 1_000_000).toInt())
    private var notifJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> {
                val mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.DRILL.name)
                callJobs.values.forEach { it.cancel() }
                callJobs.clear()
                val ev = Store.startEvent(mode)
                val ids = ev.entries.map { it.contactId }
                val s = Store.settings.value
                if (s.sendSms) scope.launch { sendSms(ids) }
                if (s.makeCalls && s.twilioReady) callAll(ids, force = false)
            }
            ACTION_RETRY -> {
                val ids = Store.event.value?.entries?.filter { !it.found }?.map { it.contactId }.orEmpty()
                callAll(ids, force = false)
            }
            ACTION_CALL_ONE -> if (id != null) callAll(listOf(id), force = true)
            ACTION_SMS_ONE -> if (id != null) scope.launch { sendSms(listOf(id)) }
            ACTION_STOP -> {
                callJobs.values.forEach { it.cancel() }
                callJobs.clear()
                Store.endEvent()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> if (Store.event.value?.active != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    private fun buildNotification(ev: EventData?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (ev != null) "נכס לאומי – ${ev.mode.label}" else "נכס לאומי"
        val text = if (ev != null) "נמצאו ${ev.foundCount} מתוך ${ev.entries.size}" else "…"
        return NotificationCompat.Builder(this, Notifications.CH_EVENT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (ev != null) setProgress(ev.entries.size.coerceAtLeast(1), ev.foundCount, false) }
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Store.event.value), type)
        if (notifJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            notifJob = scope.launch {
                Store.event.collect { ev -> if (ev?.active == true) nm.notify(NOTIF_ID, buildNotification(ev)) }
            }
        }
    }

    // ------------------------------------------------------------ SMS

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun statusIntent(contactId: String, eventId: Long, kind: String): PendingIntent {
        val i = Intent(this, SmsStatusReceiver::class.java)
            .setAction(kind)
            .putExtra(SmsStatusReceiver.EXTRA_ID, contactId)
            .putExtra(SmsStatusReceiver.EXTRA_EVENT, eventId)
        // Mutable: the system adds the delivery-report PDU to the intent. Explicit intent, so allowed.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, piCounter.incrementAndGet(), i, flags)
    }

    private suspend fun sendSms(ids: List<String>) {
        val ev = Store.event.value ?: return
        val text = Store.settings.value.smsFor(ev.mode)
        val sm = try { smsManager() } catch (e: Exception) { null }
        for (id in ids) {
            val e = Store.entry(id) ?: continue
            if (sm == null) {
                Store.updateEntry(id) { it.copy(sms = SmsState.FAILED, error = "אין אפשרות לשלוח SMS במכשיר") }
                continue
            }
            try {
                val parts = sm.divideMessage(text)
                val sent = ArrayList(parts.map { statusIntent(id, ev.id, SmsStatusReceiver.KIND_SENT) })
                val delivered = ArrayList(parts.map { statusIntent(id, ev.id, SmsStatusReceiver.KIND_DELIVERED) })
                Store.updateEntry(id) { it.copy(sms = SmsState.SENDING) }
                sm.sendMultipartTextMessage(Phones.e164(e.phone), null, parts, sent, delivered)
            } catch (ex: Exception) {
                Store.updateEntry(id) { it.copy(sms = SmsState.FAILED, error = "SMS: ${ex.message ?: ex.javaClass.simpleName}") }
            }
            delay(400)
        }
    }

    // ------------------------------------------------------------ calls

    private fun callAll(ids: List<String>, force: Boolean) {
        val s = Store.settings.value
        if (!s.twilioReady) return
        for (id in ids) {
            if (callJobs[id]?.isActive == true) continue
            callJobs[id] = scope.launch { callLoop(id, force) }
        }
    }

    private fun eventOver() = Store.event.value?.active != true

    private suspend fun callLoop(id: String, force: Boolean) {
        val s = Store.settings.value
        val tw = Twilio(s.twilioSid, s.twilioToken)
        val mode = Store.event.value?.mode ?: return
        val twiml = CallLogic.twiml(s.callTextFor(mode), s.voice, s.callRepeat)
        val maxAttempts = s.maxAttempts.coerceIn(1, 10)
        var attempt = 0

        while (true) {
            val e = Store.entry(id) ?: return
            if (eventOver()) return
            if (e.found && !(force && attempt == 0)) return
            attempt++

            val sid = try {
                dialGate.withLock {
                    val r = tw.startCall(e.phone, s.twilioFrom, twiml, s.detectVoicemail)
                    delay(1100)
                    r
                }
            } catch (ex: Exception) {
                Store.updateEntry(id) {
                    it.copy(call = CallState.FAILED, attempts = it.attempts + 1, error = "שיחה: ${ex.message}")
                }
                return
            }
            Store.updateEntry(id) {
                it.copy(call = CallState.QUEUED, callSid = sid, attempts = it.attempts + 1, error = null)
            }

            var state = CallState.QUEUED
            val deadline = System.currentTimeMillis() + 3 * 60_000
            while (!state.terminal && System.currentTimeMillis() < deadline) {
                delay(2500)
                if (eventOver()) {
                    runCatching { tw.hangup(sid) }
                    return
                }
                val info = try { tw.fetchCall(sid) } catch (ex: Exception) { continue }
                state = CallLogic.map(info.status, info.answeredBy, info.duration, s.detectVoicemail)
                val now = System.currentTimeMillis()
                Store.updateEntry(id) {
                    it.copy(call = state, answeredAt = if (state == CallState.ANSWERED) it.answeredAt ?: now else it.answeredAt)
                }
            }

            if (state == CallState.ANSWERED || state == CallState.FAILED) return
            if (attempt >= maxAttempts) return

            // Wait before the next attempt, but stop early if they reply / are marked found.
            val until = System.currentTimeMillis() + s.retryDelaySec.coerceAtLeast(10) * 1000L
            while (System.currentTimeMillis() < until) {
                delay(1000)
                if (eventOver() || Store.entry(id)?.found == true) return
            }
        }
    }
}
