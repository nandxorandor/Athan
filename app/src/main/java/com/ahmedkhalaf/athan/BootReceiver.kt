package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms do not survive a reboot, an app update, a timezone change, or the
 * clock being set. Without this the app would go quiet and give no sign why.
 *
 * ACTION_TIME_CHANGED matters as much as the timezone one and was missing.
 * Alarms are armed at absolute timestamps, so moving the clock does not move
 * them: set the phone back an hour and the athan simply waits out that hour
 * again; set it forward past a prayer and that prayer is skipped entirely with
 * nothing rescheduled. Anyone changing the clock by hand, correcting a wrong
 * one, or landing after a flight saw the athan quietly stop.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AthanScheduler.scheduleNext(context)
    }
}
