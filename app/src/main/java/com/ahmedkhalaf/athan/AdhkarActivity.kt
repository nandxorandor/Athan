package com.ahmedkhalaf.athan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityAdhkarBinding

/**
 * The morning and evening remembrances. One activity for both sittings — they
 * differ only in which asset is read and what the heading says, and two screens
 * would be two places for the same layout to drift.
 *
 * The whole screen is laid out right-to-left regardless of the device locale:
 * the content is Arabic, and the rest of the app being in English must not
 * push it to the wrong edge.
 */
class AdhkarActivity : LocalizedActivity() {

    private lateinit var binding: ActivityAdhkarBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdhkarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val sitting =
            if (intent.getBooleanExtra(EXTRA_EVENING, false)) AdhkarSitting.EVENING
            else AdhkarSitting.MORNING

        binding.title.setText(sitting.titleRes)
        binding.whenSaid.setText(sitting.whenRes)

        val adhkar = Adhkar.load(this, sitting)
        binding.empty.visibility = if (adhkar.isEmpty()) View.VISIBLE else View.GONE

        adhkar.forEachIndexed { index, dhikr ->
            val row = layoutInflater.inflate(R.layout.item_dhikr, binding.list, false)
            row.findViewById<TextView>(R.id.dhikrNumber).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.dhikrRepeat).apply {
                visibility = if (dhikr.repeat.isBlank()) View.GONE else View.VISIBLE
                text = dhikr.repeat
            }
            row.findViewById<TextView>(R.id.dhikrText).text = dhikr.text
            row.findViewById<TextView>(R.id.dhikrSource).apply {
                visibility = if (dhikr.source.isBlank()) View.GONE else View.VISIBLE
                text = dhikr.source
            }
            binding.list.addView(row)
        }
    }

    companion object {
        private const val EXTRA_EVENING = "evening"

        fun intent(context: Context, sitting: AdhkarSitting): Intent =
            Intent(context, AdhkarActivity::class.java)
                .putExtra(EXTRA_EVENING, sitting == AdhkarSitting.EVENING)
    }
}
