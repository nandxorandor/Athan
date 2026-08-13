package com.ahmedkhalaf.athan

import android.content.Context

/** How the athan announces itself. */
enum class AthanMode { SOUND, VIBRATE, SILENT }

/**
 * All persisted state. SharedPreferences rather than DataStore: every read here
 * happens on a BroadcastReceiver or Service where a blocking read of a handful
 * of primitives is simpler and safer than a coroutine.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("athan", Context.MODE_PRIVATE)

    var latitude: Double
        get() = Double.fromBits(sp.getLong(KEY_LAT, NO_LOCATION.toRawBits()))
        set(v) = sp.edit().putLong(KEY_LAT, v.toRawBits()).apply()

    var longitude: Double
        get() = Double.fromBits(sp.getLong(KEY_LNG, NO_LOCATION.toRawBits()))
        set(v) = sp.edit().putLong(KEY_LNG, v.toRawBits()).apply()

    var cityName: String
        get() = sp.getString(KEY_CITY, "") ?: ""
        set(v) = sp.edit().putString(KEY_CITY, v).apply()

    val hasLocation: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN()

    /**
     * North America (ISNA, 15°/15°) rather than Muslim World League (18°/17°):
     * MWL puts Fajr 20 minutes early and Isha 12 minutes late at this latitude,
     * against what local timetables publish.
     */
    var method: String
        get() = sp.getString(KEY_METHOD, "NORTH_AMERICA") ?: "NORTH_AMERICA"
        set(v) = sp.edit().putString(KEY_METHOD, v).apply()

    /**
     * Manual shift applied to every computed time, in minutes. Some countries
     * move the clock seasonally without the astronomy changing, and local
     * timetables are often rounded or deliberately offset from the calculation.
     */
    var adjustmentMinutes: Int
        get() = sp.getInt(KEY_ADJUST, 0)
        set(v) = sp.edit().putInt(KEY_ADJUST, v).apply()

    /**
     * Athan loudness, 0–100, applied as a per-player scalar via
     * MediaPlayer.setVolume — so it scales the app's own output only and never
     * touches the phone's system volume.
     */
    var volume: Int
        get() = sp.getInt(KEY_VOLUME, 100)
        set(v) = sp.edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()

    /** A "get ready" popup this many minutes before each prayer. */
    var reminderEnabled: Boolean
        get() = sp.getBoolean(KEY_REMINDER_ON, false)
        set(v) = sp.edit().putBoolean(KEY_REMINDER_ON, v).apply()

    /** How many minutes before the prayer the heads-up fires. */
    var reminderMinutes: Int
        get() = sp.getInt(KEY_REMINDER_MIN, 10)
        set(v) = sp.edit().putInt(KEY_REMINDER_MIN, v).apply()

    /** Reminder sound: a "content://" ringtone URI, or "" for the default. */
    var reminderSound: String
        get() = sp.getString(KEY_REMINDER_SOUND, "") ?: ""
        set(v) = sp.edit().putString(KEY_REMINDER_SOUND, v).apply()

    /** Vibrate instead of sound. Mutually exclusive with sound; sound is default. */
    var reminderVibrate: Boolean
        get() = sp.getBoolean(KEY_REMINDER_VIB, false)
        set(v) = sp.edit().putBoolean(KEY_REMINDER_VIB, v).apply()

    /** First-run compass notice; dismissed permanently by the checkbox. */
    var qiblaNoticeSeen: Boolean
        get() = sp.getBoolean(KEY_QIBLA_NOTICE, false)
        set(v) = sp.edit().putBoolean(KEY_QIBLA_NOTICE, v).apply()

    var madhab: String
        get() = sp.getString(KEY_MADHAB, "SHAFI") ?: "SHAFI"
        set(v) = sp.edit().putString(KEY_MADHAB, v).apply()

    /**
     * Per prayer, not global: Fajr is the one people most often want silent or
     * vibrate-only while still wanting the athan aloud during the day.
     */
    fun modeFor(slot: Slot): AthanMode =
        runCatching { AthanMode.valueOf(sp.getString(keyMode(slot), null) ?: "") }
            .getOrDefault(AthanMode.SOUND)

    fun setMode(slot: Slot, mode: AthanMode) =
        sp.edit().putString(keyMode(slot), mode.name).apply()

    private fun keyMode(slot: Slot) = "${KEY_MODE}_${slot.name}"

    /**
     * Fajr has its own recording: its adhan carries an extra line. Stored as a
     * bare asset path with no default — the catalogue is built at runtime, so
     * only it can say what a sensible fallback is. See AthanCatalog.resolve*.
     */
    var fajrSound: String
        get() = sp.getString(KEY_FAJR_SOUND, "") ?: ""
        set(v) = sp.edit().putString(KEY_FAJR_SOUND, v).apply()

    var otherSound: String
        get() = sp.getString(KEY_OTHER_SOUND, "") ?: ""
        set(v) = sp.edit().putString(KEY_OTHER_SOUND, v).apply()

    private companion object {
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val KEY_CITY = "city"
        const val KEY_METHOD = "method"
        const val KEY_MADHAB = "madhab"
        const val KEY_QIBLA_NOTICE = "qibla_notice_seen"
        const val KEY_ADJUST = "adjust_minutes"
        const val KEY_MODE = "mode"
        const val KEY_VOLUME = "volume"
        const val KEY_REMINDER_ON = "reminder_on"
        const val KEY_REMINDER_MIN = "reminder_min"
        const val KEY_REMINDER_SOUND = "reminder_sound"
        const val KEY_REMINDER_VIB = "reminder_vib"
        const val KEY_FAJR_SOUND = "fajr_sound"
        const val KEY_OTHER_SOUND = "other_sound"
        val NO_LOCATION = Double.NaN
    }
}
