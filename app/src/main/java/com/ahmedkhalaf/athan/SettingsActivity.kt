package com.ahmedkhalaf.athan

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.batoulapps.adhan.CalculationMethod

/**
 * Everything that is configured once and then forgotten. Kept off the main
 * screen so that screen stays a single glance: next prayer, today's times, and
 * how the athan will announce itself.
 */
class SettingsActivity : LocalizedActivity() {

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
        binding.temperatureRow.setOnClickListener { chooseTemperature() }
        binding.ramadanRow.setOnClickListener {
            startActivity(RamadanActivity.intent(this))
        }
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
        binding.temperatureName.text =
            if (prefs.weatherEnabled) getString(
                R.string.temperature_summary_on,
                getString(
                    if (prefs.fahrenheit) R.string.unit_fahrenheit else R.string.unit_celsius
                )
            )
            else getString(R.string.temperature_summary_off)
        binding.ramadanName.text = getString(R.string.ramadan_title, RamadanCalendar.upcomingHijriYear())
    }

    /**
     * The temperature switch and its units in one dialog, with the explainer
     * spelling out that this is the only thing the app ever sends anywhere.
     * Turning it off drops the cached reading immediately, so the home screen
     * does not keep showing a number the user just declined.
     */
    private fun chooseTemperature() {
        val content = layoutInflater.inflate(R.layout.dialog_temperature, null)
        val enabled = content.findViewById<android.widget.CheckBox>(R.id.temperatureEnabled)
        val units = content.findViewById<android.widget.RadioGroup>(R.id.temperatureUnits)
        val celsius = content.findViewById<android.widget.RadioButton>(R.id.unitCelsius)
        val fahrenheit = content.findViewById<android.widget.RadioButton>(R.id.unitFahrenheit)

        enabled.isChecked = prefs.weatherEnabled
        units.isEnabled = prefs.weatherEnabled
        if (prefs.fahrenheit) fahrenheit.isChecked = true else celsius.isChecked = true
        // Units mean nothing while the feature is off; grey them out rather
        // than letting someone set a preference that does not apply.
        fun syncUnits(on: Boolean) {
            celsius.isEnabled = on
            fahrenheit.isEnabled = on
        }
        syncUnits(prefs.weatherEnabled)
        enabled.setOnCheckedChangeListener { _, checked -> syncUnits(checked) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.temperature)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.weatherEnabled = enabled.isChecked
                prefs.fahrenheit = fahrenheit.isChecked
                // Answered here, so the home screen never asks again.
                prefs.weatherNoticeSeen = true
                if (!enabled.isChecked) Weather.forget()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        "NORTH_AMERICA" -> getString(R.string.method_north_america)
        "MUSLIM_WORLD_LEAGUE" -> getString(R.string.method_mwl)
        "EGYPTIAN" -> getString(R.string.method_egyptian)
        "KARACHI" -> getString(R.string.method_karachi)
        "UMM_AL_QURA" -> getString(R.string.method_umm_al_qura)
        "DUBAI" -> getString(R.string.method_dubai)
        "MOON_SIGHTING_COMMITTEE" -> getString(R.string.method_moonsighting)
        "KUWAIT" -> getString(R.string.method_kuwait)
        "QATAR" -> getString(R.string.method_qatar)
        "SINGAPORE" -> getString(R.string.method_singapore)
        "TURKEY" -> getString(R.string.method_turkey)
        "TEHRAN" -> getString(R.string.method_tehran)
        "OTHER" -> getString(R.string.method_other)
        // A method the library added since this list was written: better a
        // readable English name than a raw SCREAMING_CASE constant.
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private companion object {
        /** ±2 hours covers a seasonal clock change plus local rounding. */
        const val MAX_ADJUST = 120
    }
}
