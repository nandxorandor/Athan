package com.ahmedkhalaf.athan

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.Prayer
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.util.Calendar
import java.util.Date

/**
 * Everything shown in the daily list, in order. Sunrise is displayed because it
 * marks the end of Fajr's window, but it is not a prayer and must never raise
 * an athan — hence [notifies].
 */
enum class Slot(val prayer: Prayer, val labelRes: Int, val notifies: Boolean = true) {
    FAJR(Prayer.FAJR, R.string.fajr),
    SUNRISE(Prayer.SUNRISE, R.string.sunrise, notifies = false),
    DHUHR(Prayer.DHUHR, R.string.dhuhr),
    ASR(Prayer.ASR, R.string.asr),
    MAGHRIB(Prayer.MAGHRIB, R.string.maghrib),
    ISHA(Prayer.ISHA, R.string.isha);

    companion object {
        fun of(prayer: Prayer): Slot? = entries.firstOrNull { it.prayer == prayer }
    }
}

data class Upcoming(val slot: Slot, val time: Date)

/**
 * Thin wrapper over the adhan library. Everything is computed on device from
 * latitude/longitude — no network call, so prayer times work on a plane or in
 * a basement.
 */
class PrayerEngine(private val prefs: Prefs) {

    private fun params(): CalculationParameters {
        val method = runCatching { CalculationMethod.valueOf(prefs.method) }
            .getOrDefault(CalculationMethod.MUSLIM_WORLD_LEAGUE)
        return method.parameters.apply {
            madhab = runCatching { Madhab.valueOf(prefs.madhab) }.getOrDefault(Madhab.SHAFI)
        }
    }

    fun timesOn(date: Date): PrayerTimes? {
        if (!prefs.hasLocation) return null
        val coordinates = Coordinates(prefs.latitude, prefs.longitude)
        return PrayerTimes(coordinates, DateComponents.from(date), params())
    }

    /** Applies the user's manual offset. One place, so display and alarms agree. */
    private fun shift(date: Date?): Date? =
        date?.let { Date(it.time + prefs.adjustmentMinutes * 60_000L) }

    /** Today's times including sunrise, in order. Empty with no location set. */
    fun today(): List<Pair<Slot, Date>> {
        val times = timesOn(Date()) ?: return emptyList()
        return Slot.entries.mapNotNull { slot ->
            shift(times.timeForPrayer(slot.prayer))?.let { slot to it }
        }
    }

    /**
     * The next prayer strictly after [now]. Rolls into tomorrow's Fajr once
     * Isha has passed, which is the case every single night — the reason this
     * cannot simply scan today's list.
     */
    fun next(now: Date = Date()): Upcoming? {
        val todayTimes = timesOn(now) ?: return null
        Slot.entries.filter { it.notifies }.forEach { slot ->
            val t = shift(todayTimes.timeForPrayer(slot.prayer))
            if (t != null && t.after(now)) return Upcoming(slot, t)
        }
        val tomorrow = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, 1)
        }.time
        val next = timesOn(tomorrow) ?: return null
        val fajr = shift(next.timeForPrayer(Prayer.FAJR)) ?: return null
        return Upcoming(Slot.FAJR, fajr)
    }
}
