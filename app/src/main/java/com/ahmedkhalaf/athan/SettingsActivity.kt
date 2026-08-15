package com.ahmedkhalaf.athan

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivitySettingsBinding
import com.batoulapps.adhan.CalculationMethod

/**
 * Everything that is configured once and then forgotten. Kept off the main
 * screen so that screen stays a single glance: next prayer, today's times, and
 * how the athan will announce itself.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = Prefs(this)
        binding.audioOptionsRow.setOnClickListener {
            startActivity(Intent(this, AthanAudioOptionsActivity::class.java))
        }
        binding.appVolumeRow.setOnClickListener { chooseVolume() }
        binding.reminderRow.setOnClickListener {
            startActivity(Intent(this, ReminderSettingsActivity::class.java))
        }
        binding.methodRow.setOnClickListener { chooseMethod() }
        binding.madhabRow.setOnClickListener { chooseMadhab() }
        binding.adjustRow.setOnClickListener { chooseAdjustment() }
        binding.creditsRow.setOnClickListener {
            startActivity(Intent(this, CreditsActivity::class.java))
        }
        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.methodName.text = methodLabel(prefs.method)
        binding.madhabName.text =
            getString(if (prefs.madhab == "HANAFI") R.string.madhab_hanafi else R.string.madhab_shafi)
        binding.adjustName.text = offsetLabel(prefs.adjustmentMinutes)
        binding.reminderName.text =
            if (prefs.reminderEnabled) getString(R.string.reminder_summary_on, prefs.reminderMinutes)
            else getString(R.string.reminder_summary_off)
        binding.appVolumeName.text = getString(R.string.volume_percent, prefs.volume)
    }

    /** Any change here moves the prayer times, so the alarm must be re-armed. */
    private fun applied() {
        refresh()
        AthanScheduler.scheduleNext(this)
    }

    /**
     * Built from CalculationMethod.values() rather than a hardcoded list, so the
     * picker cannot drift out of sync with whatever the library actually ships.
     */
    private fun chooseMethod() {
        val methods = CalculationMethod.values()
        val labels = methods.map { methodLabel(it.name) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.calculation_method)
            .setSingleChoiceItems(labels, methods.indexOfFirst { it.name == prefs.method }) { d, which ->
                prefs.method = methods[which].name
                d.dismiss()
                applied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMadhab() {
        val values = arrayOf("SHAFI", "HANAFI")
        val labels = arrayOf(
            getString(R.string.madhab_shafi_long),
            getString(R.string.madhab_hanafi)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.juristic_method)
            .setSingleChoiceItems(labels, values.indexOf(prefs.madhab)) { d, which ->
                prefs.madhab = values[which]
                d.dismiss()
                applied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * displayedValues rather than a formatter: NumberPicker's formatter is not
     * applied to the initially selected row until it is scrolled, which shows a
     * raw index on open.
     */
    private fun chooseAdjustment() {
        val labels = (-MAX_ADJUST..MAX_ADJUST).map { offsetLabel(it) }.toTypedArray()
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = labels.size - 1
            displayedValues = labels
            wrapSelectorWheel = false
            value = (prefs.adjustmentMinutes + MAX_ADJUST).coerceIn(0, labels.size - 1)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.time_adjustment)
            .setMessage(R.string.time_adjustment_hint)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.adjustmentMinutes = picker.value - MAX_ADJUST
                applied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseVolume() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }
        val value = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(getColor(R.color.accent))
            text = getString(R.string.volume_percent, prefs.volume)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = prefs.volume
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.volume_green))
            thumb = getDrawable(R.drawable.volume_thumb)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.volume = progress
                    value.text = getString(R.string.volume_percent, progress)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        content.addView(value)
        content.addView(slider)
        AlertDialog.Builder(this)
            .setTitle(R.string.app_volume)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun offsetLabel(minutes: Int): String = when {
        minutes == 0 -> getString(R.string.adjust_none)
        minutes > 0 -> getString(R.string.adjust_plus, minutes)
        else -> getString(R.string.adjust_minus, -minutes)
    }

    private fun methodLabel(name: String): String = when (name) {
        "NORTH_AMERICA" -> "North America (ISNA)"
        "MUSLIM_WORLD_LEAGUE" -> "Muslim World League"
        "EGYPTIAN" -> "Egyptian General Authority"
        "KARACHI" -> "Karachi"
        "UMM_AL_QURA" -> "Umm al-Qura (Makkah)"
        "DUBAI" -> "Dubai"
        "MOON_SIGHTING_COMMITTEE" -> "Moonsighting Committee"
        "KUWAIT" -> "Kuwait"
        "QATAR" -> "Qatar"
        "SINGAPORE" -> "Singapore"
        "TURKEY" -> "Turkey"
        "TEHRAN" -> "Tehran"
        "OTHER" -> "Other"
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private companion object {
        /** ±2 hours covers a seasonal clock change plus local rounding. */
        const val MAX_ADJUST = 120
    }
}
