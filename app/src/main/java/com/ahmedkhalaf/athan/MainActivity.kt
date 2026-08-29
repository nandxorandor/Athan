package com.ahmedkhalaf.athan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.res.ColorStateList
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One glance, no scrolling: where you are, what is next, today's times, and how
 * the athan will announce itself. Anything configured once lives in
 * SettingsActivity behind the gear.
 */
class MainActivity : LocalizedActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var engine: PrayerEngine

    // Built lazily, not at construction: a field initialiser runs before
    // attachBaseContext has applied the chosen language, so an eager formatter
    // would keep the previous locale for the life of the screen.
    private val timeFormat by lazy {
        SimpleDateFormat(getString(R.string.time_pattern), Locale.getDefault())
    }
    private val hijriDateFormat by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.hijri_date_pattern), Locale.getDefault())
    }
    private val ticker = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateCountdown()
            ticker.postDelayed(this, 1000L)
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) useDeviceLocation() else toast(R.string.location_denied)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining only costs the popup; audio still plays. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        prefs = Prefs(this)
        engine = PrayerEngine(prefs)

        binding.locationRow.setOnClickListener { chooseLocation() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.qiblaButton.setOnClickListener {
            startActivity(Intent(this, QiblaActivity::class.java))
        }
        binding.morningAdhkarButton.setOnClickListener {
            startActivity(AdhkarActivity.intent(this, AdhkarSitting.MORNING))
        }
        binding.eveningAdhkarButton.setOnClickListener {
            startActivity(AdhkarActivity.intent(this, AdhkarSitting.EVENING))
        }
        // Tap the reading to flip the unit. The setting also lives in Settings,
        // but this is where you are actually looking when you want the other
        // one, and a number is a big enough target on its own.
        binding.temperature.setOnClickListener {
            prefs.fahrenheit = !prefs.fahrenheit
            showTemperature(Weather.current())
            pulseTemperature()
        }
        binding.ramadanButton.setOnClickListener {
            startActivity(RamadanActivity.intent(this))
        }
        binding.stopAthanBar.setText(R.string.stop_athan_now)
        binding.stopAthanBar.setOnClickListener {
            startService(
                Intent(this, AthanService::class.java).setAction(AthanService.ACTION_STOP)
            )
            // The service takes a moment to die; hide immediately so the tap
            // is acknowledged rather than looking ignored.
            binding.stopAthanBar.visibility = View.GONE
        }
        bindLanguageSwitch()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!prefs.hasLocation) chooseLocation()
    }

    /**
     * Checking a button fires the listener, so the current language is set
     * before the listener exists — otherwise simply opening the screen would
     * look like a change and recreate the activity on every launch.
     */
    private fun bindLanguageSwitch() {
        val current = AppLocale.current(this)
        binding.languageGroup.check(
            if (current == AppLanguage.ARABIC) R.id.langArabic else R.id.langEnglish
        )
        binding.languageGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val chosen =
                if (checkedId == R.id.langArabic) AppLanguage.ARABIC else AppLanguage.ENGLISH
            if (chosen == AppLocale.current(this)) return@addOnButtonCheckedListener
            AppLocale.set(this, chosen)
            // The channel names live in the system's notification settings, so
            // they only follow the language if we rename them here.
            AthanApp.createChannels(this)
            recreate()
        }
    }

    /**
     * Never shown unless a reading is in hand: an empty slot, a dash or a
     * spinner would all be noise on a screen whose job is the prayer times.
     */
    private fun showTemperature(reading: Weather.Reading?) {
        if (reading == null || !prefs.weatherEnabled) {
            binding.temperature.visibility = View.GONE
            return
        }
        val fahrenheit = prefs.fahrenheit
        val value = if (fahrenheit) reading.celsius * 9 / 5 + 32 else reading.celsius
        val degrees = getString(
            if (fahrenheit) R.string.temperature_fahrenheit else R.string.temperature_celsius,
            Math.round(value).toInt()
        )
        // The symbol leads: the sky is the thing you take in at a glance, and
        // the number is what you read second.
        binding.temperature.text = reading.symbol.let {
            if (it.isEmpty()) degrees else "$it $degrees"
        }
        binding.temperature.visibility = View.VISIBLE
    }

    /**
     * A brief swell on the glow when the unit changes, so the tap is
     * acknowledged even though the digits barely move.
     */
    private fun pulseTemperature() {
        binding.temperature.animate().cancel()
        binding.temperature.scaleX = 1f
        binding.temperature.scaleY = 1f
        binding.temperature.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setDuration(110)
            .withEndAction {
                binding.temperature.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()
    }

    /**
     * Asked once, before a single coordinate leaves the phone. Returns true if
     * the dialog is on screen, so the caller knows to leave the user alone.
     */
    private fun askAboutTemperature(): Boolean {
        if (prefs.weatherNoticeSeen || !prefs.hasLocation) return false
        val body = TextView(this).apply {
            setText(R.string.temperature_notice_body)
            setTextColor(getColor(R.color.text_dim))
            textSize = 15f
            setLineSpacing(4f, 1f)
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, 0, pad, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.temperature_notice_title)
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setCancelable(false)
            .setPositiveButton(R.string.temperature_notice_yes) { _, _ ->
                prefs.weatherNoticeSeen = true
                prefs.weatherEnabled = true
                refresh()
            }
            .setNegativeButton(R.string.temperature_notice_no) { _, _ ->
                prefs.weatherNoticeSeen = true
                prefs.weatherEnabled = false
                Weather.forget()
                showTemperature(null)
            }
            .show()
        return true
    }

    /**
     * The month's timetable, offered once in the fortnight before the first
     * fast. The dismissal is stored as a Hijri year rather than a flag so
     * "not this year" lapses on its own next Ramadan instead of silently
     * switching the feature off for good.
     */
    private fun offerRamadanIfDue() {
        if (!prefs.ramadanPromptEnabled || !prefs.hasLocation) return
        val year = RamadanCalendar.upcomingHijriYear()
        if (year == 0 || prefs.ramadanPromptDismissedYear == year) return
        if (!RamadanCalendar.isSeason(year)) return

        prefs.ramadanPromptDismissedYear = year
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ramadan_prompt_title)
            .setMessage(getString(R.string.ramadan_prompt_body, RamadanCalendar.daysIn(year)))
            .setPositiveButton(R.string.ramadan_prompt_yes) { _, _ ->
                startActivity(RamadanActivity.intent(this, year))
            }
            .setNegativeButton(R.string.ramadan_prompt_no, null)
            .show()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // Before the first tick, so opening the app mid-athan shows the Stop
        // straight away rather than a second later.
        binding.stopAthanBar.visibility =
            if (AthanService.isPlaying) View.VISIBLE else View.GONE
        refresh()
        ticker.post(tick)
        AthanScheduler.scheduleNext(this)
        warnIfExactAlarmsBlocked()
        // Order matters: the temperature notice is asked once and answered
        // before anything is fetched, and only after that can Ramadan have the
        // screen. Two dialogs at once on a first launch is nobody's welcome.
        if (!askAboutTemperature()) offerRamadanIfDue()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun refresh() {
        binding.cityName.text =
            if (prefs.hasLocation) prefs.cityName.ifBlank { getString(R.string.location_set) }
            else getString(R.string.set_location)
        updateHijriDate()
        showTemperature(Weather.current())
        Weather.refresh(this) { reading -> runOnUiThread { showTemperature(reading) } }

        val times = engine.today()
        binding.timesContainer.removeAllViews()
        if (times.isEmpty()) {
            binding.nextPrayer.setText(R.string.no_location_yet)
            return
        }
        val next = engine.next()
        times.forEach { (slot, time) ->
            val row = layoutInflater.inflate(R.layout.item_prayer, binding.timesContainer, false)
            val isNext = next?.slot == slot && next.time.after(Date())
            // Sunrise gets its own warm colour: it is a marker, not something
            // that will call you, so it must not read as one of the five.
            val color = ContextCompat.getColor(
                this, when {
                    isNext -> R.color.accent
                    !slot.notifies -> R.color.sunrise
                    else -> R.color.text
                }
            )
            row.findViewById<TextView>(R.id.prayerLabel).apply {
                setText(slot.labelRes)
                setTextColor(color)
            }
            row.findViewById<TextView>(R.id.prayerTime).apply {
                text = timeFormat.format(time)
                setTextColor(color)
            }
            bindModeIcons(row, slot)
            binding.timesContainer.addView(row)
        }
        updateCountdown()
    }

    /**
     * Each prayer carries its own Sound/Vibrate/Silent choice. Sunrise has no
     * icons at all — it never announces anything, so offering a mode for it
     * would imply it could.
     */
    private fun bindModeIcons(row: View, slot: Slot) {
        val group = row.findViewById<View>(R.id.modeGroup)
        if (!slot.notifies) {
            // GONE, not INVISIBLE: the time stays right-aligned either way, but
            // reserving the icons' width squeezed "☀️ Sunrise" into an ellipsis.
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE

        val icons = listOf(
            row.findViewById<ImageView>(R.id.modeSound) to AthanMode.SOUND,
            row.findViewById<ImageView>(R.id.modeVibrate) to AthanMode.VIBRATE,
            row.findViewById<ImageView>(R.id.modeSilent) to AthanMode.SILENT,
        )
        val current = prefs.modeFor(slot)
        icons.forEach { (icon, mode) ->
            icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (mode == current) R.color.accent else R.color.icon_idle)
            )
            icon.setOnClickListener {
                prefs.setMode(slot, mode)
                bindModeIcons(row, slot)
            }
        }
    }

    private fun updateCountdown() {
        updateHijriDate()
        // Checked on the same one-second tick as the countdown: no receiver to
        // register, and it cannot get stuck showing after the athan has ended.
        binding.stopAthanBar.visibility =
            if (AthanService.isPlaying) View.VISIBLE else View.GONE
        val next = engine.next() ?: return
        val remaining = next.time.time - System.currentTimeMillis()
        if (remaining <= 0) {
            refresh()
            return
        }
        val hours = TimeUnit.MILLISECONDS.toHours(remaining)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        val left = if (hours > 0) getString(R.string.countdown_hm, hours, minutes)
        else getString(R.string.countdown_ms, minutes, seconds)
        binding.nextPrayer.text =
            getString(R.string.next_in, getString(next.slot.labelRes), left)
    }

    /** Android's built-in Hijri calendar keeps this offline and in sync with the device date. */
    private fun updateHijriDate() {
        binding.hijriDate.text = hijriDateFormat.format(HijrahDate.now())
    }

    private fun chooseLocation() {
        val input = EditText(this).apply {
            hint = getString(R.string.city_hint)
            setText(prefs.cityName)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_location)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ -> lookUpCity(input.text.toString()) }
            .setNeutralButton(R.string.use_gps) { _, _ -> requestDeviceLocation() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestDeviceLocation() {
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    /**
     * Location services being off is the one cause of "no fix" the user can
     * actually act on, and the symptom otherwise looks identical to a phone
     * that simply has no cached position. Android does not allow switching it
     * on programmatically, so offer the settings screen.
     */
    private fun locationServicesEnabled(manager: LocationManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

    private fun promptEnableLocation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_off_title)
            .setMessage(R.string.location_off_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun useDeviceLocation() {
        val manager = getSystemService(LocationManager::class.java)
        if (!locationServicesEnabled(manager)) {
            promptEnableLocation()
            return
        }
        // Last known fix rather than a live request: prayer times only need
        // city-level accuracy, and a stale fix beats making the user wait.
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (fix == null) {
            toast(R.string.no_fix)
            return
        }
        prefs.latitude = fix.latitude
        prefs.longitude = fix.longitude
        prefs.cityName = reverseGeocode(fix.latitude, fix.longitude)
        refresh()
        AthanScheduler.scheduleNext(this)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String = runCatching {
        Geocoder(this, Locale.getDefault()).getFromLocation(lat, lng, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName }
            .orEmpty()
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun lookUpCity(query: String) {
        if (query.isBlank()) return
        Thread {
            val match = runCatching {
                Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull()
            }.getOrNull()
            runOnUiThread {
                if (match == null) {
                    toast(R.string.city_not_found)
                    return@runOnUiThread
                }
                prefs.latitude = match.latitude
                prefs.longitude = match.longitude
                prefs.cityName = match.locality ?: match.adminArea ?: query
                refresh()
                AthanScheduler.scheduleNext(this)
            }
        }.start()
    }

    /**
     * Exact alarms can be revoked in system settings, and the failure is silent:
     * the athan simply never fires. Make it visible and one tap to fix.
     */
    private fun warnIfExactAlarmsBlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(android.app.AlarmManager::class.java)
        val blocked = !alarmManager.canScheduleExactAlarms()
        binding.alarmWarning.visibility = if (blocked) View.VISIBLE else View.GONE
        binding.alarmWarning.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
}
