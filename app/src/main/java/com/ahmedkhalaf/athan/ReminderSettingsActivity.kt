package com.ahmedkhalaf.athan

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityReminderSettingsBinding

/**
 * Configures the pre-prayer heads-up: on/off, how many minutes early, which
 * ringtone, and whether to vibrate. Any change re-arms the alarms so it takes
 * effect from the next prayer.
 */
class ReminderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderSettingsBinding
    private lateinit var prefs: Prefs

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // A null pick means "Silent"; store empty and fall back to default tone.
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        prefs.reminderSound = uri?.toString().orEmpty()
        refresh()
        applied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)

        binding.enableSwitch.isChecked = prefs.reminderEnabled
        binding.enableSwitch.setOnCheckedChangeListener { _, on ->
            prefs.reminderEnabled = on
            updateEnabledState()
            applied()
        }

        binding.minutesGroup.check(
            when (prefs.reminderMinutes) {
                5 -> R.id.min5
                15 -> R.id.min15
                else -> R.id.min10
            }
        )
        binding.minutesGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.reminderMinutes = when (checkedId) {
                R.id.min5 -> 5
                R.id.min15 -> 15
                else -> 10
            }
            applied()
        }

        binding.toneRow.setOnClickListener { pickSound() }

        // Sound and Vibrate are mutually exclusive; exactly one is on. Setting
        // the checked state fires the listener, so guard against the ping-pong.
        binding.soundSwitch.isChecked = !prefs.reminderVibrate
        binding.vibrateSwitch.isChecked = prefs.reminderVibrate
        binding.soundSwitch.setOnCheckedChangeListener { _, on -> setVibrate(!on) }
        binding.vibrateSwitch.setOnCheckedChangeListener { _, on -> setVibrate(on) }

        updateEnabledState()
        refresh()
    }

    private var applyingExclusive = false

    /** Single source of truth: vibrate on ⇒ sound off, and vice versa. */
    private fun setVibrate(vibrate: Boolean) {
        if (applyingExclusive) return
        applyingExclusive = true
        prefs.reminderVibrate = vibrate
        binding.vibrateSwitch.isChecked = vibrate
        binding.soundSwitch.isChecked = !vibrate
        applyingExclusive = false
        refresh()
    }

    private fun refresh() {
        binding.soundName.text = reminderSoundLabel()
        // The tone only matters when sound is the chosen mode.
        val soundOn = !prefs.reminderVibrate
        binding.toneRow.alpha = if (soundOn) 1f else 0.4f
        binding.toneRow.isEnabled = soundOn
    }

    /** Re-arm so a changed minutes/enabled value applies from the next prayer. */
    private fun applied() = AthanScheduler.scheduleNext(this)

    private fun updateEnabledState() {
        val on = prefs.reminderEnabled
        binding.options.alpha = if (on) 1f else 0.4f
        setEnabledDeep(binding.options, on)
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    private fun pickSound() {
        val current = prefs.reminderSound.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.reminder_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        pickRingtone.launch(intent)
    }

    private fun reminderSoundLabel(): String {
        val value = prefs.reminderSound
        if (value.isEmpty()) return getString(R.string.default_tone)
        return SoundSource.customName(this, Uri.parse(value)) ?: getString(R.string.custom_sound)
    }
}
