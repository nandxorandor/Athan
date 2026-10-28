package com.ahmedkhalaf.athan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityMonthBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A month's prayer times: this month when opened from the bottom bar, any
 * other from the arrows or by picking a date, in either calendar - Ramadan is
 * simply its Hijri month. Sharing offers it three ways: a one-page PDF to
 * print, an image to send in a chat, or a CSV for a spreadsheet.
 */
class MonthActivity : LocalizedActivity() {

    private lateinit var binding: ActivityMonthBinding
    private lateinit var prefs: Prefs
    private lateinit var month: Month
    private var days: List<TimetableDay> = emptyList()

    /**
     * No AM/PM: eight columns do not fit a phone with it, and a timetable is
     * read in order - nobody takes Isha for the morning.
     */
    private val timeFormat by lazy { SimpleDateFormat("h:mm", Locale.getDefault()) }
    private val dateFormat by lazy { SimpleDateFormat("d/M", Locale.getDefault()) }
    private val longDayFormat by lazy { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Added to the layout's own padding, not substituted for it: this
        // screen's padding is on the root, and replacing it wiped the margin
        // the table needs.
        val basePadding = resources.displayMetrics.density.times(14).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                bars.left + basePadding,
                bars.top + basePadding,
                bars.right + basePadding,
                bars.bottom + basePadding,
            )
            insets
        }

        prefs = Prefs(this)
        prefs.moveRamadanNotes()
        month = savedInstanceState?.let {
            Month(it.getBoolean(KEY_HIJRI), it.getInt(KEY_YEAR), it.getInt(KEY_MONTH))
        } ?: intent.getIntExtra(EXTRA_RAMADAN_YEAR, 0).takeIf { it > 0 }?.let { year ->
            RamadanCalendar.firstDay(year)?.let { MonthTimetable.containing(it, hijri = true) }
        } ?: MonthTimetable.containing(Date(), hijri = false)

        binding.previousMonth.setOnClickListener { show(MonthTimetable.shift(month, -1)) }
        binding.nextMonth.setOnClickListener { show(MonthTimetable.shift(month, 1)) }
        binding.title.setOnClickListener { pickMonth() }
        binding.todayButton.setOnClickListener {
            show(MonthTimetable.containing(Date(), month.hijri))
        }
        binding.calendarToggle.check(if (month.hijri) R.id.hijriButton else R.id.gregorianButton)
        binding.calendarToggle.addOnButtonCheckedListener { _, id, checked ->
            val hijri = id == R.id.hijriButton
            if (!checked || hijri == month.hijri) return@addOnButtonCheckedListener
            // This month in the other calendar: today's month if it is
            // showing, otherwise the one its middle falls in.
            val anchor = if (MonthTimetable.containing(Date(), month.hijri) == month) Date()
            else MonthTimetable.middle(month)
            show(MonthTimetable.containing(anchor, hijri))
        }
        binding.shareButton.setOnClickListener { chooseFormat() }
        show(month)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HIJRI, month.hijri)
        outState.putInt(KEY_YEAR, month.year)
        outState.putInt(KEY_MONTH, month.month)
    }

    private fun show(shown: Month) {
        month = shown
        binding.title.text = MonthTimetable.title(month)
        val current = MonthTimetable.containing(Date(), month.hijri) == month
        binding.todayButton.visibility = if (current) View.GONE else View.VISIBLE

        days = MonthTimetable.build(month, prefs)
        val empty = days.isEmpty()
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        listOf(binding.shareButton, binding.subtitle, binding.noteHint, binding.header)
            .forEach { it.visibility = if (empty) View.GONE else View.VISIBLE }
        binding.table.removeAllViews()
        if (empty) return

        binding.subtitle.text = getString(
            R.string.month_subtitle,
            // Isolated: an English city name in an Arabic line (or the reverse)
            // otherwise pulls the neighbouring number into its own direction.
            androidx.core.text.BidiFormatter.getInstance()
                .unicodeWrap(prefs.cityName.ifBlank { getString(R.string.location_set) }),
            otherCalendarSpan(),
        )
        fillHeader()
        fillTable()
        // This month opens on today's row, not on the 1st.
        if (current) binding.scroll.post {
            val index = days.indexOfFirst { sameDay(it.date, Date()) }
            binding.table.getChildAt(index)?.let { row ->
                binding.scroll.scrollTo(0, maxOf(0, row.top - row.height * 2))
            }
        } else binding.scroll.scrollTo(0, 0)
    }

    /** The other calendar's months across this one: "Rabiʻ II – Jumada I 1448 AH". */
    private fun otherCalendarSpan(): String {
        val pattern = if (month.hijri) "MMMM y" else "MMMM y G"
        val first = MonthTimetable.format(days.first().date, !month.hijri, pattern)
        val last = MonthTimetable.format(days.last().date, !month.hijri, pattern)
        return if (first == last) first else "$first – $last"
    }

    /** Day, then the other calendar's day, then the six times. */
    private fun columns(): List<String> = listOf(
        getString(R.string.month_col_day),
        getString(if (month.hijri) R.string.month_col_date else R.string.month_col_hijri),
    ) + Slot.entries.map { getString(it.labelRes).removePrefix("☀️ ") }

    private fun fillHeader() {
        binding.header.removeAllViews()
        columns().forEachIndexed { i, label ->
            binding.header.addView(cell(label, i, 10f, getColor(R.color.accent)))
        }
    }

    private fun cell(value: String, column: Int, sizeSp: Float, color: Int) =
        TextView(this).apply {
            text = value
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[column]
            )
        }

    private fun fillTable() {
        binding.table.removeAllViews()
        // The hint speaks up until the user has written a note; after that it
        // only needs to be there, not to be noticed.
        val hintColor = getColor(if (prefs.hasNotes()) R.color.text_dim else R.color.accent)
        binding.noteHint.setTextColor(hintColor)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            binding.noteHint, android.content.res.ColorStateList.valueOf(hintColor)
        )
        days.forEach { day ->
            val row = layoutInflater.inflate(R.layout.item_month_day, binding.table, false)
            // Today first, then Fridays, then the banding. The row you want is
            // usually today's, and it has to be findable without reading a
            // single date.
            val today = sameDay(day.date, Date())
            when {
                today -> row.setBackgroundColor(getColor(R.color.today_row))
                isFriday(day.date) -> row.setBackgroundColor(getColor(R.color.friday))
                day.dayOfMonth % 2 == 0 -> row.setBackgroundColor(getColor(R.color.surface))
            }
            // On the highlighted row the dim greys and the accent both lose
            // their contrast, so everything in it goes to one bright colour.
            fun color(normal: Int) = getColor(if (today) R.color.today_text else normal)
            val cells = row.findViewById<LinearLayout>(R.id.cells)
            cells.addView(cell(day.dayOfMonth.toString(), 0, 12f, color(R.color.text_dim)))
            cells.addView(cell(
                if (month.hijri) dateFormat.format(day.date) else day.hijriDay.toString(),
                1, 12f, color(R.color.text_dim),
            ))
            Slot.entries.forEachIndexed { i, slot ->
                cells.addView(cell(timeFormat.format(day.times.getValue(slot)), i + 2, 12f, color(R.color.text)))
            }
            // Under the day: in a Gregorian month the start of a Hijri one,
            // and in either the day's occasions.
            row.findViewById<TextView>(R.id.monthStart).apply {
                val marks = buildList {
                    if (!month.hijri && day.hijriDay == 1) {
                        add(MonthTimetable.format(day.date, true, "d MMMM y G"))
                    }
                    day.occasions.forEach { add(getString(it)) }
                }
                if (marks.isNotEmpty()) {
                    text = marks.joinToString(" · ")
                    visibility = View.VISIBLE
                    setTextColor(color(R.color.accent))
                }
            }
            row.findViewById<TextView>(R.id.note).apply {
                val note = prefs.note(day.date)
                text = note
                visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
                setTextColor(color(R.color.sunrise))
            }
            row.setOnClickListener { editNote(day) }
            binding.table.addView(row)
        }
    }

    /**
     * Any month, in the calendar being shown. Android's own date picker only
     * knows the Gregorian calendar - in the Hijri view it offered October, not
     * Jumada - and its large date heading was more than choosing a month needs.
     */
    private fun pickMonth() {
        val hijri = month.hijri
        val thisYear = MonthTimetable.containing(Date(), hijri).year
        val monthPicker = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = 11
            displayedValues = Array(12) { MonthTimetable.monthName(Month(hijri, month.year, it)) }
            value = month.month
            wrapSelectorWheel = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val yearPicker = android.widget.NumberPicker(this).apply {
            minValue = thisYear - YEARS_AROUND
            maxValue = thisYear + YEARS_AROUND
            value = month.year.coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val gap = (resources.displayMetrics.density * 24).toInt()
        val pickers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, gap / 2, 0, 0)
            addView(monthPicker, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gap })
            addView(yearPicker)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.month_pick)
            .setView(pickers)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                show(Month(hijri, yearPicker.value, monthPicker.value))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A day's note: who is coming for iftar, the night to be at the mosque,
     * whatever the user wants to remember against that date. Saving an empty
     * note removes it.
     */
    private fun editNote(day: TimetableDay) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val input = android.widget.EditText(this).apply {
            setText(prefs.note(day.date))
            setSelection(text.length)
            minLines = 2
            maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(NOTE_MAX))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(longDayFormat.format(day.date))
            .setMessage(MonthTimetable.format(day.date, true, "d MMMM y G"))
            .setView(frame)
            .setPositiveButton(R.string.month_note_save) { _, _ ->
                prefs.setNote(day.date, input.text.toString())
                fillTable()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (prefs.note(day.date).isNotEmpty()) {
            dialog.setNeutralButton(R.string.month_note_delete) { _, _ ->
                prefs.setNote(day.date, "")
                fillTable()
            }
        }
        dialog.show().window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        input.requestFocus()
    }

    /** This month's notes, in order. */
    private fun notes(): List<Pair<TimetableDay, String>> =
        days.map { it to prefs.note(it.date) }.filter { it.second.isNotEmpty() }

    private fun chooseFormat() {
        val options = arrayOf(
            getString(R.string.month_share_pdf),
            getString(R.string.month_share_image),
            getString(R.string.month_share_csv),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.month_share)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> share("pdf", "application/pdf") { exporter().writePdf(it) }
                    1 -> share("png", "image/png") { exporter().writePng(it) }
                    else -> share("csv", "text/csv") { it.writeText(csv()) }
                }
            }
            .show()
    }

    private fun exporter() = MonthExport(
        this, days, month.hijri, binding.title.text.toString(),
        binding.subtitle.text.toString(), notes(),
    )

    /** UTF-8 with a BOM, or Excel misreads an Arabic city name. */
    private fun csv(): String = buildString {
        val full = SimpleDateFormat(getString(R.string.time_pattern), Locale.getDefault())
        val date = SimpleDateFormat("d/M/yyyy", Locale.getDefault())
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault())
        append('﻿')
        append(
            (listOf(
                getString(R.string.month_col_date),
                getString(R.string.month_col_weekday),
                getString(R.string.month_col_hijri),
            ) + Slot.entries.map { getString(it.labelRes).removePrefix("☀️ ") } +
                getString(R.string.month_notes)).joinToString(",")
        )
        append('\n')
        days.forEach { day ->
            append(
                (listOf(
                    date.format(day.date),
                    weekday.format(day.date),
                    MonthTimetable.format(day.date, true, "d MMMM y"),
                ) + Slot.entries.map { full.format(day.times.getValue(it)) } +
                    csvField(prefs.note(day.date))).joinToString(",")
            )
            append('\n')
        }
    }

    /** Quoted, so a comma or a line break in a note stays inside its cell. */
    private fun csvField(value: String): String =
        if (value.isEmpty()) "" else "\"" + value.replace("\"", "\"\"") + "\""

    /**
     * Written into cacheDir and handed out through the existing FileProvider,
     * so nothing is dropped into shared storage and no storage permission is
     * involved.
     */
    private fun share(extension: String, mime: String, write: (File) -> Unit) {
        val shared = runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val name = "prayer-times-${month.year}-${month.month + 1}" + if (month.hijri) "-hijri" else ""
            val file = File(dir, "$name.$extension")
            write(file)
            androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
        }.getOrElse {
            android.util.Log.e("MonthActivity", "could not write the $extension timetable", it)
            toast(R.string.month_save_failed)
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shared)
            putExtra(Intent.EXTRA_SUBJECT, binding.title.text.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.month_share)))
        }.onFailure { toast(R.string.month_save_failed) }
    }

    private fun sameDay(a: Date, b: Date): Boolean {
        val one = java.util.Calendar.getInstance().apply { time = a }
        val two = java.util.Calendar.getInstance().apply { time = b }
        return one.get(java.util.Calendar.YEAR) == two.get(java.util.Calendar.YEAR) &&
            one.get(java.util.Calendar.DAY_OF_YEAR) == two.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun isFriday(date: Date) = java.util.Calendar.getInstance()
        .apply { time = date }
        .get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_RAMADAN_YEAR = "ramadan_year"
        private const val KEY_HIJRI = "hijri"
        private const val KEY_YEAR = "year"
        private const val KEY_MONTH = "month"
        private const val NOTE_MAX = 200
        /** How far the month picker reaches either side of this year. */
        private const val YEARS_AROUND = 50

        /** Day narrow, the other calendar's day a little wider, the six times equal. */
        private val WEIGHTS = floatArrayOf(0.75f, 1.05f, 1.3f, 1.3f, 1.3f, 1.3f, 1.3f, 1.3f)

        /** This month. */
        fun intent(context: Context): Intent = Intent(context, MonthActivity::class.java)

        /** Ramadan of [hijriYear], in the Hijri view. */
        fun ramadan(context: Context, hijriYear: Int): Intent =
            intent(context).putExtra(EXTRA_RAMADAN_YEAR, hijriYear)
    }
}
