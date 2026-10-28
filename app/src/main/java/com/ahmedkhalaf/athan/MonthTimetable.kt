package com.ahmedkhalaf.athan

import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.Calendar as IcuCalendar
import android.icu.util.GregorianCalendar
import android.icu.util.ULocale
import java.util.Date
import java.util.Locale

/**
 * A month of either calendar. Gregorian is what the timetable opens on; Hijri
 * is there for the months people keep by it - Ramadan above all. [month] is
 * 0-based, as both calendars count it.
 */
data class Month(val hijri: Boolean, val year: Int, val month: Int)

/** One day of the timetable, with the Hijri day beside it whichever month is shown. */
data class TimetableDay(
    val date: Date,
    val dayOfMonth: Int,
    val hijriDay: Int,
    val times: Map<Slot, Date>,
)

/**
 * Any month's prayer times in one table, to look at as a month and to print.
 * Both calendars go through `android.icu`, the Hijri one as Umm al-Qura - the
 * same as [RamadanCalendar], so the Ramadan offer and this table never
 * disagree on which day is 1 Ramadan.
 */
object MonthTimetable {

    private fun calendar(hijri: Boolean): IcuCalendar =
        if (hijri) RamadanCalendar.hijri() else GregorianCalendar()

    /** Midnight on the month's first day. */
    private fun start(month: Month): IcuCalendar = calendar(month.hijri).apply {
        clear()
        set(month.year, month.month, 1)
    }

    fun containing(date: Date, hijri: Boolean): Month {
        val c = calendar(hijri).apply { time = date }
        return Month(hijri, c.get(IcuCalendar.YEAR), c.get(IcuCalendar.MONTH))
    }

    fun shift(month: Month, by: Int): Month {
        val c = start(month).apply { add(IcuCalendar.MONTH, by) }
        return Month(month.hijri, c.get(IcuCalendar.YEAR), c.get(IcuCalendar.MONTH))
    }

    fun firstDay(month: Month): Date = start(month).time

    fun length(month: Month): Int = start(month).getActualMaximum(IcuCalendar.DAY_OF_MONTH)

    /** The middle of the month: where it lands when switched to the other calendar. */
    fun middle(month: Month): Date =
        start(month).apply { add(IcuCalendar.DAY_OF_MONTH, length(month) / 2) }.time

    /** "October 2026", or "Ramadan 1448 AH". */
    fun title(month: Month): String =
        format(firstDay(month), month.hijri, if (month.hijri) "MMMM y G" else "MMMM y")

    /** A date in the Hijri or the Gregorian calendar, in the app's language. */
    fun format(date: Date, hijri: Boolean, pattern: String): String {
        var locale = ULocale.forLocale(Locale.getDefault())
        if (hijri) locale = locale.setKeywordValue("calendar", "islamic-umalqura")
        return IcuDateFormat(pattern, locale).format(date)
    }

    /** Every day of the month, computed with the user's own settings. */
    fun build(month: Month, prefs: Prefs): List<TimetableDay> {
        if (!prefs.hasLocation) return emptyList()
        val engine = PrayerEngine(prefs)
        val day = start(month)
        val hijri = RamadanCalendar.hijri()
        return (1..length(month)).mapNotNull { n ->
            val date = day.time
            day.add(IcuCalendar.DAY_OF_MONTH, 1)
            val times = engine.on(date).toMap()
            if (times.size < Slot.entries.size) return@mapNotNull null
            hijri.time = date
            TimetableDay(date, n, hijri.get(IcuCalendar.DAY_OF_MONTH), times)
        }
    }
}
