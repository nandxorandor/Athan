package com.ahmedkhalaf.athan

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything on the phone that can stop an athan or a heads-up arriving, in
 * one list, so a user can see that all is ready before waiting for a prayer
 * to find out. Every one of these fails silently otherwise: the alarm simply
 * never fires, or fires to a muted stream, or behind the lock screen.
 *
 * Shared by the readiness screen and the summary on its Settings row, so the
 * two cannot disagree about how many things need attention.
 */
object Readiness {

    enum class Status { OK, ADVICE, PROBLEM }

    data class Check(
        val title: Int,
        val status: Status,
        /** Shown under the title: why it matters, or what is wrong. */
        val detail: Int,
        /** Where tapping the row takes the user to fix it; null when fine. */
        val fix: ((Context) -> Unit)?,
    )

    fun checks(context: Context): List<Check> = listOfNotNull(
        location(context),
        notifications(context),
        exactAlarms(context),
        fullScreen(context),
        backgroundRestricted(context),
        alarmVolume(context),
        batteryOptimisation(context),
    )

    /** Things the user must fix; advice does not count. */
    fun problems(context: Context): Int = checks(context).count { it.status == Status.PROBLEM }

    private fun location(context: Context): Check {
        val ok = Prefs(context).hasLocation
        return Check(
            R.string.check_location,
            if (ok) Status.OK else Status.PROBLEM,
            if (ok) R.string.check_location_ok else R.string.check_location_bad,
            if (ok) null else { c ->
                // CLEAR_TOP alone recreates the home screen, whose first act
                // with no location is to ask for one.
                c.startActivity(
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            },
        )
    }

    /**
     * The app as a whole, and the two channels that matter - a user can leave
     * notifications on and still switch off just the athan's or the heads-up's.
     */
    private fun notifications(context: Context): Check {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channelsOn = listOf(AthanApp.CHANNEL_ATHAN, AthanApp.CHANNEL_REMINDER).all { id ->
            nm.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        val ok = nm.areNotificationsEnabled() && channelsOn
        return Check(
            R.string.check_notifications,
            if (ok) Status.OK else Status.PROBLEM,
            if (ok) R.string.check_notifications_ok else R.string.check_notifications_bad,
            if (ok) null else { c ->
                c.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                )
            },
        )
    }

    private fun exactAlarms(context: Context): Check? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val ok = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        return Check(
            R.string.check_exact,
            if (ok) Status.OK else Status.PROBLEM,
            if (ok) R.string.check_exact_ok else R.string.check_exact_bad,
            if (ok) null else { c ->
                c.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(c))
                )
            },
        )
    }

    /** Android 14 lets this be revoked; without it nothing shows over the lock screen. */
    private fun fullScreen(context: Context): Check? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val ok = context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        return Check(
            R.string.check_fullscreen,
            if (ok) Status.OK else Status.PROBLEM,
            if (ok) R.string.check_fullscreen_ok else R.string.check_fullscreen_bad,
            if (ok) null else { c ->
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(c))
                )
            },
        )
    }

    /** "Restricted" battery use: the one battery setting that does stop alarms. */
    private fun backgroundRestricted(context: Context): Check? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        return Check(
            R.string.check_background,
            if (restricted) Status.PROBLEM else Status.OK,
            if (restricted) R.string.check_background_bad else R.string.check_background_ok,
            if (restricted) ::openAppDetails else null,
        )
    }

    /** The athan and the heads-up both play on the alarm stream. */
    private fun alarmVolume(context: Context): Check {
        val audio = context.getSystemService(AudioManager::class.java)
        val ok = audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
        return Check(
            R.string.check_volume,
            if (ok) Status.OK else Status.PROBLEM,
            if (ok) R.string.check_volume_ok else R.string.check_volume_bad,
            if (ok) null else { c ->
                // Raises it a step and shows the system slider, which is the
                // only way an app can hand over the alarm volume.
                c.getSystemService(AudioManager::class.java).adjustStreamVolume(
                    AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
            },
        )
    }

    /**
     * Advice, not a problem: the athan uses the one alarm type that battery
     * optimisation does not delay, so on most phones this is fine as it is.
     * Some makers' own power savers close apps regardless, and for those
     * "Unrestricted" is the fix - so it is offered, and only offered.
     */
    private fun batteryOptimisation(context: Context): Check {
        val exempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        return Check(
            R.string.check_battery,
            if (exempt) Status.OK else Status.ADVICE,
            if (exempt) R.string.check_battery_ok else R.string.check_battery_advice,
            if (exempt) null else ::openAppDetails,
        )
    }

    private fun openAppDetails(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
