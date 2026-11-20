package il.nechesleumi.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifications.createChannels(this)
    }
}

object Notifications {
    const val CH_EVENT = "event"
    const val CH_TRIGGER = "trigger"
    const val EXTRA_TRIGGER = "trigger"
    const val EXTRA_MODE = "mode"
    private const val TRIGGER_ID = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENT, "אירוע פעיל", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIGGER, "הפעלת נכס לאומי", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "התראה כשמתקבלת הודעת \"הופעל נכס לאומי\""
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                setBypassDnd(true)
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun showTrigger(ctx: Context, mode: Mode?, body: String, started: Boolean) {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TRIGGER, !started)
            .putExtra(EXTRA_MODE, mode?.name)
        val pi = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = mode?.label.orEmpty()
        val title = if (started) "נכס לאומי $label – ההתראה לרשימה הופעלה" else "התקבלה הודעה: נכס לאומי $label"
        val text = if (started) "נשלחות הודעות ומתבצעות שיחות" else "לחצו כדי להפעיל את ההתראה לרשימה"
        val n = NotificationCompat.Builder(ctx, CH_TRIGGER)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$body"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(ctx).notify(TRIGGER_ID, n)
    }
}
