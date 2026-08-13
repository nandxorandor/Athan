package com.ahmedkhalaf.athan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ahmedkhalaf.athan.databinding.ActivityAthanBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The announcement itself: prayer name, time, and one way to stop it. Shows
 * over the lock screen so it is readable without unlocking the phone.
 */
class AthanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAthanBinding
    private lateinit var prefs: Prefs

    /** Closes the popup when the athan ends on its own rather than by Stop. */
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        binding = ActivityAthanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        render(intent)
        binding.stopButton.setOnClickListener { dismiss() }
        binding.volumeSlider.progress = prefs.volume
        binding.volumeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(slider: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) setVolume(value)
            }
            override fun onStartTrackingTouch(slider: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(slider: android.widget.SeekBar) = Unit
        })

        ContextCompat.registerReceiver(
            this, finished, IntentFilter(AthanService.ACTION_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** singleInstance: a second prayer while this is up reuses the same task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val slot = runCatching {
            Slot.valueOf(intent.getStringExtra(AthanScheduler.EXTRA_SLOT) ?: "")
        }.getOrNull()

        binding.prayerName.text =
            slot?.let { getString(it.labelRes) } ?: getString(R.string.app_name)
        binding.prayerTime.text =
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val showVolume = slot?.let { prefs.modeFor(it) == AthanMode.SOUND } == true
        binding.volumeLabel.visibility = if (showVolume) android.view.View.VISIBLE else android.view.View.GONE
        binding.volumeSlider.visibility = if (showVolume) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setVolume(value: Int) {
        prefs.volume = value
        startService(
            Intent(this, AthanService::class.java)
                .setAction(AthanService.ACTION_SET_VOLUME)
                .putExtra(AthanService.EXTRA_VOLUME, value)
        )
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
        startService(Intent(this, AthanService::class.java).setAction(AthanService.ACTION_STOP))
        finish()
    }

    override fun onBackPressed() {
        // Back should silence the athan, not leave it playing behind the screen.
        dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(finished) }
    }
}
