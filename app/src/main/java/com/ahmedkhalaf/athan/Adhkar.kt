package com.ahmedkhalaf.athan

import android.content.Context
import android.util.Log

/** One remembrance: what is said, how many times, and who narrated it. */
data class Dhikr(
    val text: String,
    /** e.g. "ثلاث مرات". Blank when it is said once. */
    val repeat: String,
    /** e.g. "رواه مسلم". */
    val source: String,
)

/**
 * The two sittings. The asset carries the text so the words live in a data file
 * rather than in code — a religious text should be reviewable as a plain file,
 * and correcting a letter must never mean touching Kotlin.
 */
enum class AdhkarSitting(
    val asset: String,
    val titleRes: Int,
    val whenRes: Int,
) {
    MORNING("adhkar/morning.tsv", R.string.adhkar_morning, R.string.adhkar_morning_when),
    EVENING("adhkar/evening.tsv", R.string.adhkar_evening, R.string.adhkar_evening_when),
}

/**
 * Reads a sitting from assets. Same three-column, tab-separated shape as the
 * audio index: text, repetition, narration. A malformed line is skipped rather
 * than shown half-parsed — a truncated dhikr is worse than a missing one.
 */
object Adhkar {

    fun load(context: Context, sitting: AdhkarSitting): List<Dhikr> = runCatching {
        context.applicationContext.assets.open(sitting.asset)
            .bufferedReader()
            .useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank() || line.startsWith('#')) return@mapNotNull null
                    val parts = line.split('\t')
                    val text = parts.getOrNull(0)?.trim().orEmpty()
                    if (text.isEmpty()) return@mapNotNull null
                    Dhikr(
                        text = text,
                        repeat = parts.getOrNull(1)?.trim().orEmpty(),
                        source = parts.getOrNull(2)?.trim().orEmpty(),
                    )
                }.toList()
            }
    }.getOrElse {
        Log.w(TAG, "could not read ${sitting.asset}", it)
        emptyList()
    }

    private const val TAG = "Adhkar"
}
