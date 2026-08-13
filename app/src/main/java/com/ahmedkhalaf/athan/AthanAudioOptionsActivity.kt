package com.ahmedkhalaf.athan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityAthanAudioOptionsBinding

/** Groups every recording-related option under one Settings entry. */
class AthanAudioOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAthanAudioOptionsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAthanAudioOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)
        binding.fajrSoundRow.setOnClickListener {
            startActivity(Intent(this, SoundPickerActivity::class.java)
                .putExtra(SoundPickerActivity.EXTRA_FOR_FAJR, true))
        }
        binding.otherSoundRow.setOnClickListener {
            startActivity(Intent(this, SoundPickerActivity::class.java))
        }
        binding.downloadedRow.setOnClickListener {
            startActivity(Intent(this, DownloadedAthansActivity::class.java))
        }
        binding.moreAthansRow.setOnClickListener {
            startActivity(Intent(this, MoreAthansActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val catalog = AthanCatalog(this)
        binding.fajrSoundName.text =
            catalog.resolveFajr(prefs)?.let { SoundSource.label(this, catalog, it) }.orEmpty()
        binding.otherSoundName.text =
            catalog.resolveGeneral(prefs)?.let { SoundSource.label(this, catalog, it) }.orEmpty()
    }
}
