package com.ahmedkhalaf.athan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityRamadanBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The month's timetable. The Windows app writes this to a one-page Word
 * document to be printed and stuck on a fridge; a phone has no printer, so here
 * the screen itself is the artefact and sharing hands out a CSV — which opens
 * in any spreadsheet, and is what someone forwarding it to family can actually
 * use.
 */
class RamadanActivity : LocalizedActivity() {

    private lateinit var binding: ActivityRamadanBinding
    private lateinit var prefs: Prefs
    private var days: List<RamadanDay> = emptyList()
    private var hijriYear = 0

    private val timeFormat by lazy {
        SimpleDateFormat(getString(R.string.time_pattern), Locale.getDefault())
    }
    /**
     * Just the date. The weekday and the date together do not fit a six-column
     * table on a phone, and between the two it is the date a fasting timetable
     * is actually read by — Friday is visible from the shading anyway.
     */
    private val dayFormat by lazy { SimpleDateFormat("d/M", Locale.getDefault()) }

    /** The long form, for the subtitle and the shared file where there is room. */
    private val longDayFormat by lazy { SimpleDateFormat("d MMMM", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRamadanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Added to the layout's own padding, not substituted for it: every
        // other screen keeps its padding on an inner view, but this one is the
        // root, and replacing it wiped the margin the table needs — the day
        // number and the title were being clipped at the screen edge.
        val padded = binding.root
        val basePadding = resources.displayMetrics.density.times(14).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(padded) { view, insets ->
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
        hijriYear = intent.getIntExtra(EXTRA_YEAR, 0)
            .takeIf { it > 0 } ?: RamadanCalendar.upcomingHijriYear()

        binding.title.text = getString(R.string.ramadan_title, hijriYear)
        binding.shareButton.setOnClickListener { share() }

        days = RamadanCalendar.build(hijriYear, prefs)
        if (days.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.shareButton.visibility = View.GONE
            binding.subtitle.visibility = View.GONE
            return
        }
        binding.subtitle.text = getString(
            R.string.ramadan_subtitle,
            prefs.cityName.ifBlank { getString(R.string.location_set) },
            "${longDayFormat.format(days.first().date)} – ${longDayFormat.format(days.last().date)}"
        )
        fillTable()
    }

    private fun fillTable() {
        days.forEach { day ->
            val row = layoutInflater.inflate(R.layout.item_ramadan, binding.table, false)
            // Today first, then Fridays, then the banding. During the month the
            // row you want is today's, and it has to be findable without
            // reading a single date.
            val today = sameDay(day.date, Date())
            val friday = java.util.Calendar.getInstance()
                .apply { time = day.date }
                .get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY
            when {
                today -> row.setBackgroundColor(getColor(R.color.today_row))
                friday -> row.setBackgroundColor(getColor(R.color.friday))
                day.dayOfRamadan % 2 == 0 -> row.setBackgroundColor(getColor(R.color.surface))
            }
            row.findViewById<TextView>(R.id.ramadanDay).text = day.dayOfRamadan.toString()
            row.findViewById<TextView>(R.id.ramadanDate).text = dayFormat.format(day.date)
            row.findViewById<TextView>(R.id.ramadanSuhoor).text = timeFormat.format(day.fajr)
            row.findViewById<TextView>(R.id.ramadanDhuhr).text = timeFormat.format(day.dhuhr)
            row.findViewById<TextView>(R.id.ramadanAsr).text = timeFormat.format(day.asr)
            row.findViewById<TextView>(R.id.ramadanIftar).text = timeFormat.format(day.maghrib)
            // On the highlighted row the dim greys and the accent both lose
            // their contrast, so everything in it goes to one bright colour.
            if (today) {
                listOf(
                    R.id.ramadanDay, R.id.ramadanDate, R.id.ramadanSuhoor,
                    R.id.ramadanDhuhr, R.id.ramadanAsr, R.id.ramadanIftar,
                ).forEach { id ->
                    row.findViewById<TextView>(id).setTextColor(getColor(R.color.today_text))
                }
            }
            binding.table.addView(row)
        }
    }

    /**
     * Written into cacheDir and handed out through the existing FileProvider,
     * so nothing is dropped into shared storage and no storage permission is
     * involved. UTF-8 with a BOM, or Excel misreads an Arabic city name.
     */
    private fun share() {
        val csv = buildString {
            append('﻿')
            append(
                listOf(
                    getString(R.string.ramadan_col_day),
                    getString(R.string.ramadan_col_date),
                    getString(R.string.ramadan_col_suhoor),
                    getString(R.string.sunrise).removePrefix("☀️ "),
                    getString(R.string.dhuhr),
                    getString(R.string.asr),
                    getString(R.string.ramadan_col_iftar),
                    getString(R.string.isha),
                ).joinToString(",")
            )
            append('\n')
            days.forEach { day ->
                append(
                    listOf(
                        day.dayOfRamadan.toString(),
                        dayFormat.format(day.date),
                        timeFormat.format(day.fajr),
                        timeFormat.format(day.sunrise),
                        timeFormat.format(day.dhuhr),
                        timeFormat.format(day.asr),
                        timeFormat.format(day.maghrib),
                        timeFormat.format(day.isha),
                    ).joinToString(",")
                )
                append('\n')
            }
        }

        val shared = runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "ramadan-$hijriYear.csv")
            file.writeText(csv)
            androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
        }.getOrElse {
            toast(R.string.ramadan_save_failed)
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, shared)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ramadan_title, hijriYear))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.ramadan_share)))
        }.onFailure { toast(R.string.ramadan_save_failed) }
    }

    private fun sameDay(a: Date, b: Date): Boolean {
        val one = java.util.Calendar.getInstance().apply { time = a }
        val two = java.util.Calendar.getInstance().apply { time = b }
        return one.get(java.util.Calendar.YEAR) == two.get(java.util.Calendar.YEAR) &&
            one.get(java.util.Calendar.DAY_OF_YEAR) == two.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_YEAR = "hijri_year"

        fun intent(context: Context, hijriYear: Int = 0): Intent =
            Intent(context, RamadanActivity::class.java).putExtra(EXTRA_YEAR, hijriYear)
    }
}
