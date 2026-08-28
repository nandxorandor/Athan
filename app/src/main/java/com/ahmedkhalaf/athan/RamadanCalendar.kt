package com.ahmedkhalaf.athan

import android.icu.util.Calendar as IcuCalendar
import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import java.util.Calendar
import java.util.Date

/** One day of Ramadan, with the times people actually plan around. */
data class RamadanDay(
    val dayOfRamadan: Int,
    val date: Date,
    val fajr: Date,
    val sunrise: Date,
    val dhuhr: Date,
    val asr: Date,
    val maghrib: Date,
    val isha: Date,
)

/**
 * The whole month in one table. Ramadan is the one time of year when people
 * want every day at once rather than just today's — the fast is planned around
 * suhoor and iftar the night before — so this exists to be looked at as a
 * month, and shared, not scrolled a day at a time.
 *
 * Ported from the Windows app, which built it first. Both use the Umm al-Qura
 * civil calendar so the two agree on which Gregorian day is 1 Ramadan; here
 * that comes from `android.icu`, which ships with the platform, rather than
 * from a table of our own that would need maintaining every year.
 */
object RamadanCalendar {

    /** Ramadan is the ninth month. */
    private const val RAMADAN = 8 // android.icu months are 0-based

    private fun hijri() = IslamicCalendar(ULocale.ROOT).apply {
        // Umm al-Qura: the calculated calendar Saudi Arabia publishes, and the
        // same one Windows uses, so the phone and the PC never disagree.
        setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA)
    }

    fun currentHijriYear(): Int = runCatching {
        hijri().apply { time = Date() }.get(IcuCalendar.YEAR)
    }.getOrDefault(0)

    /** The Gregorian date on which 1 Ramadan of [hijriYear] falls. */
    fun firstDay(hijriYear: Int): Date? = runCatching {
        hijri().apply {
            clear()
            set(hijriYear, RAMADAN, 1)
        }.time
    }.getOrNull()

    fun daysIn(hijriYear: Int): Int = runCatching {
        hijri().apply {
            clear()
            set(hijriYear, RAMADAN, 1)
        }.getActualMaximum(IcuCalendar.DAY_OF_MONTH)
    }.getOrDefault(30)

    /**
     * The Hijri year whose Ramadan is worth offering now: this year's if it has
     * not finished, otherwise next year's. 0 when unavailable.
     */
    fun upcomingHijriYear(): Int {
        val year = currentHijriYear()
        if (year == 0) return 0
        val first = firstDay(year) ?: return 0
        val last = midnight(first).apply { add(Calendar.DAY_OF_YEAR, daysIn(year) - 1) }
        return if (midnight(Date()).after(last)) year + 1 else year
    }

    /**
     * True inside the window where the offer is welcome rather than noise:
     * from [leadDays] before the first fast until the last day of the month.
     */
    fun isSeason(hijriYear: Int, leadDays: Int = 14): Boolean {
        val first = firstDay(hijriYear) ?: return false
        val opens = midnight(first).apply { add(Calendar.DAY_OF_YEAR, -leadDays) }
        val closes = midnight(first).apply { add(Calendar.DAY_OF_YEAR, daysIn(hijriYear) - 1) }
        val today = midnight(Date())
        return !today.before(opens) && !today.after(closes)
    }

    /** Every day of the month, computed with the user's own settings. */
    fun build(hijriYear: Int, prefs: Prefs): List<RamadanDay> {
        val first = firstDay(hijriYear) ?: return emptyList()
        if (!prefs.hasLocation) return emptyList()

        val engine = PrayerEngine(prefs)
        return (0 until daysIn(hijriYear)).mapNotNull { offset ->
            val date = midnight(first).apply { add(Calendar.DAY_OF_YEAR, offset) }.time
            val times = engine.on(date).toMap()
            if (times.size < Slot.entries.size) return@mapNotNull null
            RamadanDay(
                dayOfRamadan = offset + 1,
                date = date,
                fajr = times.getValue(Slot.FAJR),
                sunrise = times.getValue(Slot.SUNRISE),
                dhuhr = times.getValue(Slot.DHUHR),
                asr = times.getValue(Slot.ASR),
                maghrib = times.getValue(Slot.MAGHRIB),
                isha = times.getValue(Slot.ISHA),
            )
        }
    }

    private fun midnight(date: Date): Calendar = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
