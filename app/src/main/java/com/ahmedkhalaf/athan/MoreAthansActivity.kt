package com.ahmedkhalaf.athan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityMoreAthansBinding

/**
 * Signposts to places on the web where more athans can be downloaded. The app
 * only opens the browser at the source's own page — it never fetches, stores or
 * bundles the audio itself. That keeps the app clear of redistributing
 * anything: a link copies nothing.
 *
 * Opening a source also starts [DownloadWatch], which is what turns "the
 * browser saved something" into the app confirming it: a banner while the user
 * is still in the browser, and the same news as a dialog if they come back here
 * without having dealt with it.
 */
class MoreAthansActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreAthansBinding
    private lateinit var prefs: Prefs

    /** Without the permission we can only guess — so guess at most once a visit. */
    private var promptedUnseen = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingUrl: String? = null

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Granted or not, the user asked to visit the source — go there either way.
        pendingUrl?.let { openBrowser(it) }
        pendingUrl = null
    }

    /** name, one-line note, and the page (not a raw file) to open. */
    private data class Source(val name: String, val note: String, val url: String)

    private val sources = listOf(
        Source(
            "IslamWeb — athan library",
            "Many reciters (download on their site)",
            "https://audio.islamweb.net/audio/index.php?Gtype=1&page=AudioGroup"
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

        prefs = Prefs(this)

        sources.forEach { source ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_link, binding.list, false)
            row.findViewById<TextView>(R.id.linkName).text = source.name
            row.findViewById<TextView>(R.id.linkNote).text = source.note
            row.setOnClickListener { open(source.url) }
            binding.list.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacksAndMessages(null)
        // The banner is cleared unconditionally: the user is looking at the app,
        // so it has nothing left to tell them — and one left behind by a killed
        // process would otherwise sit in the shade for good.
        val bannerWasShowing = DownloadWatch.bannerShowing(this)
        DownloadWatch.clearBanner(this)
        report(bannerWasShowing, attempt = 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // Leaving this screen ends the browsing session the watch existed for.
        if (!isChangingConfigurations) DownloadWatch.stop(this)
    }

    /**
     * Tell the user what arrived, if anything. [attempt] retries because
     * MediaStore indexes the file a moment after the browser writes it, so a
     * user who switches straight back can arrive before the job has fired.
     * Silence afterwards is deliberate — someone who only browsed the page must
     * not be told they downloaded something.
     */
    private fun report(bannerWasShowing: Boolean, attempt: Int) {
        if (prefs.downloadWatchStartedAt == 0L) return
        if (!AthanImports.canSeeDownloads(this)) {
            if (!promptedUnseen) {
                promptedUnseen = true
                announceUnseen()
            }
            return
        }
        DownloadWatch.collectArrivals(this)
        if (prefs.downloadPending.isNotEmpty()) {
            // Already banner-ed and the user dealt with it from the shade? Then
            // the app has said its piece; repeating it in a dialog is nagging.
            val handled = prefs.downloadBannerPosted && !bannerWasShowing
            val arrived = DownloadWatch.takePending(this)
            if (!handled) announce(arrived)
            return
        }
        if (attempt < RETRIES) {
            handler.postDelayed({ report(bannerWasShowing, attempt + 1) }, RETRY_MS)
        }
    }

    /**
     * Reading the Downloads folder is what lets the app spot the file at all.
     * Asked here rather than silently, because a permission dialog appearing on
     * an "open this website" tap is otherwise unexplained — and asked only once,
     * so declining does not turn every source tap into a nag.
     */
    private fun open(url: String) {
        if (AthanImports.canSeeDownloads(this) || prefs.downloadWatchAsked) {
            openBrowser(url)
            return
        }
        prefs.downloadWatchAsked = true
        AlertDialog.Builder(this)
            .setTitle(R.string.watch_downloads_title)
            .setMessage(R.string.watch_downloads_body)
            .setPositiveButton(R.string.watch_downloads_allow) { _, _ ->
                pendingUrl = url
                requestAudio.launch(AthanImports.permission())
            }
            .setNegativeButton(R.string.not_now) { _, _ -> openBrowser(url) }
            .setOnCancelListener { openBrowser(url) }
            .show()
    }

    private fun openBrowser(url: String) {
        DownloadWatch.start(this)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * The file is in the phone's Downloads folder, which is exactly what the
     * Downloaded athans screen lists — so it is already there to be picked, and
     * the offer is to go and assign it rather than to import anything.
     */
    private fun announce(arrived: List<String>) {
        if (arrived.isEmpty()) return
        val message =
            if (arrived.size == 1) {
                getString(R.string.download_arrived_one, arrived.first().substringBeforeLast('.'))
            } else {
                getString(R.string.download_arrived_many, arrived.size)
            }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_arrived_title)
            .setMessage(message)
            .setPositiveButton(R.string.view_downloaded) { _, _ -> openDownloaded() }
            .setNegativeButton(R.string.keep_downloading, null)
            .show()
    }

    /** No permission to look, so say what is true without claiming a download. */
    private fun announceUnseen() {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_unseen_title)
            .setMessage(R.string.download_unseen_body)
            .setPositiveButton(R.string.view_downloaded) { _, _ -> openDownloaded() }
            .setNegativeButton(R.string.keep_downloading, null)
            .show()
    }

    private fun openDownloaded() =
        startActivity(Intent(this, DownloadedAthansActivity::class.java))

    private companion object {
        /** ~6 s of grace, which covers MediaStore indexing comfortably. */
        const val RETRIES = 4
        const val RETRY_MS = 1500L
    }
}
