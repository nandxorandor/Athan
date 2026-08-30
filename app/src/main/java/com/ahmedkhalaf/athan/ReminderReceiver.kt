package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fired by AlarmManager [reminderMinutes] before a prayer. Shows the "get ready"
 * heads-up, then re-arms the chain.
 *
 * It used to re-arm nothing, on the reasoning that the athan's own alarm was
 * already set and would re-arm everything when it fired. That left the heads-up
 * hanging off a single link: only one reminder alarm exists at a time (one fixed
 * request code, FLAG_UPDATE_CURRENT), so any firing the athan missed - a denied
 * service start, a reboot between prayers, or simply enabling the feature, which
 * armed the next prayer and nothing beyond it - stopped the heads-up dead while
 * the athan kept working. Users saw it fire for exactly one prayer and never
 * again. Re-arming here costs nothing and makes the chain self-healing.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slot = runCatching {
            Slot.valueOf(intent.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull() ?: return

        if (!Prefs(context).reminderEnabled) {
            // Off now, but an alarm was already in flight. Re-arm anyway so the
            // athan chain is not left depending on this firing.
            AthanScheduler.scheduleNext(context)
            return
        }

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

        // Re-arm the whole chain. The guard skips anything within a minute of
        // now, which here means this same prayer's reminder: re-selecting it
        // would schedule a time already past, and AlarmManager delivers past-due
        // alarms immediately - the same loop the athan receiver guards against.
        AthanScheduler.scheduleNext(context, guardMs = REARM_GUARD_MS)
    }

    private companion object {
        const val TAG = "ReminderReceiver"

        /** Far beyond alarm jitter, far below the gap between any two prayers. */
        const val REARM_GUARD_MS = 60_000L
    }
}
