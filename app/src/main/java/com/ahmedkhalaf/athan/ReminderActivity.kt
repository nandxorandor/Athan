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

    /** Closes the popup if the reminder ends on its own (sound finished / timeout). */
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        render(intent)
        binding.closeButton.setOnClickListener { dismiss() }

        ContextCompat.registerReceiver(
            this, finished, IntentFilter(ReminderService.ACTION_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        runCatching { unregisterReceiver(finished) }
    }
}
