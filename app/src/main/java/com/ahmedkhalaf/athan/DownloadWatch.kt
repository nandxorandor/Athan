package com.ahmedkhalaf.athan

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

/**
 * Notices when an athan the user downloaded in their browser lands on the phone,
 * and says so where they actually are — a banner over the browser.
 *
 * The download happens entirely inside the browser, so nothing tells the app it
 * finished. A ContentObserver is the obvious answer and the wrong one twice
 * over: it only hears the collection it is registered on (Chrome writes through
 * MediaStore's *Downloads* collection, not the audio one), and it dies with the
 * process — which Android is free to kill the moment the browser needs the
 * memory. A JobScheduler content trigger has neither problem: the system holds
 * the registration and starts the app to deliver it, killed or not.
 *
 * State lives in [Prefs] for the same reason: none of it may depend on the
 * activity, or even the process, still being alive.
 */
object DownloadWatch {

    /** Begin watching. Everything already in Downloads is "before", not news. */
    fun start(context: Context) {
        val prefs = Prefs(context)
        prefs.downloadSnapshot = names(context)
        prefs.downloadWatchStartedAt = System.currentTimeMillis()
        schedule(context)
    }

    fun stop(context: Context) {
        Prefs(context).downloadWatchStartedAt = 0L
        runCatching { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }

    /**
     * Content triggers are one-shot — a job that has fired must re-register or
     * the next download goes unnoticed. Called on every firing as well as at the
     * start.
     */
    private fun schedule(context: Context) {
        if (!AthanImports.canSeeDownloads(context)) return
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadWatchJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            // The one that actually fires for a browser download. Audio stays
            // registered too: a file scanned later shows up there instead.
            .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(MAX_DELAY_MS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
        }
        runCatching { context.getSystemService(JobScheduler::class.java).schedule(builder.build()) }
            .onFailure { Log.w(TAG, "could not schedule download watch", it) }
    }

    /** A firing: work out what is new, say so, and re-arm. */
    fun onTriggered(context: Context) {
        val prefs = Prefs(context)
        val started = prefs.downloadWatchStartedAt
        if (started == 0L) return
        // Watching forever would leave a trigger registered for a browsing
        // session the user finished hours ago.
        if (System.currentTimeMillis() - started > WATCH_WINDOW_MS) {
            stop(context)
            return
        }
        val arrived = collectArrivals(context)
        if (arrived.isNotEmpty()) showBanner(context, prefs.downloadPending.toList())
        schedule(context)
    }

    /**
     * Names that appeared since the snapshot, folded into the pending list. The
     * snapshot advances at the same time, so one arrival is only ever news once.
     */
    fun collectArrivals(context: Context): List<String> {
        val prefs = Prefs(context)
        if (prefs.downloadWatchStartedAt == 0L) return emptyList()
        val now = names(context)
        val arrived = now - prefs.downloadSnapshot
        if (arrived.isNotEmpty()) {
            prefs.downloadSnapshot = now
            prefs.downloadPending = prefs.downloadPending + arrived
            Log.i(TAG, "downloaded: $arrived")
        }
        return arrived.toList()
    }

    /** Everything not yet shown in the app, cleared as it is handed over. */
    fun takePending(context: Context): List<String> {
        val prefs = Prefs(context)
        val pending = prefs.downloadPending.toList()
        prefs.downloadPending = emptySet()
        prefs.downloadBannerPosted = false
        return pending
    }

    /**
     * The confirmation the user is standing in front of: they are still in the
     * browser, so it has to be a notification. Silent — the browser has already
     * made its own noise — but high importance so it banners over the page.
     */
    fun showBanner(context: Context, arrived: List<String>) {
        if (arrived.isEmpty()) return
        val single = arrived.size == 1
        val name = arrived.first().substringBeforeLast('.')
        val text =
            if (single) context.getString(R.string.download_notify_one, name)
            else context.getString(R.string.download_notify_many, arrived.size)
        val expanded =
            if (single) context.getString(R.string.download_notify_expanded_one, name)
            else context.getString(R.string.download_notify_expanded_many, arrived.size)

        val view = PendingIntent.getActivity(
            context, BANNER_REQUEST_CODE,
            Intent(context, DownloadedAthansActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            context, DISMISS_REQUEST_CODE,
            Intent(context, DownloadDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AthanApp.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_athan)
            .setContentTitle(context.getString(R.string.download_arrived_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(view)
            // The same two choices the in-app dialog offers, so the answer does
            // not depend on which surface the user happens to be looking at.
            .addAction(0, context.getString(R.string.view_downloaded_short), view)
            .addAction(0, context.getString(R.string.keep_downloading), dismiss)
            // Swiping it away is an answer too: "I've seen it, leave me alone."
            .setDeleteIntent(dismiss)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(BANNER_ID, notification)
            Prefs(context).downloadBannerPosted = true
        }
    }

    /** Untouched in the shade means the user has not taken the news in yet. */
    fun bannerShowing(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == BANNER_ID }
    }.getOrDefault(false)

    fun clearBanner(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(BANNER_ID) }
    }

    private fun names(context: Context): Set<String> =
        AthanImports(context).downloadsAudio().map { it.name }.toSet()

    private const val TAG = "DownloadWatch"
    private const val JOB_ID = 2001
    private const val BANNER_ID = 44
    private const val BANNER_REQUEST_CODE = 1003
    private const val DISMISS_REQUEST_CODE = 1004
    /** Let a burst of MediaStore writes settle, but stay quick enough to feel live. */
    private const val UPDATE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 4_000L
    private val WATCH_WINDOW_MS = TimeUnit.HOURS.toMillis(6)
}

/** Started by the system when the watched MediaStore collections change. */
class DownloadWatchJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // A MediaStore query is disk work; the job's callback runs on the main
        // thread, so it cannot happen here.
        Thread {
            runCatching { DownloadWatch.onTriggered(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }

    /** Reschedule: a download we were killed mid-check must not be lost. */
    override fun onStopJob(params: JobParameters): Boolean = true
}

/**
 * "Keep downloading", and the swipe-away that means the same thing: drop the
 * banner and forget the arrivals, so the app does not repeat the same news as a
 * dialog the next time the user is back on the More athans screen.
 */
class DownloadDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DownloadWatch.clearBanner(context)
        DownloadWatch.takePending(context)
    }
}
