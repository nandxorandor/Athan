package com.ahmedkhalaf.athan

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Plays the athan. A foreground service rather than playback from the receiver:
 * a BroadcastReceiver is killed within seconds, and the athan runs for minutes.
 */
class AthanService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Logged so a short athan can be told apart from a bug: this line
            // means someone pressed Stop, its absence means we ended on our own.
            Log.i(TAG, "stop requested by user")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_VOLUME) {
            val volume = intent.getIntExtra(EXTRA_VOLUME, Prefs(this).volume).coerceIn(0, 100)
            Prefs(this).volume = volume
            player?.setVolume(volume / 100f, volume / 100f)
            Log.i(TAG, "volume=$volume")
            return START_NOT_STICKY
        }

        val slot = runCatching {
            Slot.valueOf(intent?.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        // A repeat start for an athan already running must not stack a second
        // MediaPlayer or restart the vibration timer.
        if (active) {
            Log.i(TAG, "already announcing; ignoring duplicate start for $slot")
            return START_NOT_STICKY
        }
        active = true
        Log.i(TAG, "start $slot mode=${Prefs(this).modeFor(slot)}")
        startForeground(NOTIFICATION_ID, buildNotification(slot))

        // The heads-up popup, if one is still on screen, stands down here: the
        // thing it was warning about has arrived, and two prayer windows at once
        // would leave the user closing the wrong one.
        sendBroadcast(Intent(ACTION_STARTED).setPackage(packageName))

        // The device may be dozing; hold the CPU just long enough to get audio
        // running. Released in onDestroy.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "athan:playback")
            .apply { acquire(TIMEOUT_MS) }

        when (Prefs(this).modeFor(slot)) {
            AthanMode.SOUND -> playAthan(slot)
            AthanMode.VIBRATE -> vibrate()
            AthanMode.SILENT -> Unit // the notification alone is the announcement
        }
        return START_NOT_STICKY
    }

    private fun playAthan(slot: Slot) {
        val prefs = Prefs(this)
        val catalog = AthanCatalog(this)
        val asset = (if (slot == Slot.FAJR) catalog.resolveFajr(prefs) else catalog.resolveGeneral(prefs))
            ?: run {
                Log.e(TAG, "no recordings bundled")
                stopSelf()
                return
            }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM so the athan is audible even when the ringer
                        // is down — that is the entire point of an athan alarm.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                // Bundled asset or a phone file / ringtone the user chose.
                SoundSource.setDataSource(this@AthanService, this, asset)
                // App volume: a scalar on this player only, independent of the
                // phone's system volume.
                val v = prefs.volume / 100f
                setVolume(v, v)
                setOnCompletionListener {
                    Log.i(TAG, "playback finished")
                    stopSelf()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "playback error what=$what extra=$extra")
                    stopSelf()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not play $asset", e)
            stopSelf()
        }
    }

    private fun vibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        // Repeat from index 0 so the pattern loops rather than running once;
        // the timeout below is what ends it, mirroring how long an athan runs.
        val pattern = longArrayOf(0, 700, 400, 700, 400, 700, 1500)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build()
        )
        handler.postDelayed(stopRunnable, VIBRATE_MS)
    }

    private fun buildNotification(slot: Slot): Notification {
        val full = PendingIntent.getActivity(
            this, slot.ordinal,
            Intent(this, AthanActivity::class.java)
                .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // With the screen on and unlocked the receiver has already opened the
        // athan window, so the banner would be a duplicate of what you are
        // looking at: post quietly instead. Locked or screen-off is the case
        // that genuinely needs the full-screen intent to wake the window.
        val visible = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

        // The notification is the one surface Android always delivers. The
        // window is launched separately and its start can be silently denied
        // (a backgrounded receiver on Android 14+), so Stop must live here too
        // or a vibrate-only athan leaves nothing to stop it. Both Stops end the
        // same service, and onDestroy closes the window, so they stay in sync.
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AthanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            this,
            if (visible) AthanApp.CHANNEL_ATHAN_QUIET else AthanApp.CHANNEL_ATHAN
        )
            .setSmallIcon(R.drawable.ic_athan)
            .setContentTitle(getString(R.string.athan_title, getString(slot.labelRes)))
            .setContentText(getString(R.string.athan_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .addAction(R.drawable.ic_athan, getString(R.string.stop), stop)

        return if (visible) {
            builder.setPriority(NotificationCompat.PRIORITY_LOW).build()
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(full, true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "stop")
        active = false
        handler.removeCallbacks(stopRunnable)
        player?.runCatching { stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        // Tell the popup to close itself; otherwise it would sit on screen after
        // the athan has finished, with nothing left to stop.
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    companion object {
        const val ACTION_STOP = "com.ahmedkhalaf.athan.STOP"
        const val ACTION_SET_VOLUME = "com.ahmedkhalaf.athan.SET_VOLUME"
        const val EXTRA_VOLUME = "volume"
        const val ACTION_FINISHED = "com.ahmedkhalaf.athan.FINISHED"
        const val ACTION_STARTED = "com.ahmedkhalaf.athan.STARTED"
        private const val TAG = "AthanService"
        private const val NOTIFICATION_ID = 42
        private const val TIMEOUT_MS = 10 * 60 * 1000L
        private const val VIBRATE_MS = 2 * 60 * 1000L
    }
}
