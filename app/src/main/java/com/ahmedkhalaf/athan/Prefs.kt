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

    /**
     * "en" or "ar"; empty until the user picks one, which is what lets a fresh
     * install follow the phone's own language instead of guessing English.
     */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    /**
     * The temperature on the home screen. On by default, and the one switch
     * that takes the app back to making no network requests at all.
     */
    var weatherEnabled: Boolean
        get() = sp.getBoolean(KEY_WEATHER, true)
        set(v) = sp.edit().putBoolean(KEY_WEATHER, v).apply()

    /**
     * Whether the first-run notice about the temperature has been shown. It is
     * asked once, before any coordinates leave the device.
     */
    var weatherNoticeSeen: Boolean
        get() = sp.getBoolean(KEY_WEATHER_NOTICE, false)
        set(v) = sp.edit().putBoolean(KEY_WEATHER_NOTICE, v).apply()

    /**
     * Fahrenheit rather than Celsius. Celsius is the default everywhere, by
     * request: this used to key off the phone's region, which meant a US phone
     * could not be given a Celsius default at all. Anyone who wants Fahrenheit
     * sets it once, from Settings or by tapping the reading on the home screen.
     */
    var fahrenheit: Boolean
        get() = sp.getBoolean(KEY_FAHRENHEIT, false)
        set(v) = sp.edit().putBoolean(KEY_FAHRENHEIT, v).apply()

    /** Offer the month's timetable as Ramadan comes round. */
    var ramadanPromptEnabled: Boolean
        get() = sp.getBoolean(KEY_RAMADAN_PROMPT, true)
        set(v) = sp.edit().putBoolean(KEY_RAMADAN_PROMPT, v).apply()

    /**
     * The Hijri year whose offer was dismissed. A year rather than a flag, so
     * "not this year" lapses on its own next Ramadan instead of switching the
     * feature off for good.
     */
    var ramadanPromptDismissedYear: Int
        get() = sp.getInt(KEY_RAMADAN_YEAR, 0)
        set(v) = sp.edit().putInt(KEY_RAMADAN_YEAR, v).apply()

    /**
     * The du'aa said after the athan. On by default: it is the natural
     * companion to the call, and one toggle away for anyone who would rather
     * it did not play.
     */
    var afterAthanDua: Boolean
        get() = sp.getBoolean(KEY_AFTER_DUA, true)
        set(v) = sp.edit().putBoolean(KEY_AFTER_DUA, v).apply()

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

    /**
     * How many minutes before the prayer the heads-up fires, [MIN_REMINDER] to
     * [MAX_REMINDER]. Was a three-way 5/10/15 picker; a slider covers the same
     * ground and everything between, without a wider default changing for
     * anyone already set.
     */
    var reminderMinutes: Int
        get() = sp.getInt(KEY_REMINDER_MIN, 10).coerceIn(MIN_REMINDER, MAX_REMINDER)
        set(v) = sp.edit().putInt(KEY_REMINDER_MIN, v.coerceIn(MIN_REMINDER, MAX_REMINDER)).apply()

    /** Reminder sound: a "content://" ringtone URI, or "" for the default. */
    var reminderSound: String
        get() = sp.getString(KEY_REMINDER_SOUND, "") ?: ""
        set(v) = sp.edit().putString(KEY_REMINDER_SOUND, v).apply()

    /** Vibrate instead of sound. Mutually exclusive with sound; sound is default. */
    var reminderVibrate: Boolean
        get() = sp.getBoolean(KEY_REMINDER_VIB, false)
        set(v) = sp.edit().putBoolean(KEY_REMINDER_VIB, v).apply()

    /**
     * The heads-up's own loudness, 0-100, deliberately separate from [volume].
     * The two serve opposite purposes: the athan is meant to carry across a
     * room, while the heads-up only has to be noticed by someone holding the
     * phone. Sharing one slider forced a compromise that suited neither.
     * Defaults to 70 rather than 100 for the same reason.
     */
    var reminderVolume: Int
        get() = sp.getInt(KEY_REMINDER_VOL, 70)
        set(v) = sp.edit().putInt(KEY_REMINDER_VOL, v.coerceIn(0, 100)).apply()

    /**
     * Whether we have already offered to watch the Downloads folder. Asked once,
     * on the first source link tap; a refusal must not re-prompt on every tap.
     */
    var downloadWatchAsked: Boolean
        get() = sp.getBoolean(KEY_WATCH_ASKED, false)
        set(v) = sp.edit().putBoolean(KEY_WATCH_ASKED, v).apply()

    /**
     * The three below are persisted rather than held in the activity because the
     * whole point is to survive the app being killed while the user browses.
     */

    /** Downloads-folder audio as it looked when the browser was handed the link. */
    var downloadSnapshot: Set<String>
        get() = sp.getStringSet(KEY_DL_SNAPSHOT, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_DL_SNAPSHOT, v).apply()

    /** Arrivals the user has not been told about in the app yet. */
    var downloadPending: Set<String>
        get() = sp.getStringSet(KEY_DL_PENDING, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_DL_PENDING, v).apply()

    /** Whether a banner was raised for [downloadPending]. */
    var downloadBannerPosted: Boolean
        get() = sp.getBoolean(KEY_DL_BANNER, false)
        set(v) = sp.edit().putBoolean(KEY_DL_BANNER, v).apply()

    /** When the current watch began; 0 means not watching. */
    var downloadWatchStartedAt: Long
        get() = sp.getLong(KEY_DL_STARTED, 0L)
        set(v) = sp.edit().putLong(KEY_DL_STARTED, v).apply()

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

    companion object {
        /** The heads-up slider's bounds. Zero would mean "at the prayer". */
        const val MIN_REMINDER = 1
        const val MAX_REMINDER = 60

        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_CITY = "city"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_WEATHER = "weather_enabled"
        private const val KEY_WEATHER_NOTICE = "weather_notice_seen"
        private const val KEY_FAHRENHEIT = "fahrenheit"
        private const val KEY_RAMADAN_PROMPT = "ramadan_prompt"
        private const val KEY_RAMADAN_YEAR = "ramadan_prompt_year"
        private const val KEY_AFTER_DUA = "after_athan_dua"
        private const val KEY_METHOD = "method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_QIBLA_NOTICE = "qibla_notice_seen"
        private const val KEY_WATCH_ASKED = "download_watch_asked"
        private const val KEY_DL_SNAPSHOT = "download_snapshot"
        private const val KEY_DL_PENDING = "download_pending"
        private const val KEY_DL_BANNER = "download_banner_posted"
        private const val KEY_DL_STARTED = "download_watch_started"
        private const val KEY_ADJUST = "adjust_minutes"
        private const val KEY_MODE = "mode"
        private const val KEY_VOLUME = "volume"
        private const val KEY_REMINDER_ON = "reminder_on"
        private const val KEY_REMINDER_MIN = "reminder_min"
        private const val KEY_REMINDER_SOUND = "reminder_sound"
        private const val KEY_REMINDER_VIB = "reminder_vib"
        private const val KEY_REMINDER_VOL = "reminder_volume"
        private const val KEY_FAJR_SOUND = "fajr_sound"
        private const val KEY_OTHER_SOUND = "other_sound"
        private val NO_LOCATION = Double.NaN
    }
}
