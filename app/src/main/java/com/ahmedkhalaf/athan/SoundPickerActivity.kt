package com.ahmedkhalaf.athan

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivitySoundPickerBinding

/**
 * Picks the athan sound for one slot — Fajr, or all the other prayers, chosen by
 * the [EXTRA_FOR_FAJR] flag. Offers the bundled recordings plus two ways to use
 * the phone's own audio: any saved file, or a system ringtone.
 */
class SoundPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySoundPickerBinding
    private lateinit var prefs: Prefs
    private lateinit var catalog: AthanCatalog
    private var forFajr = false
    private var preview: MediaPlayer? = null

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // Copy the picked file into the app's own storage and list it under
        // "Downloaded athans", so it is reusable and cannot break later.
        val imported = AthanImports(this).import(uri)
        if (imported != null) select(imported.path) else toast(R.string.import_failed)
    }

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ?: return@registerForActivityResult
        select(uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySoundPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = Prefs(this)
        catalog = AthanCatalog(this)
        forFajr = intent.getBooleanExtra(EXTRA_FOR_FAJR, false)

        binding.title.setText(if (forFajr) R.string.fajr_athan_sound else R.string.athan_sound_other)
        if (forFajr) {
            binding.explainer.visibility = View.VISIBLE
            binding.explainer.setText(R.string.fajr_explainer)
        }
        rebuild()
    }

    private fun current(): String =
        (if (forFajr) catalog.resolveFajr(prefs) else catalog.resolveGeneral(prefs)).orEmpty()

    private fun select(value: String) {
        if (forFajr) prefs.fajrSound = value else prefs.otherSound = value
        rebuild()
        playPreview(value)
    }

    private fun rebuild() {
        val selected = current()
        buildOptions(selected)
        buildBundled(selected)
    }

    private fun buildOptions(selected: String) {
        binding.optionsList.removeAllViews()
        // Downloaded athans are managed on their own screen; here we just show
        // the current custom pick (a ringtone or a downloaded file) as selected.
        if (SoundSource.isCustom(selected)) {
            addRow(binding.optionsList, SoundSource.label(this, catalog, selected), checked = true) {
                playPreview(selected)
            }
        }
        addRow(binding.optionsList, getString(R.string.choose_from_device), checked = false) {
            pickFile.launch(arrayOf("audio/*"))
        }
        addRow(binding.optionsList, getString(R.string.phone_ringtone), checked = false) {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.phone_ringtone))
                // A ringtone existing-URI only makes sense if the pick is a ringtone.
                val existing = if (SoundSource.isRingtone(selected)) Uri.parse(selected)
                else Settings.System.DEFAULT_ALARM_ALERT_URI
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            }
            pickRingtone.launch(intent)
        }
    }

    private fun buildBundled(selected: String) {
        binding.soundList.removeAllViews()
        val sounds = if (forFajr) catalog.fajr else catalog.general
        sounds.forEach { sound ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_sound, binding.soundList, false)
            row.findViewById<TextView>(R.id.soundLabel).text = sound.label
            row.findViewById<TextView>(R.id.soundDuration).text = sound.duration
            row.findViewById<RadioButton>(R.id.soundRadio).isChecked = sound.asset == selected
            val onClick = View.OnClickListener { select(sound.asset) }
            row.setOnClickListener(onClick)
            row.findViewById<RadioButton>(R.id.soundRadio).setOnClickListener(onClick)
            binding.soundList.addView(row)
        }
    }

    /** An action or custom-selection row: label, radio state, no duration. */
    private fun addRow(container: LinearLayout, label: String, checked: Boolean, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_sound, container, false)
        row.findViewById<TextView>(R.id.soundLabel).text = label
        row.findViewById<TextView>(R.id.soundDuration).visibility = View.GONE
        val radio = row.findViewById<RadioButton>(R.id.soundRadio)
        radio.isChecked = checked
        radio.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        val handler = View.OnClickListener { onClick() }
        row.setOnClickListener(handler)
        radio.setOnClickListener(handler)
        container.addView(row)
    }

    private fun playPreview(value: String) {
        stopPreview()
        runCatching {
            preview = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                SoundSource.setDataSource(this@SoundPickerActivity, this, value)
                val v = prefs.volume / 100f
                setVolume(v, v)
                prepare()
                start()
            }
        }
    }

    private fun stopPreview() {
        preview?.runCatching { stop() }
        preview?.release()
        preview = null
    }

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        // Never let a preview keep playing once the screen is gone.
        stopPreview()
    }

    companion object {
        const val EXTRA_FOR_FAJR = "for_fajr"
    }
}
