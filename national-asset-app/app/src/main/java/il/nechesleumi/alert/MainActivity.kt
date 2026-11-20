package il.nechesleumi.alert

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

/** A trigger SMS arrived and the user has to confirm starting the event. */
data class TriggerPrompt(val mode: Mode?)

class MainActivity : ComponentActivity() {
    private val trigger = mutableStateOf<TriggerPrompt?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        handle(intent)
        setContent { AppRoot(trigger) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null || !i.hasExtra(Notifications.EXTRA_MODE)) return
        if (i.getBooleanExtra(Notifications.EXTRA_TRIGGER, false) && Store.event.value?.active != true) {
            trigger.value = TriggerPrompt(i.getStringExtra(Notifications.EXTRA_MODE)?.let { Mode.valueOf(it) })
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }
        i.removeExtra(Notifications.EXTRA_TRIGGER)
        i.removeExtra(Notifications.EXTRA_MODE)
    }
}
