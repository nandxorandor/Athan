package com.ahmedkhalaf.athan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityMoreAthansBinding

/**
 * Signposts to places on the web where more athans can be downloaded. The app
 * only opens the browser at the source's own page — it never fetches, stores or
 * bundles the audio itself. The user downloads on their phone and then selects
 * the file via "Choose from device" in the sound picker. That keeps the app
 * clear of redistributing anything: a link copies nothing.
 */
class MoreAthansActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreAthansBinding

    /** name, one-line note, and the page (not a raw file) to open. */
    private data class Source(val name: String, val note: String, val url: String)

    private val sources = listOf(
        Source(
            "Wikimedia Commons — free-licensed",
            "Creative Commons / public-domain athans",
            "https://commons.wikimedia.org/w/index.php?search=adhan+call+to+prayer&title=Special:MediaSearch&type=audio"
        ),
        Source(
            "IslamWeb — athan library",
            "Many reciters (download on their site)",
            "https://audio.islamweb.net/audio/index.php?Gtype=1&page=AudioGroup"
        ),
        Source(
            "Internet Archive — athan recordings",
            "Large collection; check each item's license",
            "https://archive.org/search?query=athan+adhan&and[]=mediatype%3A%22audio%22"
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoreAthansBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        sources.forEach { source ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_link, binding.list, false)
            row.findViewById<TextView>(R.id.linkName).text = source.name
            row.findViewById<TextView>(R.id.linkNote).text = source.note
            row.setOnClickListener { open(source.url) }
            binding.list.addView(row)
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
