package com.ahmedkhalaf.athan

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityDownloadedAthansBinding

/**
 * One place for the athans the user downloaded. Auto-lists audio from the
 * Downloads folder, and tapping one asks which prayers it should announce —
 * Fajr, the other prayers, or both — so a downloaded file is never silently
 * tied to whichever screen happened to be open.
 */
class DownloadedAthansActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadedAthansBinding
    private lateinit var prefs: Prefs
    private lateinit var imports: AthanImports
    private var preview: MediaPlayer? = null

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadedAthansBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)
        imports = AthanImports(this)
        build()
    }

    private fun build() {
        binding.list.removeAllViews()
        val imported = imports.list()
        val importedNames = imported.map { it.name }.toSet()

        if (needsAudioPermission()) {
            addActionRow(getString(R.string.show_downloaded)) {
                requestAudio.launch(audioPermission())
            }
        }
        imported.forEach { addFileRow(it.path, it.name, canDelete = true) }
        if (!needsAudioPermission()) {
            imports.downloadsAudio()
                .filter { it.name.substringBeforeLast('.') !in importedNames }
                .forEach { audio ->
                    addFileRow(audio.name, audio.name, canDelete = false) {
                        imports.import(audio.uri)?.path
                    }
                }
        }

        val nothing = imported.isEmpty() &&
            (needsAudioPermission() || imports.downloadsAudio().isEmpty())
        binding.empty.visibility = if (nothing) View.VISIBLE else View.GONE
    }

    /**
     * A file row. [resolvePath] turns a pending download into a real imported
     * path on first tap (by copying it in); imported files pass their own path.
     */
    private fun addFileRow(
        path: String,
        name: String,
        canDelete: Boolean,
        resolvePath: () -> String? = { path },
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_download_manage, binding.list, false)
        row.findViewById<TextView>(R.id.name).text = name.substringBeforeLast('.')

        val note = assignmentNote(path)
        row.findViewById<TextView>(R.id.assignment).apply {
            visibility = if (note == null) View.GONE else View.VISIBLE
            if (note != null) text = note
        }

        val delete = row.findViewById<View>(R.id.deleteButton)
        delete.visibility = if (canDelete) View.VISIBLE else View.GONE
        delete.setOnClickListener { confirmDelete(path, name) }

        row.setOnClickListener {
            val real = resolvePath()
            if (real == null) {
                toast(R.string.import_failed)
            } else {
                preview(real)
                chooseDestination(real)
            }
        }
        binding.list.addView(row)
    }

    private fun addActionRow(label: String, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_download_manage, binding.list, false)
        row.findViewById<TextView>(R.id.name).text = label
        row.findViewById<View>(R.id.deleteButton).visibility = View.GONE
        row.setOnClickListener { onClick() }
        binding.list.addView(row)
    }

    private fun chooseDestination(path: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.use_athan_for)
            .setItems(
                arrayOf(
                    getString(R.string.dest_fajr),
                    getString(R.string.dest_other),
                    getString(R.string.dest_both),
                )
            ) { _, which ->
                when (which) {
                    0 -> prefs.fajrSound = path
                    1 -> prefs.otherSound = path
                    else -> { prefs.fajrSound = path; prefs.otherSound = path }
                }
                AthanScheduler.scheduleNext(this)
                build()
            }
            .setOnDismissListener { stopPreview() }
            .show()
    }

    private fun assignmentNote(path: String): String? = when {
        prefs.fajrSound == path && prefs.otherSound == path -> getString(R.string.assigned_both)
        prefs.fajrSound == path -> getString(R.string.assigned_fajr)
        prefs.otherSound == path -> getString(R.string.assigned_other)
        else -> null
    }

    private fun confirmDelete(path: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_athan, name.substringBeforeLast('.')))
            .setPositiveButton(R.string.delete) { _, _ ->
                stopPreview()
                imports.delete(path)
                if (prefs.fajrSound == path) prefs.fajrSound = ""
                if (prefs.otherSound == path) prefs.otherSound = ""
                build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun preview(path: String) {
        stopPreview()
        runCatching {
            preview = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                SoundSource.setDataSource(this@DownloadedAthansActivity, this, path)
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

    override fun onStop() {
        super.onStop()
        stopPreview()
    }

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun needsAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
}
