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

        // Safety net: never let the reminder sound run forever, even if the
        // recording is long or completion never fires.
        handler.postDelayed(stopRunnable, MAX_MS)
        return START_NOT_STICKY
    }

    private fun playSound(prefs: Prefs) {
        // The user's ringtone, or the system default notification tone.
        val uri = prefs.reminderSound.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderService, uri)
                val v = prefs.volume / 100f
                setVolume(v, v)
                setOnCompletionListener { stopSelf() }
                setOnErrorListener { _, _, _ -> stopSelf(); true }
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
            if (visible) AthanApp.CHANNEL_ATHAN_QUIET else AthanApp.CHANNEL_ATHAN
        )
            .setSmallIcon(R.drawable.ic_athan)
            .setContentTitle(getString(R.string.reminder_title, getString(slot.labelRes)))
            .setContentText(getString(R.string.reminder_body, getString(slot.labelRes), minutes))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(full)
            .addAction(0, getString(R.string.close), stop)

        return if (visible) {
            builder.setPriority(NotificationCompat.PRIORITY_LOW).build()
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
        player?.runCatching { stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STOP = "com.ahmedkhalaf.athan.REMINDER_STOP"
        const val ACTION_FINISHED = "com.ahmedkhalaf.athan.REMINDER_FINISHED"
        private const val TAG = "ReminderService"
        private const val NOTIFICATION_ID = 43
        private const val MAX_MS = 60_000L
    }
}
