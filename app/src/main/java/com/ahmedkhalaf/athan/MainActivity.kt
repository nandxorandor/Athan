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
    private val gregorianDateFormat by lazy { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    private val ticker = Handler(Looper.getMainLooper())

    /** Start of the stretch the ring measures; refreshed with the times. */
    private var previousTime: Date? = null

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
        // Home stays selected: the other three open a sheet or a screen, and
        // returning from either lands back here. Returning false keeps the
        // highlight where it is.
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navTimes -> showAllTimes()
                R.id.navCalendar -> startActivity(RamadanActivity.intent(this))
                R.id.navSettings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            item.itemId == R.id.navHome
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
        binding.allTimesButton.setOnClickListener { showAllTimes() }
        binding.nextCard.setOnClickListener { showAllTimes() }
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
        // No bottom padding here: the bottom bar pads itself for the system
        // navigation, and doing it twice left a band of empty bar beneath the
        // labels.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        // Re-fitted on every layout, so a warning appearing, a longer city
        // name or the Stop bar all give the ring back the room they take.
        binding.scroll.viewTreeObserver.addOnGlobalLayoutListener { fitRing() }
    }

    /**
     * The ring takes whatever height the rest of the screen leaves, between a
     * floor where its text would get too small and a ceiling where it would
     * just be big. The text inside is then scaled to sit within it. Fixed
     * sizes looked right on one phone and pushed the buttons off the screen
     * on another with larger text and display zoom.
     */
    private fun fitRing() {
        if (binding.nextCard.visibility != View.VISIBLE) return
        val density = resources.displayMetrics.density
        val viewport = binding.scroll.height
        val card = binding.nextCard.height
        if (viewport == 0 || card == 0) return
        // Added up item by item, not read off the content's height: the
        // ScrollView stretches the content to fill the screen, so its height
        // already includes the free space the ring could grow into, and the
        // ring could shrink but never grow back.
        val content = binding.content
        var others = content.paddingTop + content.paddingBottom
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child === binding.nextCard || child.visibility == View.GONE) continue
            val margins = child.layoutParams as android.view.ViewGroup.MarginLayoutParams
            others += child.height + margins.topMargin + margins.bottomMargin
        }
        others += (binding.nextCard.layoutParams as android.view.ViewGroup.MarginLayoutParams).let {
            it.topMargin + it.bottomMargin
        }
        val ceiling = minOf(binding.nextCard.width, (300 * density).toInt())
        val floor = minOf((190 * density).toInt(), ceiling)
        val size = (viewport - others).coerceIn(floor, ceiling)

        // The card is sized with the ring: otherwise the text block, which is
        // measured before it is scaled, would hold the card open at its full
        // height and the space would never come back.
        listOf(binding.nextRing, binding.nextCard).forEach { view ->
            val params = view.layoutParams
            val width = if (view === binding.nextRing) size else params.width
            if (kotlin.math.abs(params.height - size) > 2 || params.width != width) {
                params.height = size
                params.width = width
                view.layoutParams = params
            }
        }

        // The text inside, at the size that fits. Measured unconstrained - in
        // the card it is capped at the card's height, which would hide how
        // much too big it is - and divided by the scale already applied, to
        // get its natural size. The font sizes themselves change, not a
        // drawing scale, so what is measured is what is drawn.
        val inner = binding.nextInner
        val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        inner.measure(unbounded, unbounded)
        if (inner.measuredWidth == 0 || inner.measuredHeight == 0) return
        val naturalWidth = inner.measuredWidth / ringTextScale
        val naturalHeight = inner.measuredHeight / ringTextScale
        // Clear of the arc and its end dot. The widest line, the countdown,
        // sits mid-circle where there is the most width, so width and height
        // are checked separately rather than as one diagonal.
        val room = size - 2 * 22 * density
        // Height gets less of it than width: the first and last lines sit
        // where the circle is narrowing, and at full height "Next prayer" and
        // the iqama touched the arc and its end dot.
        val scale = minOf(1f, room * 0.80f / naturalHeight, room * 0.92f / naturalWidth)
        if (kotlin.math.abs(scale - ringTextScale) < 0.02f) return
        ringTextScale = scale
        ringTexts().forEach { (view, base) ->
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
    }

    /** Current shrink applied to the ring's text; 1 is the layout's own sizes. */
    private var ringTextScale = 1f

    /** The ring's text views with their layout sizes, captured once. */
    private val ringTextBase by lazy {
        (0 until binding.nextInner.childCount)
            .map { binding.nextInner.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it to it.textSize }
    }

    private fun ringTexts() = ringTextBase

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
        checkMethodForStoredLocation()
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
        val compact = prefs.homeCompact && times.isNotEmpty()
        // Compact: the card and "All times". Full: the one-line countdown and
        // the table, exactly as the screen was before the card existed.
        binding.nextCard.visibility = if (compact) View.VISIBLE else View.GONE
        binding.allTimesButton.visibility = if (compact) View.VISIBLE else View.GONE
        binding.nextPrayer.visibility = if (compact) View.GONE else View.VISIBLE
        binding.timesContainer.visibility = if (compact) View.GONE else View.VISIBLE
        if (times.isEmpty()) {
            binding.nextPrayer.setText(R.string.no_location_yet)
            return
        }
        previousTime = previousPrayerTime()
        if (!compact) fillTimes(binding.timesContainer, times)
        updateCountdown()
    }

    /**
     * The table rows, with each prayer's Sound / Vibrate / Silent icons. Used
     * by the full home screen and by the "All times" sheet alike, so the two
     * can never show different times or different choices.
     */
    private fun fillTimes(
        container: android.widget.LinearLayout,
        times: List<Pair<Slot, Date>>,
        boxNext: Boolean = false,
    ) {
        container.removeAllViews()
        val next = engine.next()
        val inset = (resources.displayMetrics.density * 12).toInt()
        times.forEach { (slot, time) ->
            val row = layoutInflater.inflate(R.layout.item_prayer, container, false)
            val isNext = next?.slot == slot && next.time.after(Date())
            // In the sheet every row is inset, so the boxed one does not shift
            // its text sideways relative to the rest.
            if (boxNext) {
                row.setPaddingRelative(inset, row.paddingTop, inset, row.paddingBottom)
                if (isNext) row.setBackgroundResource(R.drawable.bg_next_row)
            }
            // Sunrise gets its own warm colour: it is a marker, not something
            // that will call you, so it must not read as one of the five.
            val color = ContextCompat.getColor(
                this, when {
                    isNext -> R.color.accent
                    !slot.notifies -> R.color.sunrise
                    else -> R.color.text
                }
            )
            row.findViewById<ImageView>(R.id.prayerIcon).apply {
                setImageResource(iconFor(slot))
                imageTintList = ColorStateList.valueOf(color)
            }
            row.findViewById<TextView>(R.id.prayerLabel).apply {
                // The sunrise label carries its own sun for places with no
                // icon beside it; here the icon is that sun.
                text = getString(slot.labelRes).removePrefix("☀️ ")
                setTextColor(color)
            }
            row.findViewById<TextView>(R.id.prayerTime).apply {
                text = timeFormat.format(time)
                setTextColor(color)
            }
            bindModeIcons(row, slot)
            row.findViewById<TextView>(R.id.prayerExtra).apply {
                val parts = listOfNotNull(
                    prefs.offsetFor(slot).takeIf { it != 0 }
                        ?.let { getString(R.string.adjust_minutes, signed(it)) },
                    prefs.iqamaFor(slot).takeIf { it > 0 && slot.notifies }
                        ?.let { getString(R.string.iqama_at, timeFormat.format(plusMinutes(time, it))) },
                )
                text = parts.joinToString(" · ")
                visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
                if (isNext) setTextColor(color)
            }
            row.setOnClickListener {
                editPrayer(slot) { fillTimes(container, engine.today(), boxNext) }
            }
            container.addView(row)
        }
    }

    /**
     * One prayer's own minutes and its iqama. Both apply at once - the times,
     * the alarms and the home screen - so what the user sets here is what the
     * athan will do.
     */
    private fun editPrayer(slot: Slot, onSaved: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_prayer, null)
        val saved = prefs.offsetFor(slot)
        var offset = saved
        var iqama = prefs.iqamaFor(slot)
        // Today's time without this prayer's own minutes, to preview against.
        val base = engine.today().firstOrNull { it.first == slot }?.second
            ?.let { plusMinutes(it, -saved) }

        val baseView = view.findViewById<TextView>(R.id.adjustBase)
        val adjustValue = view.findViewById<TextView>(R.id.adjustValue)
        val iqamaValue = view.findViewById<TextView>(R.id.iqamaValue)
        baseView.text = base?.let { getString(R.string.prayer_adjust_base, timeFormat.format(it)) }.orEmpty()

        fun render() {
            val change = if (offset == 0) getString(R.string.adjust_none_short)
            else getString(R.string.adjust_minutes, signed(offset))
            adjustValue.text = base?.let { "$change · ${timeFormat.format(plusMinutes(it, offset))}" } ?: change
            iqamaValue.text = if (iqama == 0) getString(R.string.iqama_off) else {
                val after = getString(R.string.iqama_after, iqama)
                base?.let { "$after\n${timeFormat.format(plusMinutes(it, offset + iqama))}" } ?: after
            }
        }
        view.findViewById<View>(R.id.adjustMinus).setOnClickListener {
            offset = (offset - 1).coerceAtLeast(-MAX_PRAYER_OFFSET); render()
        }
        view.findViewById<View>(R.id.adjustPlus).setOnClickListener {
            offset = (offset + 1).coerceAtMost(MAX_PRAYER_OFFSET); render()
        }
        view.findViewById<View>(R.id.iqamaMinus).setOnClickListener {
            iqama = (iqama - 1).coerceAtLeast(0); render()
        }
        view.findViewById<View>(R.id.iqamaPlus).setOnClickListener {
            iqama = (iqama + 1).coerceAtMost(MAX_IQAMA); render()
        }
        // Sunrise is a marker, not a prayer: it moves, but it has no iqama.
        view.findViewById<View>(R.id.iqamaGroup).visibility =
            if (slot.notifies) View.VISIBLE else View.GONE
        render()

        fun apply(newOffset: Int, newIqama: Int) {
            prefs.setOffset(slot, newOffset)
            prefs.setIqama(slot, newIqama)
            AthanScheduler.scheduleNext(this)
            refresh()
            onSaved()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(slot.labelRes).removePrefix("☀️ "))
            .setView(view)
            .setPositiveButton(R.string.prayer_save) { _, _ -> apply(offset, iqama) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.prayer_reset) { _, _ -> apply(0, 0) }
            .show()
    }

    private fun plusMinutes(time: Date, minutes: Int) = Date(time.time + minutes * 60_000L)

    /**
     * "+2" or "-3", isolated so the sign stays in front of the number in an
     * Arabic line instead of drifting to the other side of it.
     */
    private fun signed(minutes: Int): String =
        androidx.core.text.BidiFormatter.getInstance()
            .unicodeWrap(String.format(Locale.US, "%+d", minutes))

    private fun iconFor(slot: Slot): Int = when (slot) {
        Slot.FAJR -> R.drawable.ic_prayer_fajr
        Slot.SUNRISE, Slot.DHUHR -> R.drawable.ic_adhkar_morning
        Slot.ASR -> R.drawable.ic_prayer_asr
        Slot.MAGHRIB -> R.drawable.ic_prayer_maghrib
        Slot.ISHA -> R.drawable.ic_adhkar_evening
    }

    /** Today's full table in a sheet, from "All times", the card or the bar. */
    private fun showAllTimes() {
        val times = engine.today()
        if (times.isEmpty()) {
            chooseLocation()
            return
        }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_times, null)
        content.findViewById<TextView>(R.id.sheetCity).text = binding.cityName.text
        content.findViewById<TextView>(R.id.sheetHijri).text = binding.hijriDate.text
        content.findViewById<TextView>(R.id.sheetGregorian).text = binding.gregorianDate.text
        fillTimes(content.findViewById(R.id.sheetTimes), times, boxNext = true)
        content.findViewById<View>(R.id.sheetClose).setOnClickListener { sheet.dismiss() }
        content.findViewById<View>(R.id.sheetRamadan).setOnClickListener {
            sheet.dismiss()
            startActivity(RamadanActivity.intent(this))
        }
        content.findViewById<View>(R.id.sheetShare).setOnClickListener { shareToday(times) }
        sheet.setContentView(content)
        // Fully open at once: half a table, with the rest behind a drag, is
        // the one thing this sheet must not be.
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Today's times as plain text, for a message to family. */
    private fun shareToday(times: List<Pair<Slot, Date>>) {
        val text = buildString {
            append(getString(R.string.prayer_times_title)).append(" · ").append(binding.cityName.text)
            append('\n').append(binding.hijriDate.text)
            append('\n').append(binding.gregorianDate.text).append('\n')
            times.forEach { (slot, time) ->
                append('\n').append(getString(slot.labelRes).removePrefix("☀️ "))
                append(": ").append(timeFormat.format(time))
            }
            append("\n\n").append(getString(R.string.app_name))
        }
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    getString(R.string.share_times),
                )
            )
        }
    }

    /**
     * When the stretch the ring measures began: the latest prayer (or sunrise)
     * at or before now, looking back into yesterday for the hours before Fajr.
     */
    private fun previousPrayerTime(now: Date = Date()): Date? {
        val yesterday = java.util.Calendar.getInstance().apply {
            time = now
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }.time
        return (engine.on(yesterday) + engine.on(now))
            .map { it.second }
            .filter { !it.after(now) }
            .maxOrNull()
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

        binding.nextName.setText(next.slot.labelRes)
        binding.nextCountdown.text =
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        binding.nextTime.text = timeFormat.format(next.time)
        val iqama = prefs.iqamaFor(next.slot)
        binding.nextIqama.visibility = if (iqama > 0) View.VISIBLE else View.GONE
        if (iqama > 0) {
            binding.nextIqama.text =
                getString(R.string.iqama_at, timeFormat.format(plusMinutes(next.time, iqama)))
        }
        binding.nextRing.progress = previousTime?.let { prev ->
            val span = next.time.time - prev.time
            if (span <= 0) 0f else 1f - remaining.toFloat() / span
        } ?: 0f
    }

    /** Android's built-in Hijri calendar keeps this offline and in sync with the device date. */
    private fun updateHijriDate() {
        binding.hijriDate.text = hijriDateFormat.format(HijrahDate.now())
        binding.gregorianDate.text = gregorianDateFormat.format(Date())
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
        val place = placeAt(fix.latitude, fix.longitude)
        prefs.cityName = place
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName }
            .orEmpty()
        matchMethodToCountry(place?.countryCode)
        refresh()
        AthanScheduler.scheduleNext(this)
    }

    @Suppress("DEPRECATION")
    private fun placeAt(lat: Double, lng: Double): android.location.Address? = runCatching {
        Geocoder(this, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
    }.getOrNull()

    /**
     * The calculation method follows the country, until the user picks one in
     * Settings. Every new install used to start on North America wherever it
     * was, so someone in Cairo or Casablanca got the wrong Fajr and Isha until
     * they found the setting.
     */
    private fun matchMethodToCountry(countryCode: String?) {
        if (prefs.methodChosen || countryCode.isNullOrEmpty()) return
        prefs.methodCountryChecked = true
        val method = Methods.forCountry(countryCode)
        if (method == prefs.method) return
        prefs.method = method
        AthanScheduler.scheduleNext(this)
        refresh()
        android.widget.Toast.makeText(
            this, getString(R.string.method_auto_set, Methods.label(this, method)),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Once, for installs that set their location before the method followed
     * the country: look up the stored location's country off the main thread
     * and match the method to it. Tried again next time if the lookup fails,
     * which it does without a network.
     */
    private fun checkMethodForStoredLocation() {
        if (prefs.methodChosen || prefs.methodCountryChecked || !prefs.hasLocation) return
        val lat = prefs.latitude
        val lng = prefs.longitude
        Thread {
            val country = placeAt(lat, lng)?.countryCode
            if (country != null) runOnUiThread { matchMethodToCountry(country) }
        }.start()
    }

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
                matchMethodToCountry(match.countryCode)
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

    private companion object {
        /** Half an hour either way: beyond that it is a different method, not a tweak. */
        const val MAX_PRAYER_OFFSET = 30

        /** Iqama up to an hour after the athan. */
        const val MAX_IQAMA = 60
    }
}
