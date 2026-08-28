package com.ahmedkhalaf.athan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityAboutBinding

/** App identity, contact details, and credits for the included recordings. */
class AboutActivity : LocalizedActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.version.text = getString(R.string.about_version, version)
        binding.email.setOnClickListener { open("mailto:ahmedkhalaf1@yahoo.com") }
        binding.github.setOnClickListener { open("https://github.com/nandxorandor") }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
