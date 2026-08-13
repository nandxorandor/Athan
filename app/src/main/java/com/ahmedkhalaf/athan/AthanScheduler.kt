package com.ahmedkhalaf.athan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules exactly one alarm — the next prayer — and re-arms after each firing.
 * Scheduling all five at once would go stale the moment the user travels or the
 * date rolls, and Android caps how many exact alarms an app may hold anyway.
 */
object AthanScheduler {

    const val EXTRA_SLOT = "slot"
    private const val TAG = "AthanScheduler"
    private const val REQUEST_CODE = 1001
    private const val REMINDER_REQUEST_CODE = 1002

    /**
     * [guardMs] skips prayers within that window of now. AlarmManager may fire
     * an alarm a few milliseconds early, so re-arming straight after a firing
     * can re-select the prayer that just went off; that time is then already in
     * the past, and AlarmManager delivers past-due alarms immediately, looping
     * the athan. The receiver passes a guard; the UI does not need one.
     */
    fun scheduleNext(context: Context, guardMs: Long = 0L) {
        val prefs = Prefs(context)
        val from = java.util.Date(System.currentTimeMillis() + guardMs)
        val upcoming = PrayerEngine(prefs).next(from) ?: run {
            Log.i(TAG, "no location yet — nothing to schedule")
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // The user revoked exact alarms. An inexact athan is worse than none,
            // so surface it in the UI rather than silently drifting.
            Log.w(TAG, "exact alarms not permitted")
            return
        }

        val pending = pendingIntent(context, upcoming.slot)
        // setAlarmClock, not setExactAndAllowWhileIdle: it is the only tier that
        // Doze never defers, and it shows the system alarm icon so the user can
        // see an athan is armed.
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(upcoming.time.time, showIntent(context)),
            pending
        )
        Log.i(TAG, "next: ${upcoming.slot} at ${upcoming.time}")

        scheduleReminder(context, alarmManager, prefs, upcoming)
    }

    /**
     * The optional "get ready" heads-up, [reminderMinutes] before the athan. Its
     * own alarm, distinct request code, so it and the athan never overwrite each
     * other. Only armed if still in the future — within the window there is no
     * point reminding you about a prayer that is seconds away.
     */
    private fun scheduleReminder(
        context: Context,
        alarmManager: AlarmManager,
        prefs: Prefs,
        upcoming: Upcoming,
    ) {
        val reminder = reminderPendingIntent(context, upcoming.slot)
        alarmManager.cancel(reminder)
        if (!prefs.reminderEnabled) return

        val at = upcoming.time.time - prefs.reminderMinutes * 60_000L
        if (at <= System.currentTimeMillis()) return

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
        Log.i(TAG, "reminder for ${upcoming.slot} at ${java.util.Date(at)}")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        Slot.entries.forEach {
            alarmManager.cancel(pendingIntent(context, it))
            alarmManager.cancel(reminderPendingIntent(context, it))
        }
    }

    private fun pendingIntent(context: Context, slot: Slot): PendingIntent {
        val intent = Intent(context, AthanReceiver::class.java)
            .putExtra(EXTRA_SLOT, slot.name)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun reminderPendingIntent(context: Context, slot: Slot): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_SLOT, slot.name)
        return PendingIntent.getBroadcast(
            context, REMINDER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
