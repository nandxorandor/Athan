package com.ahmedkhalaf.athan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class AthanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    companion object {
        const val CHANNEL_ATHAN = "athan"
        const val CHANNEL_ATHAN_QUIET = "athan_quiet"
        const val CHANNEL_DOWNLOAD = "athan_download"
        const val CHANNEL_REMINDER = "athan_reminder"

        /**
         * Channel names and descriptions are user-visible text in the system's
         * own notification settings, so they follow the chosen language.
         * Re-creating a channel with the same id renames it, which is why this
         * is called again when the language changes rather than only at start.
         */
        fun createChannels(context: Context) {
            val ctx = AppLocale.wrap(context)
            val manager = ctx.getSystemService(NotificationManager::class.java)

            // No sound and no vibration on the channel itself: the service owns both,
            // so the mode setting stays in one place and the channel can't override it.
            // High importance, used only when the screen is off or locked: it carries
            // the full-screen intent that wakes the athan window.
            val alert = NotificationChannel(
                CHANNEL_ATHAN,
                ctx.getString(R.string.channel_athan),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_athan_desc)
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            // Low importance never produces a heads-up banner. Used when the athan
            // window is already on screen: a foreground service must post *some*
            // notification, but it should not duplicate a window you are looking at.
            val quiet = NotificationChannel(
                CHANNEL_ATHAN_QUIET,
                ctx.getString(R.string.channel_athan_quiet),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.channel_athan_quiet_desc)
                setSound(null, null)
                enableVibration(false)
            }

            // "Your athan finished downloading". High importance so it banners over
            // the browser the user is still standing in — that is the whole point of
            // it — but silent, because the browser has already made its own noise.
            val download = NotificationChannel(
                CHANNEL_DOWNLOAD,
                ctx.getString(R.string.channel_download),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_download_desc)
                setSound(null, null)
                enableVibration(false)
            }

            // The heads-up while the phone is unlocked and in use. High
            // importance so it banners at the top of the screen - that banner is
            // the whole point of a heads-up, and the quiet channel could not
            // produce one, which left an unlocked phone with sound and nothing
            // to look at. Silent, because ReminderService plays the tone itself.
            val reminder = NotificationChannel(
                CHANNEL_REMINDER,
                ctx.getString(R.string.channel_reminder),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.channel_reminder_desc)
                setSound(null, null)
                enableVibration(false)
            }

            manager.createNotificationChannels(listOf(alert, quiet, download, reminder))
        }
    }
}
