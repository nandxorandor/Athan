package com.ahmedkhalaf.athan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The month's timetable as a printable sheet: one A4 page as a PDF, or the same
 * sheet as a PNG for sending in a chat. People asked for these over the CSV,
 * which a phone opens as raw text and which cannot be printed as it stands.
 *
 * The sheet is built from ordinary views rather than drawn by hand, so Arabic
 * is shaped, mirrored and ordered by the same text engine as the screen - a
 * hand-drawn table would have to re-solve right-to-left layout, and the times
 * ("5:27 ص") are exactly the mixed text that goes wrong when it does. Sizes
 * are in PDF points (1/72 inch); the image is the same sheet drawn at 3x.
 *
 * Black on white, light shading only: it is meant to be printed and put on a
 * fridge, and the app's dark theme would spend a cartridge doing it.
 */
class RamadanExport(
    private val context: Context,
    private val days: List<RamadanDay>,
    private val hijriYear: Int,
    private val subtitle: String,
    /** The user's notes, by day of Ramadan; listed under the table. */
    private val notes: Map<Int, String> = emptyMap(),
) {
    private val timeFormat =
        SimpleDateFormat(context.getString(R.string.time_pattern), Locale.getDefault())
    private val dayFormat = SimpleDateFormat("d/M", Locale.getDefault())
    private val longDayFormat = SimpleDateFormat("d MMMM", Locale.getDefault())

    /** One A4 page. Taller only if the month somehow outgrows it. */
    fun writePdf(file: File) {
        val sheet = buildSheet()
        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, maxOf(PAGE_HEIGHT, sheet.height), 1).create()
            )
            sheet.draw(page.canvas)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** Cropped to the content: an image has no page to fill. */
    fun writePng(file: File) {
        val sheet = buildSheet()
        val bitmap = Bitmap.createBitmap(
            PAGE_WIDTH * IMAGE_SCALE, sheet.height * IMAGE_SCALE, Bitmap.Config.ARGB_8888
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(IMAGE_SCALE.toFloat(), IMAGE_SCALE.toFloat())
            sheet.draw(canvas)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Measured and laid out at the page width, ready to draw. */
    private fun buildSheet(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(MARGIN, MARGIN, MARGIN, MARGIN)
            // A view that is never attached has no parent to inherit from, and
            // falls back to left-to-right - the Arabic sheet would come out
            // with its columns in English order.
            layoutDirection = context.resources.configuration.layoutDirection
        }

        root.addView(text(context.getString(R.string.ramadan_title, hijriYear), 20f, INK, bold = true)
            .apply { gravity = Gravity.CENTER })
        root.addView(text(subtitle, 11f, DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 12)
        })

        root.addView(row(
            listOf(
                context.getString(R.string.ramadan_col_day),
                context.getString(R.string.ramadan_col_date),
                context.getString(R.string.ramadan_col_suhoor),
                context.getString(R.string.sunrise).removePrefix("☀️ "),
                context.getString(R.string.dhuhr),
                context.getString(R.string.asr),
                context.getString(R.string.ramadan_col_iftar),
                context.getString(R.string.isha),
            ),
            background = HEADER_BG, color = Color.WHITE, header = true,
        ))

        days.forEach { day ->
            val friday = Calendar.getInstance().apply { time = day.date }
                .get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            root.addView(row(
                listOf(
                    // Marked, so the notes below can be found from the table.
                    day.dayOfRamadan.toString() + if (notes.containsKey(day.dayOfRamadan)) "*" else "",
                    dayFormat.format(day.date),
                    timeFormat.format(day.fajr),
                    timeFormat.format(day.sunrise),
                    timeFormat.format(day.dhuhr),
                    timeFormat.format(day.asr),
                    timeFormat.format(day.maghrib),
                    timeFormat.format(day.isha),
                ),
                background = when {
                    friday -> FRIDAY_BG
                    day.dayOfRamadan % 2 == 0 -> BAND_BG
                    else -> Color.WHITE
                },
                color = INK, header = false,
            ))
        }

        // Under the table rather than in it: a column wide enough for a note
        // would squeeze the six times off a phone-printed page.
        if (notes.isNotEmpty()) {
            root.addView(text(context.getString(R.string.ramadan_notes), 10f, INK, bold = true)
                .apply { setPadding(0, 12, 0, 4) })
            notes.toSortedMap().forEach { (day, note) ->
                val date = days.firstOrNull { it.dayOfRamadan == day }?.date
                val label = context.getString(R.string.ramadan_note_line, day,
                    date?.let { longDayFormat.format(it) }.orEmpty())
                root.addView(text("$label: $note", 9f, INK).apply { setPadding(0, 1, 0, 1) })
            }
        }

        root.addView(text(context.getString(R.string.ramadan_footnote), 7.5f, DIM)
            .apply { setPadding(0, 10, 0, 0) })
        root.addView(text(context.getString(R.string.app_name), 7.5f, DIM)
            .apply { gravity = Gravity.CENTER; setPadding(0, 6, 0, 0) })

        root.measure(
            View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun row(cells: List<String>, background: Int, color: Int, header: Boolean) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(background)
            cells.forEachIndexed { i, value ->
                // Suhoor and iftar are what the sheet is for; they stand out.
                val emphasis = header || i == COL_SUHOOR || i == COL_IFTAR
                addView(
                    text(value, if (header) 9f else 10f, color, bold = emphasis).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, if (header) 6 else 4, 0, if (header) 6 else 4)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, WEIGHTS[i]),
                )
            }
        }

    private fun text(value: String, sizePt: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePt)
            setTextColor(color)
            includeFontPadding = false
            // The app's language, not the first letter: "Antakya · 8 فبراير"
            // otherwise reads as English and scrambles the Arabic dates.
            textDirection = View.TEXT_DIRECTION_LOCALE
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private companion object {
        /** A4 in points. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 32
        const val IMAGE_SCALE = 3

        /** Day number narrow, date a little wider, the six times equal. */
        val WEIGHTS = floatArrayOf(0.55f, 0.9f, 1.15f, 1.15f, 1.15f, 1.15f, 1.15f, 1.15f)
        const val COL_SUHOOR = 2
        const val COL_IFTAR = 6

        val INK = Color.rgb(0x16, 0x1C, 0x1A)
        val DIM = Color.rgb(0x5E, 0x6A, 0x66)
        val HEADER_BG = Color.rgb(0x1F, 0x6B, 0x4F)
        val BAND_BG = Color.rgb(0xF2, 0xF5, 0xF4)
        val FRIDAY_BG = Color.rgb(0xD9, 0xF0, 0xE4)
    }
}
