package com.ahmedkhalaf.athan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The current temperature at the saved coordinates.
 *
 * This is the **only** part of the app that touches the network, and it is the
 * only reason the app holds INTERNET at all. Everything else — prayer times,
 * the qibla, the athkar, the calendar — is computed on the device and always
 * will be. So this is built to be switched off completely (see
 * [Prefs.weatherEnabled]) and to fail silently: no error text, no retry storm,
 * no dialog. If the temperature cannot be had, the screen simply does not show
 * one, and nothing else on it is affected.
 *
 * Open-Meteo needs no API key. That is the whole reason it was chosen: a key
 * shipped inside an APK is a key anyone can extract, and someone else's traffic
 * would then be spent against it.
 */
object Weather {

    /** A reading, and when it was taken. */
    data class Reading(val celsius: Double, val takenAt: Long)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather").apply { isDaemon = true }
    }

    @Volatile
    private var cached: Reading? = null

    @Volatile
    private var inFlight = false

    /** The last reading if it is still fresh, else null. Never blocks. */
    fun current(): Reading? = cached?.takeIf { fresh(it) }

    /**
     * Fetches in the background and calls [onResult] on the caller's thread
     * only if something changed. Does nothing at all when the user has turned
     * the temperature off, when there is no location yet, or when a fetch is
     * already running.
     */
    fun refresh(context: Context, onResult: (Reading) -> Unit) {
        val prefs = Prefs(context)
        // Nothing leaves the device until the first-run notice has been
        // answered. Without this the fetch races the dialog and the
        // coordinates are already gone by the time the question is put.
        if (!prefs.weatherNoticeSeen) return
        if (!prefs.weatherEnabled || !prefs.hasLocation) return

        cached?.let { if (fresh(it)) return }
        if (inFlight) return
        inFlight = true

        val latitude = prefs.latitude
        val longitude = prefs.longitude
        io.execute {
            val reading = runCatching { fetch(latitude, longitude) }
                .onFailure { Log.i(TAG, "no temperature: ${it.javaClass.simpleName}") }
                .getOrNull()
            inFlight = false
            if (reading != null) {
                cached = reading
                onResult(reading)
            }
        }
    }

    /** Dropped when the user turns the feature off, so nothing lingers on screen. */
    fun forget() {
        cached = null
    }

    private fun fresh(reading: Reading) =
        System.currentTimeMillis() - reading.takenAt < CACHE_MS

    private fun fetch(latitude: Double, longitude: Double): Reading {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                latitude, longitude
            )
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val celsius = JSONObject(body)
                .getJSONObject("current")
                .getDouble("temperature_2m")
            Reading(celsius, System.currentTimeMillis())
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "Weather"

    /**
     * Half an hour. The temperature outside does not change faster than that in
     * any way worth a request, and the screen is opened many times a day.
     */
    private const val CACHE_MS = 30 * 60 * 1000L

    /** Short: this is decoration. It must never hold anything up. */
    private const val TIMEOUT_MS = 5000
}
