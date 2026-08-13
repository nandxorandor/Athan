package com.ahmedkhalaf.athan

import android.content.Context
import android.util.Log

data class AthanSound(
    /** Asset path, e.g. "athan/mecca/4002.mp3". Stored in prefs. */
    val asset: String,
    val label: String,
    val duration: String,
)

/**
 * Built by listing assets/athan at runtime rather than from a hardcoded list.
 * Adding recordings is then a matter of re-running tools/sync-audio.ps1 and
 * rebuilding — no Kotlin to edit, and no way for the two to drift apart.
 */
class AthanCatalog(context: Context) {

    private val assets = context.applicationContext.assets

    /** Fajr recordings, which carry the extra dawn line. */
    val fajr: List<AthanSound>

    /** Everything else — Dhuhr, Asr, Maghrib and Isha. */
    val general: List<AthanSound>

    init {
        val durations = readIndex()
        // Mecca and Madina first, then alphabetical. Plain alphabetical would put
        // "egyptian" at the top, which also makes it the fallback default —
        // the two Haramain recordings are the ones people expect to see first.
        val preferred = listOf("mecca", "madina")
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
            files.mapIndexed { index, file ->
                AthanSound(
                    asset = "$ROOT/$category/$file",
                    // Numbered only when a category holds more than one, so a
                    // lone recording reads "Kuwait" rather than "Kuwait 1".
                    label = if (files.size == 1) display else "$display ${index + 1}",
                    duration = formatDuration(durations["$category/$file"]),
                )
            }
        }

        fajr = all.filter { it.asset.startsWith("$ROOT/$FAJR/") }
        general = all.filterNot { it.asset.startsWith("$ROOT/$FAJR/") }
        Log.i(TAG, "catalogue: ${general.size} general, ${fajr.size} fajr")
    }

    /** Default when the user has not chosen: Original recording 1, else first. */
    val defaultGeneral: String?
        get() = general.firstOrNull { it.label == DEFAULT_GENERAL_LABEL }?.asset
            ?: general.firstOrNull()?.asset

    /** Default for Fajr: the bundled dawn recording, else first. */
    val defaultFajr: String?
        get() = fajr.firstOrNull { it.label == DEFAULT_FAJR_LABEL }?.asset
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

    private fun readIndex(): Map<String, Int> = runCatching {
        assets.open("$ROOT/$INDEX_FILE").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val seconds = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
                "${parts[0]}/${parts[1]}" to seconds
            }.toMap()
        }
    }.getOrElse {
        Log.w(TAG, "no duration index", it)
        emptyMap()
    }

    private fun formatDuration(seconds: Int?): String =
        if (seconds == null) "" else "%d:%02d".format(seconds / 60, seconds % 60)

    // Only "original" and "fajr" ship today; the rest are kept so that dropping
    // a properly licensed folder into audio/ names itself correctly.
    private fun displayName(category: String) = when (category) {
        "original" -> "Original recording"
        "mecca" -> "Mecca — Masjid al-Haram"
        "madina" -> "Madina — Masjid an-Nabawi"
        "emarat" -> "Emirates"
        FAJR -> "Fajr athan"
        else -> category.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val TAG = "AthanCatalog"
        const val ROOT = "athan"
        const val FAJR = "fajr"
        const val INDEX_FILE = "index.tsv"
        // Matched by label, not by filename, so renumbering the audio files
        // cannot silently change what a fresh install plays.
        const val DEFAULT_GENERAL_LABEL = "Original recording 1"
        const val DEFAULT_FAJR_LABEL = "Fajr athan"
    }
}
