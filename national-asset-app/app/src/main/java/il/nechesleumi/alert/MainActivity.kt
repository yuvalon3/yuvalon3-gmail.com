package il.nechesleumi.alert

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

/** Ask the user to confirm starting an event (from a trigger SMS or a home-screen shortcut). */
data class TriggerPrompt(val mode: Mode?, val fromTrigger: Boolean)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_SHORTCUT_START = "il.nechesleumi.alert.START"
    }

    private val trigger = mutableStateOf<TriggerPrompt?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        if (savedInstanceState == null) handle(intent)
        setContent { AppRoot(trigger) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val mode = i.getStringExtra(Notifications.EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        val fromShortcut = i.action == ACTION_SHORTCUT_START
        val fromTrigger = i.getBooleanExtra(Notifications.EXTRA_TRIGGER, false)
        if ((fromShortcut || fromTrigger) && Store.event.value?.active != true) {
            trigger.value = TriggerPrompt(mode, fromTrigger)
            if (fromTrigger && Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }
        i.removeExtra(Notifications.EXTRA_TRIGGER)
        i.removeExtra(Notifications.EXTRA_MODE)
        if (fromShortcut) i.action = Intent.ACTION_MAIN
    }
}
