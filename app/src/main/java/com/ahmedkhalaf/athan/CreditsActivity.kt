package com.ahmedkhalaf.athan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityCreditsBinding

/**
 * Where every bundled recording came from. This is not decoration: IslamWeb
 * permits its material to be used non-commercially **provided the source is
 * named**, so this screen is the condition being met, and it is built from the
 * shipped audio index rather than a hand-written list — a credit that can drift
 * out of step with the files is worse than none.
 */
class CreditsActivity : LocalizedActivity() {

    private lateinit var binding: ActivityCreditsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreditsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.islamwebLink.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FATWA_URL)))
            }
        }

        val inflater = LayoutInflater.from(this)
        AthanCatalog(this).byCategory().forEach { (heading, sounds) ->
            addHeading(heading)
            sounds.forEach { sound ->
                val row = inflater.inflate(R.layout.item_credit, binding.recordings, false)
                row.findViewById<TextView>(R.id.creditName).text = sound.label
                row.findViewById<TextView>(R.id.creditSource).apply {
                    val credit = creditFor(sound)
                    visibility = if (credit.isBlank()) View.GONE else View.VISIBLE
                    text = credit
                }
                binding.recordings.addView(row)
            }
        }
    }

    /**
     * The recording's own artist tag where it has one. Where it does not, say so
     * plainly rather than inventing a source: the app's own recordings are named
     * as such, and an untagged download is described as exactly that.
     */
    private fun creditFor(sound: AthanSound): String = when {
        sound.category == DEVELOPER -> getString(R.string.credit_own_recording)
        sound.asset.endsWith(DEVELOPER_FAJR) -> getString(R.string.credit_own_recording)
        sound.source.isNotBlank() -> getString(R.string.credit_source, tidy(sound.source))
        else -> getString(R.string.credit_source_unknown)
    }

    /** Tags carry things like "www.islamweb.net\<Arabic name>"; show one line. */
    private fun tidy(source: String): String =
        source.replace('\\', ' ').replace(Regex("\\s+"), " ").trim()

    private fun addHeading(text: String) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            letterSpacing = 0.08f
            setPadding(0, resources.displayMetrics.density.times(18).toInt(), 0, 0)
        }
        binding.recordings.addView(view)
    }

    private companion object {
        const val DEVELOPER = "developer"
        const val DEVELOPER_FAJR = "Developer_Athan-3_Fajr.mp3"
        const val FATWA_URL = "https://www.islamweb.net/en/fatwa/379009/"
    }
}
