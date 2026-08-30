package com.ahmedkhalaf.athan

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityReminderSettingsBinding

/**
 * Configures the pre-prayer heads-up: on/off, how many minutes early, which
 * ringtone, and whether to vibrate. Any change re-arms the alarms so it takes
 * effect from the next prayer.
 */
class ReminderSettingsActivity : LocalizedActivity() {

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

    /**
     * Any audio file on the phone, not just what the ringtone picker lists.
     * The reminder is often something with meaning in it — a hadith urging
     * people to come early, a du'aa — and such a recording sits in Downloads,
     * where a ringtone picker will never show it.
     */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Without the persistable grant the URI is readable now and dead by
        // tomorrow's Fajr, which is exactly when it is needed.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.reminderSound = uri.toString()
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

        // SeekBar counts from 0, the setting from MIN_REMINDER, so the two are
        // one apart throughout.
        binding.minutesSlider.max = Prefs.MAX_REMINDER - Prefs.MIN_REMINDER
        binding.minutesSlider.progress = prefs.reminderMinutes - Prefs.MIN_REMINDER
        binding.minutesSlider.progressTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
        binding.minutesSlider.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
        binding.minutesSlider.thumb = getDrawable(R.drawable.volume_thumb)
        binding.minutesValue.text = getString(R.string.minutes_value, prefs.reminderMinutes)
        binding.minutesSlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: android.widget.SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val minutes = progress + Prefs.MIN_REMINDER
                    prefs.reminderMinutes = minutes
                    binding.minutesValue.text = getString(R.string.minutes_value, minutes)
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit

                // Re-arm once, on release. Doing it per step would rewrite the
                // alarm sixty times across one drag.
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) = applied()
            }
        )

        binding.toneRow.setOnClickListener { chooseSoundSource() }

        binding.volumeSlider.progress = prefs.reminderVolume
        binding.volumeSlider.progressTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
        binding.volumeSlider.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
        binding.volumeSlider.thumb = getDrawable(R.drawable.volume_thumb)
        binding.volumeValue.text = getString(R.string.volume_percent, prefs.reminderVolume)
        binding.volumeSlider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: android.widget.SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    prefs.reminderVolume = progress
                    binding.volumeValue.text = getString(R.string.volume_percent, progress)
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit

                // Preview on release, not on every step: hearing the tone at the
                // volume just chosen is the only way to judge it, but playing it
                // while the finger is still moving would stutter.
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) = previewTone()
            }
        )

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
        // The tone and its volume only matter when sound is the chosen mode.
        val soundOn = !prefs.reminderVibrate
        binding.toneRow.alpha = if (soundOn) 1f else 0.4f
        binding.toneRow.isEnabled = soundOn
        binding.volumeRow.alpha = if (soundOn) 1f else 0.4f
        binding.volumeSlider.isEnabled = soundOn
    }

    /**
     * Plays the chosen tone at the chosen volume, on USAGE_ALARM - the same
     * routing ReminderService uses, so what is heard here is what will be heard
     * at the prayer rather than a preview down a different stream.
     */
    private fun previewTone() {
        if (prefs.reminderVibrate) return
        preview?.runCatching { stop() }
        preview?.release()
        val uri = prefs.reminderSound.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI ?: return
        preview = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderSettingsActivity, uri)
                val v = prefs.reminderVolume / 100f
                setVolume(v, v)
                setOnCompletionListener { it.release(); preview = null }
                prepare()
                start()
            }
        }.getOrNull()
    }

    private var preview: android.media.MediaPlayer? = null

    override fun onStop() {
        super.onStop()
        // Never let a preview outlive the screen that started it.
        preview?.runCatching { stop() }
        preview?.release()
        preview = null
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

    /**
     * Two ways in, asked before either: the phone's own tones, or a file the
     * user has put there themselves.
     */
    private fun chooseSoundSource() {
        val options = arrayOf(
            getString(R.string.phone_ringtone),
            getString(R.string.choose_from_device),
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.reminder_sound)
            .setItems(options) { _, which ->
                if (which == 0) pickSound() else pickFile.launch(arrayOf("audio/*"))
            }
            .show()
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
