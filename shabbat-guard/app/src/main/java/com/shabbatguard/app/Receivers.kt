package com.shabbatguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when Shabbat / Yom Tov begins. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.reschedule(context)
    }
}

/** Re-arms after reboot, app update, or clock / time-zone change. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs(context).addLog("אירוע מערכת: ${intent.action?.substringAfterLast('.')}")
        Scheduler.reschedule(context)
    }
}
