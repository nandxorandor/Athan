package com.ahmedkhalaf.athan

import android.content.Context
import android.util.Log

data class AthanSound(
    /** Asset path, e.g. "athan/egyptian/023.mp3". Stored in prefs. */
    val asset: String,
    val label: String,
    val duration: String,
    /** Folder slug, e.g. "egyptian". Groups the credits screen. */
    val category: String,
    /** Reciter, from the recording's own ID3 title. Blank if untagged. */
    val reciter: String,
    /** Where the recording came from, from its ID3 artist tag. Blank if untagged. */
    val source: String,
)

/**
 * Built by listing assets/athan at runtime rather than from a hardcoded list.
 * Adding recordings is then a matter of re-running tools/sync-audio.ps1 and
 * rebuilding — no Kotlin to edit, and no way for the two to drift apart.
 *
 * The same index carries the reciter and source of each recording, which the
 * credits screen reads. Attribution is a licence condition for the downloaded
 * recordings, so it is generated from the files themselves rather than typed
 * into a list that could silently fall out of step with what actually ships.
 */
class AthanCatalog(private val context: Context) {

    private val assets = context.applicationContext.assets

    /** Fajr recordings, which carry the extra dawn line. */
    val fajr: List<AthanSound>

    /** Everything else — Dhuhr, Asr, Maghrib and Isha. */
    val general: List<AthanSound>

    init {
        val index = readIndex()
        // The app's own recordings first; everything else alphabetical. They are
        // the default, and the only ones with no third party behind them.
        val preferred = listOf(DEVELOPER)
        val categories = runCatching { assets.list(ROOT)?.toList() }.getOrNull().orEmpty()
            .filter { it != INDEX_FILE }
            .sortedWith(
                compareBy(
                    { preferred.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE },
                    { it }
                )
            )

        val byCategory = categories.mapNotNull { category ->
            val files = runCatching { assets.list("$ROOT/$category")?.sorted() }
                .getOrNull().orEmpty()
            if (files.isEmpty()) null else category to files
        }

        val all = byCategory.flatMap { (category, files) ->
            val display = displayName(category)
            files.mapIndexed { position, file ->
                val entry = index["$category/$file"]
                AthanSound(
                    asset = "$ROOT/$category/$file",
                    // The recording's own title names the reciter, which is far
                    // more use than "Egyptian 3". Untagged files — the app's own
                    // recordings — keep the numbered fallback; numbered only when
                    // a category holds more than one, so a lone one reads "Kuwait".
                    label = entry?.title?.takeIf { it.isNotBlank() }
                        ?: if (files.size == 1) display else "$display ${position + 1}",
                    duration = formatDuration(entry?.seconds),
                    category = category,
                    reciter = entry?.title.orEmpty(),
                    source = entry?.source.orEmpty(),
                )
            }
        }

        fajr = all.filter { it.category == FAJR }
        general = all.filterNot { it.category == FAJR }
        Log.i(TAG, "catalogue: ${general.size} general, ${fajr.size} fajr")
    }

    /** Everything bundled, grouped for the credits screen. */
    fun byCategory(): List<Pair<String, List<AthanSound>>> =
        (general + fajr).groupBy { it.category }.map { (slug, sounds) -> displayName(slug) to sounds }

    /**
     * Defaults are matched on filename, not label: labels now come from the
     * recordings' own tags, so a label match would break the moment a tag
     * changed — and silently hand a fresh install someone else's recording.
     */
    val defaultGeneral: String?
        get() = general.firstOrNull { it.asset.endsWith(DEFAULT_GENERAL_FILE) }?.asset
            ?: general.firstOrNull()?.asset

    val defaultFajr: String?
        get() = fajr.firstOrNull { it.asset.endsWith(DEFAULT_FAJR_FILE) }?.asset
            ?: fajr.firstOrNull()?.asset

    /**
     * The value to actually play. A stored "content://" (a phone file or
     * ringtone) passes straight through; a bundled asset is honoured only if it
     * still exists; anything else falls back to the default.
     */
    fun resolveGeneral(prefs: Prefs): String? = resolve(prefs.otherSound, general, defaultGeneral)

    fun resolveFajr(prefs: Prefs): String? = resolve(prefs.fajrSound, fajr, defaultFajr)

    private fun resolve(stored: String, bundled: List<AthanSound>, default: String?): String? = when {
        stored.isEmpty() -> default
        SoundSource.isRingtone(stored) -> stored
        // A downloaded athan, kept only while its file still exists.
        SoundSource.isLocalFile(stored) -> if (java.io.File(stored).exists()) stored else default
        bundled.any { it.asset == stored } -> stored
        else -> default
    }

    fun labelFor(asset: String): String =
        (general + fajr).firstOrNull { it.asset == asset }?.label ?: "Athan"

    private data class Entry(val seconds: Int?, val title: String, val source: String)

    /** slug/file → duration, reciter, source. Written by tools/sync-audio.ps1. */
    private fun readIndex(): Map<String, Entry> = runCatching {
        assets.open("$ROOT/$INDEX_FILE").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                "${parts[0]}/${parts[1]}" to Entry(
                    seconds = parts[2].trim().toIntOrNull(),
                    title = parts.getOrNull(3)?.trim().orEmpty(),
                    source = parts.getOrNull(4)?.trim().orEmpty(),
                )
            }.toMap()
        }
    }.getOrElse {
        Log.w(TAG, "no audio index", it)
        emptyMap()
    }

    private fun formatDuration(seconds: Int?): String =
        if (seconds == null) "" else "%d:%02d".format(seconds / 60, seconds % 60)

    // Read through the caller's context, not the application's: the language
    // can change while the process lives, and the application context keeps
    // whatever locale it was created with.
    private fun displayName(category: String) = when (category) {
        DEVELOPER -> context.getString(R.string.category_developer)
        "mecca" -> context.getString(R.string.category_mecca)
        "madina" -> context.getString(R.string.category_madina)
        "emarat" -> context.getString(R.string.category_emarat)
        "various" -> context.getString(R.string.category_various)
        "egyptian" -> context.getString(R.string.category_egyptian)
        "turkish" -> context.getString(R.string.category_turkish)
        "kuwait" -> context.getString(R.string.category_kuwait)
        "georgia" -> context.getString(R.string.category_georgia)
        FAJR -> context.getString(R.string.category_fajr)
        // A folder added after this list was written still needs a heading.
        else -> category.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val TAG = "AthanCatalog"
        const val ROOT = "athan"
        const val FAJR = "fajr"
        const val DEVELOPER = "developer"
        const val INDEX_FILE = "index.tsv"
        // Category included in the match: a bare "001.mp3" would be free to
        // start meaning a different recording the moment a folder is added.
        const val DEFAULT_GENERAL_FILE = "kuwait/001.mp3"
        const val DEFAULT_FAJR_FILE = "fajr/168410.mp3"
    }
}
