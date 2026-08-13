package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fired by AlarmManager [reminderMinutes] before a prayer. Shows the "get ready"
 * heads-up. It does not re-arm anything: the athan's own alarm is already set,
 * and when that fires the whole chain (next prayer + its reminder) re-arms.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slot = runCatching {
            Slot.valueOf(intent.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull() ?: return

        if (!Prefs(context).reminderEnabled) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReminderService::class.java)
                    .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
            )
        }.onFailure { Log.e(TAG, "could not start reminder service", it) }

        // Alarm delivery grants a brief background-activity-start window, so the
        // popup shows even with the screen on. The full-screen intent on the
        // service's notification covers the locked / screen-off case.
        runCatching {
            context.startActivity(
                Intent(context, ReminderActivity::class.java)
                    .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        const val TAG = "ReminderReceiver"
    }
}
