package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ahmedkhalaf.athan.databinding.ActivityReminderBinding

/**
 * The heads-up popup: "Be ready for {prayer} in {N} minutes", with one Close
 * button. Shows over the lock screen so it is readable without unlocking.
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderBinding

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Stands down when the athan itself begins. Deliberately not tied to the
     * reminder *sound* ending: the tone lasts a couple of seconds, and a popup
     * that vanished with it would be gone long before you looked up. It stays
     * until you close it or the prayer arrives, which is the whole point of a
     * "be ready" warning.
     */
    private val athanStarted = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    /**
     * Backstop for the case where the athan never announces itself — a denied
     * foreground-service start throws, and the popup would otherwise sit there
     * until the user found it.
     */
    private val expire = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        render(intent)
        binding.closeButton.setOnClickListener { dismiss() }

        ContextCompat.registerReceiver(
            this, athanStarted, IntentFilter(AthanService.ACTION_STARTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scheduleExpiry()
    }

    /**
     * Closes shortly after the prayer time itself, in case the athan never
     * arrives to close it. A minute of grace, so it never races the athan and
     * steals the moment the popup exists to announce.
     */
    private fun scheduleExpiry() {
        handler.removeCallbacks(expire)
        val next = PrayerEngine(Prefs(this)).next() ?: return
        val delay = next.time.time - System.currentTimeMillis() + GRACE_MS
        handler.postDelayed(expire, delay.coerceIn(GRACE_MS, MAX_ALIVE_MS))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val slot = runCatching {
            Slot.valueOf(intent.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull()
        val name = slot?.let { getString(it.labelRes) } ?: getString(R.string.app_name)
        binding.reminderText.text =
            getString(R.string.reminder_popup, name, Prefs(this).reminderMinutes)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun dismiss() {
        startService(Intent(this, ReminderService::class.java).setAction(ReminderService.ACTION_STOP))
        finish()
    }

    override fun onBackPressed() = dismiss()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(expire)
        runCatching { unregisterReceiver(athanStarted) }
    }

    private companion object {
        const val GRACE_MS = 60_000L
        /** Never linger longer than this, whatever the clock says. */
        const val MAX_ALIVE_MS = 30 * 60 * 1000L
    }
}
