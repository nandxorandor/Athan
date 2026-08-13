package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms do not survive a reboot, an app update, or a timezone change. Without
 * this the app would go quiet after the next restart and give no sign why.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AthanScheduler.scheduleNext(context)
    }
}
