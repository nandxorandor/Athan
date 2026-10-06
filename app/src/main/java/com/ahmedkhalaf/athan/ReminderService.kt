package com.ahmedkhalaf.athan

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Delivers the pre-prayer heads-up: a short ringtone, a brief vibration, and a
 * popup. Lighter than the athan — the sound plays once and stops — but it uses
 * the same foreground-service + full-screen-intent pattern so it is reliable
 * when the screen is off. Auto-stops when the sound ends or after a safety
 * timeout, and closes its popup on the way out.
 */
class ReminderService : Service() {
    // Notifications this service posts are user-visible text, so it needs the
    // chosen language too — a service context does not inherit an activity's.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }
    private val silenceRunnable = Runnable { releasePlayer() }
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val slot = runCatching {
            Slot.valueOf(intent?.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        if (active) return START_NOT_STICKY
        active = true

        startForeground(NOTIFICATION_ID, buildNotification(slot))
        val prefs = Prefs(this)
        // Exactly one of the two, never both.
        if (prefs.reminderVibrate) vibrate() else playSound(prefs)

        // How long the heads-up stays on screen. Android decides how long the
        // floating banner itself hovers (a few seconds, not ours to set), but
        // the notification below it lives exactly this long, so the reminder is
        // still there to be found and dismissed a couple of minutes later.
        handler.postDelayed(stopRunnable, VISIBLE_MS)
        // And the tone never outlives that, however long the file is.
        handler.postDelayed(silenceRunnable, MAX_SOUND_MS)
        return START_NOT_STICKY
    }

    /** Stops the tone without ending the service or hiding the notification. */
    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    private fun playSound(prefs: Prefs) {
        // The user's ringtone, or the system default notification tone.
        val uri = prefs.reminderSound.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI ?: return
        try {
            player = MediaPlayer().apply {
                // USAGE_ALARM, exactly as the athan uses. NOTIFICATION_EVENT
                // routes to the notification stream, so a lowered ringer or any
                // Do Not Disturb profile silenced the heads-up completely while
                // the athan itself still played - the tone was chosen, stored
                // and shown in settings, and simply never heard.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderService, uri)
                // The heads-up's own slider, not the athan's.
                val v = prefs.reminderVolume / 100f
                setVolume(v, v)
                // Release the player, but leave the service - and so the
                // notification - alive. Ending the service here was why the
                // heads-up vanished a few seconds after it appeared: the tone
                // is short, and it took the notification down with it.
                setOnCompletionListener { releasePlayer() }
                setOnErrorListener { _, _, _ -> releasePlayer(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "reminder sound failed", e)
        }
    }

    private fun vibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        // Two short buzzes, ~1.6 s total — well under the 5 s ceiling, no repeat.
        val pattern = longArrayOf(0, 500, 300, 500)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun buildNotification(slot: Slot): Notification {
        val full = PendingIntent.getActivity(
            this, slot.ordinal + 100,
            Intent(this, ReminderActivity::class.java)
                .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ReminderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val visible = getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

        val minutes = Prefs(this).reminderMinutes
        val builder = NotificationCompat.Builder(
            this,
            if (visible) AthanApp.CHANNEL_REMINDER else AthanApp.CHANNEL_ATHAN
        )
            .setSmallIcon(R.drawable.ic_athan)
            .setContentTitle(getString(R.string.reminder_title, getString(slot.labelRes)))
            .setContentText(getString(R.string.reminder_body, getString(slot.labelRes), minutes))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            // Not ongoing: a heads-up is information, not something to be
            // trapped by. Swipe dismisses it, Close dismisses it, and it clears
            // itself after VISIBLE_MS if simply left alone.
            .setOngoing(false)
            .setContentIntent(full)
            .addAction(0, getString(R.string.close), stop)
            // Swiping it away must also end the service, or it would sit there
            // for the rest of the minute with nothing on screen.
            .setDeleteIntent(stop)
            // Clears itself even if the service is killed before its timer runs.
            .setTimeoutAfter(VISIBLE_MS)

        // Unlocked: a banner at the top of whatever the user is doing, tappable
        // to open the full window. It used to go out on the quiet channel, which
        // by design can never banner - so an unlocked phone played the tone and
        // showed nothing at all. Locked or screen-off: the full-screen intent,
        // which is the only thing that gets a window up over the keyguard.
        return if (visible) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH).build()
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(full, true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        active = false
        handler.removeCallbacks(stopRunnable)
        handler.removeCallbacks(silenceRunnable)
        releasePlayer()
        vibrator?.cancel()
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STOP = "com.ahmedkhalaf.athan.REMINDER_STOP"
        const val ACTION_FINISHED = "com.ahmedkhalaf.athan.REMINDER_FINISHED"
        private const val TAG = "ReminderService"
        private const val NOTIFICATION_ID = 43
        /**
         * How long the heads-up notification stays up before clearing itself.
         * Never shorter than MAX_SOUND_MS: ending the service ends the tone.
         */
        private const val VISIBLE_MS = 120_000L

        /**
         * The longest a tone may play. Generous on purpose - users pick their own
         * recordings, and a 15 s cap cut them off mid-sentence. Close or a swipe
         * still stops it at any time.
         */
        private const val MAX_SOUND_MS = 120_000L
    }
}
