package com.shabbatguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Runs for the whole Shabbat / Yom Tov: watches the accelerometer and switches the
 * ringer between ring (phone lying still) and vibrate (phone being carried).
 */
class GuardService : Service(), SensorEventListener {

    data class Live(
        val mode: RingMode,
        val level: Float,
        val tilt: Float,
        val movingRun: Int,
        val stillForSec: Long,
        val pendingInSec: Long?,
        val endAt: Long,
        val label: String,
        val proximityNear: Boolean,
    )

    companion object {
        const val ACTION_WINDOW = "com.shabbatguard.app.WINDOW"
        const val ACTION_TEST = "com.shabbatguard.app.TEST"
        const val ACTION_STOP = "com.shabbatguard.app.STOP"
        private const val EXTRA_END = "end"
        private const val EXTRA_LABEL = "label"
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 1
        private const val SAMPLE_US = 50_000 // 20 Hz

        @Volatile var live: Live? = null
            private set

        fun startWindow(ctx: Context, endAt: Long, label: String) {
            ctx.startForegroundService(
                Intent(ctx, GuardService::class.java).setAction(ACTION_WINDOW)
                    .putExtra(EXTRA_END, endAt).putExtra(EXTRA_LABEL, label),
            )
        }

        fun startTest(ctx: Context, minutes: Int) {
            ctx.startForegroundService(
                Intent(ctx, GuardService::class.java).setAction(ACTION_TEST)
                    .putExtra(EXTRA_END, System.currentTimeMillis() + minutes * 60_000L)
                    .putExtra(EXTRA_LABEL, "בדיקה"),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP))
        }

        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var logic: MotionLogic? = null
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var endAt = 0L
    private var label = ""
    private var isTest = false
    private var proximityNear = false
    private var proximityMax = 5f

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (logic != null) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        createChannel()
        // Must call startForeground promptly after startForegroundService().
        goForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                if (!isTest && endAt > System.currentTimeMillis()) prefs.skipUntil = endAt
                finish("נעצר ידנית")
                return START_NOT_STICKY
            }
            ACTION_WINDOW -> {
                endAt = intent.getLongExtra(EXTRA_END, 0)
                label = intent.getStringExtra(EXTRA_LABEL) ?: "שבת"
                isTest = false
            }
            ACTION_TEST -> {
                // A test never shortens a real Shabbat that is already running.
                if (logic == null || isTest) {
                    endAt = intent.getLongExtra(EXTRA_END, 0)
                    label = intent.getStringExtra(EXTRA_LABEL) ?: "בדיקה"
                    isTest = true
                }
            }
            null -> {
                // Restarted by the system after being killed.
                endAt = prefs.activeUntil
                label = prefs.activeLabel
                isTest = false
                prefs.addLog("השירות הופעל מחדש ע\"י המערכת")
            }
        }

        if (endAt <= System.currentTimeMillis()) {
            finish("אין חלון פעיל")
            return START_NOT_STICKY
        }
        prefs.activeUntil = endAt
        prefs.activeLabel = label
        if (logic == null) begin() else updateNotification()
        return START_STICKY
    }

    private fun begin() {
        if (prefs.savedRingerMode == -1) prefs.savedRingerMode = Ringer.current(this)
        val now = System.currentTimeMillis()
        logic = MotionLogic(prefs.motionConfig(), RingMode.RING, now)
        prefs.addLog("▶ התחיל: $label (עד ${fmt(endAt)}), מצב קודם: ${Ringer.name(prefs.savedRingerMode)}")
        applyMode(RingMode.RING, "התחלה")

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShabbatGuard:motion").apply {
            setReferenceCounted(false)
            acquire((endAt - now + 10 * 60_000L).coerceAtLeast(60_000L))
        }

        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            prefs.addLog("⚠ אין חיישן תאוצה במכשיר")
        } else {
            sm.registerListener(this, accel, SAMPLE_US)
        }
        if (prefs.useProximity) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                proximityMax = it.maximumRange
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        handler.post(tick)
    }

    private fun onTick() {
        val l = logic ?: return
        val now = System.currentTimeMillis()
        if (now >= endAt) {
            finish(if (isTest) "הבדיקה הסתיימה" else "$label יצא")
            return
        }
        val changed = l.onTick(now, prefs.useProximity && proximityNear)
        if (changed != null) {
            val why = if (changed == RingMode.VIBRATE) "זוהתה הליכה" else
                "הטלפון מונח ${prefs.stillMinutes} דק' ללא תזוזה"
            applyMode(changed, why)
        }
        live = Live(
            mode = l.mode,
            level = l.lastSecondLevel,
            tilt = l.lastSecondTilt,
            movingRun = l.movingRun,
            stillForSec = l.stillForMs(now) / 1000,
            pendingInSec = l.pendingVibrateAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) },
            endAt = endAt,
            label = label,
            proximityNear = proximityNear,
        )
    }

    private fun applyMode(mode: RingMode, reason: String) {
        val err = Ringer.set(this, mode)
        prefs.addLog(if (err == null) "→ ${Ringer.name(mode)} ($reason)" else "⚠ $err")
        updateNotification()
    }

    private fun finish(reason: String) {
        handler.removeCallbacks(tick)
        sensorManager?.unregisterListener(this)
        sensorManager = null
        val wasRunning = logic != null
        logic = null
        live = null
        val saved = prefs.savedRingerMode
        if (saved != -1) {
            Ringer.setRaw(this, saved)
            prefs.savedRingerMode = -1
        }
        if (wasRunning) prefs.addLog("■ $reason, הוחזר מצב: ${Ringer.name(saved)}")
        prefs.activeUntil = 0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Scheduler.reschedule(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        live = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> logic?.onAccel(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_PROXIMITY -> proximityNear = event.values[0] < proximityMax.coerceAtMost(5f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "שומר שבת פעיל", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val mode = logic?.mode?.let { Ringer.name(it) } ?: "מתחיל…"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("שומר שבת פעיל — $label")
            .setContentText("מצב: $mode · עד ${fmt(endAt)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (logic == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun fmt(ms: Long): String =
        if (ms <= 0) "—" else TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
