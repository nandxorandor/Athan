package com.ahmedkhalaf.athan

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** The two languages the interface is offered in. */
enum class AppLanguage(val tag: String, private val localeTag: String) {
    ENGLISH("en", "en"),

    /**
     * `nu-latn` forces Western digits — 5:14, not ٥:١٤. Arabic defaults to
     * Arabic-Indic numerals, but the digits in everyday use across most of the
     * Arabic-speaking world (and in every clock, timetable and phone UI beside
     * this one) are the Western forms, so times and dates use them.
     *
     * Resource lookup only reads the language, so `values-ar` still resolves;
     * the extension changes how numbers are formatted, not which file is read.
     */
    ARABIC("ar", "ar-u-nu-latn");

    /** What the formatters and the resource configuration are built from. */
    val locale: Locale get() = Locale.forLanguageTag(localeTag)

    companion object {
        fun of(tag: String): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ENGLISH
    }
}

/**
 * The app's language is the user's choice, not the phone's. Most of the people
 * this app is for read either Arabic or English, and plenty of them run an
 * English phone while wanting the prayer screen in Arabic — so the setting is
 * ours to keep rather than something inherited from the system.
 *
 * Every component that shows text wraps its own base context (see
 * [LocalizedActivity] and the services): resources are resolved from the
 * context they are loaded through, so a single global switch would not reach a
 * notification posted by a service.
 *
 * The adhkar screens follow the language like everything else, but only as far
 * as their headings. The remembrances themselves are Qur'an and hadith: they
 * exist in one language, and there is nothing to translate.
 */
object AppLocale {

    /**
     * The chosen language. Until the user picks one, follow the phone: an
     * Arabic handset should already be in Arabic before its owner finds the
     * switch.
     */
    fun current(context: Context): AppLanguage {
        val stored = Prefs(context).language
        if (stored.isNotEmpty()) return AppLanguage.of(stored)
        return if (systemLanguage(context) == "ar") AppLanguage.ARABIC else AppLanguage.ENGLISH
    }

    fun set(context: Context, language: AppLanguage) {
        Prefs(context).language = language.tag
        // Ahead of the activity being recreated, so date and time formatters
        // built in the new instance's field initialisers already agree.
        Locale.setDefault(language.locale)
    }

    /**
     * Resources in the chosen language. [Locale.setDefault] goes with it
     * because `SimpleDateFormat` and `DateTimeFormatter` read the default
     * locale rather than a context — without it the clock and the Hijri date
     * would stay in the phone's language while everything around them changed.
     */
    fun wrap(base: Context): Context {
        val locale = current(base).locale
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // Arabic mirrors the whole interface; without this the layout keeps
        // the phone's direction and the text sits against the wrong edge.
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun systemLanguage(context: Context): String =
        context.resources.configuration.locales.get(0)?.language.orEmpty()
}

/** Every screen extends this; it is the only thing that applies the language. */
abstract class LocalizedActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }
}
