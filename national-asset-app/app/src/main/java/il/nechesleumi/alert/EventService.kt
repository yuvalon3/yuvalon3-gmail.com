package il.nechesleumi.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.CallLog
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs an event in the foreground: sends the SMS from the phone's SIM and calls the list
 * one by one from the phone itself, announcing "נכס לאומי" through the speaker.
 *
 * Whether someone answered is read from the call log once each call ends (connected time > 0).
 */
class EventService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_RETRY = "retry"
        const val ACTION_CALL_ONE = "call_one"
        const val ACTION_SMS_ONE = "sms_one"
        const val ACTION_STOP = "stop"
        const val ACTION_ANSWERED = "answered"   // user taps during a call
        const val ACTION_NO_ANSWER = "no_answer" // user taps during a call
        const val ACTION_PAUSE_CALLS = "pause_calls"
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
    private val piCounter = AtomicInteger((System.currentTimeMillis() % 1_000_000).toInt())
    private var notifJob: Job? = null

    // calling queue
    private val queue = LinkedBlockingDeque<String>()
    private val forced = ConcurrentHashMap.newKeySet<String>()
    private var callJob: Job? = null
    @Volatile private var decision: CallState? = null

    // text to speech
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("he", "IL")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> {
                val mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.DRILL.name)
                stopCalling()
                val ev = Store.startEvent(mode)
                val ids = ev.entries.map { it.contactId }
                val s = Store.settings.value
                if (s.sendSms) scope.launch { sendSms(ids) }
                if (s.makeCalls) enqueue(ids, front = false, autoRounds = true)
            }
            ACTION_RETRY -> {
                val ids = Store.event.value?.entries?.filter { !it.found }?.map { it.contactId }.orEmpty()
                enqueue(ids, front = false, autoRounds = false)
            }
            ACTION_CALL_ONE -> if (id != null) {
                forced.add(id)
                enqueue(listOf(id), front = true, autoRounds = false)
            }
            ACTION_SMS_ONE -> if (id != null) scope.launch { sendSms(listOf(id)) }
            ACTION_ANSWERED -> decision = CallState.ANSWERED
            ACTION_NO_ANSWER -> decision = CallState.NO_ANSWER
            ACTION_PAUSE_CALLS -> stopCalling()
            ACTION_STOP -> {
                stopCalling()
                Store.endEvent()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> if (Store.event.value?.active != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCalling()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    private fun actionIntent(action: String, req: Int): PendingIntent = PendingIntent.getForegroundService(
        this, req, Intent(this, EventService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(ev: EventData?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val current = Store.calling.value?.let { Store.entry(it) }
        val b = NotificationCompat.Builder(this, Notifications.CH_EVENT)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (ev == null) return b.setContentTitle("נכס לאומי").build()
        val found = "נמצאו ${ev.foundCount} מתוך ${ev.entries.size}"
        if (current != null) {
            b.setContentTitle("מתקשר ל${current.name}")
                .setContentText("נכס לאומי – ${ev.mode.label} · $found")
                .addAction(0, "ענה ✓", actionIntent(ACTION_ANSWERED, 11))
                .addAction(0, "לא ענה ✗", actionIntent(ACTION_NO_ANSWER, 12))
                .addAction(0, "עצור חיוגים", actionIntent(ACTION_PAUSE_CALLS, 13))
        } else {
            b.setContentTitle("נכס לאומי – ${ev.mode.label}")
                .setContentText(found)
                .setProgress(ev.entries.size.coerceAtLeast(1), ev.foundCount, false)
        }
        return b.build()
    }

    private fun refreshNotification() {
        val ev = Store.event.value ?: return
        if (ev.active) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ev))
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(Store.event.value), type)
        if (notifJob == null) {
            notifJob = scope.launch {
                launch { Store.event.collect { refreshNotification() } }
                launch { Store.calling.collect { refreshNotification() } }
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

    // ------------------------------------------------------------ calls (from this phone)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun eventOver() = Store.event.value?.active != true

    private fun stopCalling() {
        callJob?.cancel()
        callJob = null
        queue.clear()
        forced.clear()
        if (Store.calling.value != null) runCatching { endCall() }
        Store.calling.value = null
        Store.dialerRunning.value = false
        tts?.stop()
        // anything still waiting / interrupted goes back to idle
        Store.event.value?.entries
            ?.filter { it.call == CallState.WAITING || it.call == CallState.DIALING || it.call == CallState.IN_CALL }
            ?.forEach { e -> Store.updateEntry(e.contactId) { it.copy(call = CallState.IDLE) } }
        refreshNotification()
    }

    private fun enqueue(ids: List<String>, front: Boolean, autoRounds: Boolean) {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            ids.forEach { id -> Store.updateEntry(id) { it.copy(call = CallState.FAILED, error = "אין הרשאת חיוג") } }
            return
        }
        for (id in ids) {
            if (queue.contains(id) || Store.calling.value == id) continue
            if (front) queue.addFirst(id) else queue.addLast(id)
            Store.updateEntry(id) { it.copy(call = CallState.WAITING) }
        }
        if (callJob?.isActive != true) {
            callJob = scope.launch {
                Store.dialerRunning.value = true
                try { worker(autoRounds) } finally {
                    Store.dialerRunning.value = false
                    Store.calling.value = null
                    refreshNotification()
                }
            }
        }
    }

    private suspend fun worker(autoRounds: Boolean) {
        val s = Store.settings.value
        var round = 1
        while (true) {
            while (true) {
                val id = queue.pollFirst() ?: break
                if (eventOver()) return
                val e = Store.entry(id) ?: continue
                if (e.found && !forced.remove(id)) {
                    Store.updateEntry(id) { if (it.call == CallState.WAITING) it.copy(call = CallState.IDLE) else it }
                    continue
                }
                callOne(id)
                delay(2000) // breathing room between calls
            }
            if (!autoRounds || round >= s.maxAttempts.coerceIn(1, 10)) return
            round++

            // Wait before the next round, but stop if everybody was found / the event ended.
            val until = System.currentTimeMillis() + s.retryDelaySec.coerceAtLeast(5) * 1000L
            while (System.currentTimeMillis() < until) {
                if (eventOver() || Store.event.value?.entries?.all { it.found } == true) return
                delay(1000)
            }
            val next = Store.event.value?.entries
                ?.filter { !it.found && it.call != CallState.FAILED }
                ?.map { it.contactId }.orEmpty()
            if (next.isEmpty()) return
            next.forEach { id ->
                queue.addLast(id)
                Store.updateEntry(id) { it.copy(call = CallState.WAITING) }
            }
        }
    }

    private fun telecom() = getSystemService(TelecomManager::class.java)

    private fun inCall(): Boolean = try { telecom().isInCall } catch (e: SecurityException) { false }

    @Suppress("DEPRECATION")
    private fun endCall() {
        if (Build.VERSION.SDK_INT >= 28 && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            runCatching { telecom().endCall() }
        }
    }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (cond()) return true
            delay(250)
        }
        return cond()
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean) = runCatching {
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        }
        am.isSpeakerphoneOn = on
    }

    /** Connected seconds of the latest outgoing call to [phone] since [since]; null if unknown. */
    private fun callLogDuration(phone: String, since: Long): Int? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (since - 10_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (Phones.same(c.getString(0).orEmpty(), phone)) return@use c.getInt(1)
                }
                null
            }
        }.getOrNull()
    }

    private suspend fun callOne(id: String) {
        val s = Store.settings.value
        val e = Store.entry(id) ?: return
        val mode = Store.event.value?.mode ?: return

        // Don't dial over a call the user is already on.
        while (inCall()) {
            if (eventOver()) return
            delay(1000)
        }

        decision = null
        Store.calling.value = id
        Store.updateEntry(id) { it.copy(call = CallState.DIALING, attempts = it.attempts + 1, error = null) }

        val started = System.currentTimeMillis()
        try {
            telecom().placeCall(Uri.fromParts("tel", Phones.local(e.phone).ifEmpty { e.phone }, null), Bundle())
        } catch (ex: Exception) {
            Store.updateEntry(id) { it.copy(call = CallState.FAILED, error = "חיוג: ${ex.message ?: ex.javaClass.simpleName}") }
            Store.calling.value = null
            return
        }

        if (!waitFor(10_000) { inCall() }) {
            Store.updateEntry(id) { it.copy(call = CallState.FAILED, error = "השיחה לא יצאה") }
            Store.calling.value = null
            return
        }
        Store.updateEntry(id) { it.copy(call = CallState.IN_CALL) }
        if (s.announce) speaker(true)

        val text = s.callTextFor(mode)
        val windowMs = s.callWindowSec.coerceIn(10, 120) * 1000L
        var weEnded = false
        var lastSpoke = 0L
        while (inCall()) {
            val now = System.currentTimeMillis()
            if (decision != null || eventOver() || now - started > windowMs) {
                endCall()
                weEnded = true
                break
            }
            // Repeat the announcement through the speaker while the call is up.
            if (s.announce && ttsReady && now - started > 3000 && now - lastSpoke > 5000) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neches")
                lastSpoke = now
            }
            delay(300)
        }
        tts?.stop()
        waitFor(6000) { !inCall() }
        if (s.announce) speaker(false)

        // The call log entry is written right after the call ends.
        var duration: Int? = null
        for (i in 0 until 6) {
            delay(700)
            duration = callLogDuration(e.phone, started)
            if (duration != null) break
        }
        val outcome = CallLogic.outcome(duration, remoteHungUp = !weEnded, manual = decision)
        val now = System.currentTimeMillis()
        Store.updateEntry(id) {
            it.copy(call = outcome, answeredAt = if (outcome == CallState.ANSWERED) it.answeredAt ?: now else it.answeredAt)
        }
        decision = null
        Store.calling.value = null
    }
}
