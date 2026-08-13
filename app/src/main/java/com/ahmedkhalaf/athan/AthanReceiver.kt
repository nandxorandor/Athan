package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Fired by AlarmManager at the prayer time. */
class AthanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slot = runCatching {
            Slot.valueOf(intent.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull()

        if (slot != null) {
            // A denied foreground-service start throws, and an uncaught throw in
            // a receiver crashes the app. Android normally grants a 10s
            // allowlist for an alarm-clock alarm, but if that window is ever
            // missed the athan should degrade to just the popup, not a crash.
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AthanService::class.java)
                        .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
                )
            }.onFailure { Log.e(TAG, "could not start athan service", it) }
            // Started from here rather than from the service: receiving an exact
            // alarm grants this app a brief background-activity-start window, so
            // the popup appears even with the screen on and unlocked. The
            // notification's full-screen intent still covers the locked case.
            runCatching {
                context.startActivity(
                    Intent(context, AthanActivity::class.java)
                        .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // Re-arm immediately. If this is missed the chain of alarms stops dead
        // and the app silently never fires again. The guard prevents re-picking
        // the prayer that just fired — see AthanScheduler.scheduleNext.
        AthanScheduler.scheduleNext(context, guardMs = REARM_GUARD_MS)
    }

    private companion object {
        const val TAG = "AthanReceiver"

        /** Far beyond alarm jitter, far below the gap between any two prayers. */
        const val REARM_GUARD_MS = 60_000L
    }
}
