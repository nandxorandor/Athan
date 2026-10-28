package com.ahmedkhalaf.athan

import android.content.Context
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.PrayerAdjustments

/**
 * Every calculation method the app offers: the adhan library's own, plus the
 * regional ones it does not ship, and which one a country uses.
 *
 * The regional methods are their authorities' published angles and minute
 * offsets, as other prayer-time libraries carry them (ITL, adhan-dart, mawaqit):
 * Morocco 19°/17° with Dhuhr and Maghrib +5; Algeria 18°/17° with Maghrib +3;
 * Tunisia 18°/18°; Turkey (Diyanet) 18°/17° with sunrise -7, Dhuhr +5, Asr +4,
 * Maghrib +7. Checked against the Aladhan service for Casablanca, Algiers, Tunis
 * and Istanbul: equal to the minute except Algeria's Maghrib, where Aladhan
 * leaves out the +3 - kept, since where the sources disagree the later Maghrib
 * is the one that can never end a fast early. A mosque that differs by a minute
 * is what the per-prayer adjustment is for.
 */
object Methods {

    private class Regional(val fajr: Double, val isha: Double, val offsets: PrayerAdjustments)

    // PrayerAdjustments(fajr, sunrise, dhuhr, asr, maghrib, isha)
    private val regional = linkedMapOf(
        "MOROCCO" to Regional(19.0, 17.0, PrayerAdjustments(0, 0, 5, 0, 5, 0)),
        "ALGERIA" to Regional(18.0, 17.0, PrayerAdjustments(0, 0, 0, 0, 3, 0)),
        "TUNISIA" to Regional(18.0, 18.0, PrayerAdjustments()),
        "TURKEY" to Regional(18.0, 17.0, PrayerAdjustments(0, -7, 5, 4, 7, 0)),
    )

    /**
     * The picker's list: the library's methods, then the regional ones. The
     * library's OTHER is left out - it has no angles, so it computes nothing
     * anyone would recognise - but a stored OTHER still resolves below.
     */
    val all: List<String> =
        CalculationMethod.values().map { it.name }.filter { it != "OTHER" } + regional.keys

    /** A fresh parameter set for [name]; Muslim World League if it is unknown. */
    fun parameters(name: String): CalculationParameters {
        regional[name]?.let { r ->
            return CalculationParameters(r.fajr, r.isha).withMethodAdjustments(r.offsets)
        }
        val method = runCatching { CalculationMethod.valueOf(name) }
            .getOrDefault(CalculationMethod.MUSLIM_WORLD_LEAGUE)
        return method.parameters
    }

    /**
     * The method a country's own authority uses, by ISO country code; Muslim
     * World League, the common international default, everywhere else.
     */
    fun forCountry(countryCode: String?): String = when (countryCode?.uppercase()) {
        "SA" -> "UMM_AL_QURA"
        "AE" -> "DUBAI"
        "KW" -> "KUWAIT"
        "QA" -> "QATAR"
        "EG", "SD", "SY", "LB", "IQ", "LY" -> "EGYPTIAN"
        "PK", "IN", "BD", "AF" -> "KARACHI"
        "US", "CA" -> "NORTH_AMERICA"
        "SG", "MY", "BN", "ID" -> "SINGAPORE"
        "MA" -> "MOROCCO"
        "DZ" -> "ALGERIA"
        "TN" -> "TUNISIA"
        "TR" -> "TURKEY"
        else -> "MUSLIM_WORLD_LEAGUE"
    }

    fun label(context: Context, name: String): String = when (name) {
        "NORTH_AMERICA" -> context.getString(R.string.method_north_america)
        "MUSLIM_WORLD_LEAGUE" -> context.getString(R.string.method_mwl)
        "EGYPTIAN" -> context.getString(R.string.method_egyptian)
        "KARACHI" -> context.getString(R.string.method_karachi)
        "UMM_AL_QURA" -> context.getString(R.string.method_umm_al_qura)
        "DUBAI" -> context.getString(R.string.method_dubai)
        "MOON_SIGHTING_COMMITTEE" -> context.getString(R.string.method_moonsighting)
        "KUWAIT" -> context.getString(R.string.method_kuwait)
        "QATAR" -> context.getString(R.string.method_qatar)
        "SINGAPORE" -> context.getString(R.string.method_singapore)
        "MOROCCO" -> context.getString(R.string.method_morocco)
        "ALGERIA" -> context.getString(R.string.method_algeria)
        "TUNISIA" -> context.getString(R.string.method_tunisia)
        "TURKEY" -> context.getString(R.string.method_turkey)
        "OTHER" -> context.getString(R.string.method_other)
        // A method the library added since this list was written: better a
        // readable English name than a raw SCREAMING_CASE constant.
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
