package com.ahmedkhalaf.athan

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityQiblaBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Points at the Kaaba. The bearing is pure spherical geometry from the saved
 * coordinates; the only live input is the phone's heading.
 */
class QiblaActivity : LocalizedActivity(), SensorEventListener {

    private lateinit var binding: ActivityQiblaBinding
    private lateinit var sensors: SensorManager

    /**
     * Preference order, and why it matters. GEOMAGNETIC_ROTATION_VECTOR and the
     * accelerometer+magnetometer pair are both absolute: they cannot wander away
     * from magnetic north. Plain ROTATION_VECTOR fuses the gyroscope, which is
     * smoother but free to drift in yaw, so the same physical spot can read a few
     * degrees differently between sessions. For a compass that is the wrong
     * trade, so it is the last resort rather than the first choice.
     */
    private var fusedSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private var qiblaBearing = 0.0
    private var declination = 0f
    private var smoothed = Float.NaN

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveField = false

    private val usable get() = fusedSensor != null || (accelerometer != null && magnetometer != null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQiblaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val prefs = Prefs(this)
        if (!prefs.hasLocation) {
            binding.compass.visibility = View.GONE
            binding.headingText.visibility = View.GONE
            binding.bearingText.setText(R.string.no_location_yet)
            binding.hint.visibility = View.GONE
            return
        }

        qiblaBearing = bearingToKaaba(prefs.latitude, prefs.longitude)
        binding.compass.qiblaBearing = qiblaBearing.toFloat()
        // The compass reads magnetic north; the qibla bearing is from true
        // north. Without this correction the arrow is wrong by the local
        // declination — over 10 degrees in parts of the world.
        declination = GeomagneticField(
            prefs.latitude.toFloat(),
            prefs.longitude.toFloat(),
            0f,
            System.currentTimeMillis()
        ).declination

        binding.bearingText.text = getString(
            R.string.qibla_bearing,
            qiblaBearing.roundToInt(),
            distanceToKaabaKm(prefs.latitude, prefs.longitude).roundToInt()
        )

        sensors = getSystemService(SensorManager::class.java)
        fusedSensor = sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (fusedSensor == null) {
            // No absolute fused sensor on this device (the Galaxy S21 is one).
            // Derive the same thing from raw accelerometer + magnetometer rather
            // than falling back to the drifting gyro-fused vector.
            accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
        if (!usable) {
            fusedSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }

        showNoticeOnce(prefs)

        if (!usable) {
            binding.compass.visibility = View.GONE
            binding.headingText.visibility = View.GONE
            binding.hint.setText(R.string.no_compass)
        }
    }

    override fun onResume() {
        super.onResume()
        val rate = SensorManager.SENSOR_DELAY_UI
        fusedSensor?.let { sensors.registerListener(this, it, rate) }
        accelerometer?.let { sensors.registerListener(this, it, rate) }
        magnetometer?.let { sensors.registerListener(this, it, rate) }
    }

    override fun onPause() {
        super.onPause()
        if (usable) sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                showAccuracy(event.accuracy)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                haveGravity = true
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                showAccuracy(event.accuracy)
                lowPass(event.values, geomagnetic)
                haveField = true
            }

            else -> return
        }

        if (fusedSensor == null) {
            if (!haveGravity || !haveField) return
            if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) return
        }

        SensorManager.getOrientation(rotationMatrix, orientation)
        val magneticNorth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val trueNorth = (magneticNorth + declination + 360f) % 360f

        // Raw sensor output jitters by a few degrees; smooth it, taking the
        // short way round so the dial never spins the long way through 360.
        smoothed = if (smoothed.isNaN()) trueNorth else {
            var delta = trueNorth - smoothed
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (smoothed + delta * HEADING_SMOOTHING + 360f) % 360f
        }
        binding.compass.heading = smoothed

        val offset = ((qiblaBearing.toFloat() - smoothed) + 360f) % 360f
        val offBy = minOf(offset, 360f - offset)
        val onTarget = offBy < 5f
        binding.compass.aligned = onTarget
        binding.hint.setText(if (onTarget) R.string.qibla_aligned else R.string.hold_flat)

        // Live numbers, so a drifting or miscalibrated compass is visible rather
        // than something you can only suspect.
        binding.headingText.text = getString(
            R.string.heading_now,
            smoothed.roundToInt() % 360,
            offBy.roundToInt(),
            getString(if (offset <= 180f) R.string.turn_right else R.string.turn_left)
        )
    }

    /** Raw accelerometer and magnetometer are noisy; this is what the fused sensors do internally. */
    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in input.indices) output[i] += RAW_SMOOTHING * (input[i] - output[i])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = showAccuracy(accuracy)

    private fun showAccuracy(accuracy: Int) {
        val poor = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        binding.calibrateWarning.visibility = if (poor) View.VISIBLE else View.GONE
    }

    /**
     * Shown the first time only. The causes listed are the ones that actually
     * bite: a miscalibrated magnetometer, and nearby metal or magnets. Location
     * services being off cannot affect this screen — the bearing comes from the
     * saved coordinates, not a live fix.
     */
    private fun showNoticeOnce(prefs: Prefs) {
        if (prefs.qiblaNoticeSeen) return
        val checkBox = layoutInflater.inflate(R.layout.dialog_qibla_notice, null)
            as android.widget.CheckBox
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qibla_notice_title)
            .setMessage(R.string.qibla_notice_body)
            .setView(checkBox)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (checkBox.isChecked) prefs.qiblaNoticeSeen = true
            }
            .show()
    }

    /** Great-circle initial bearing from here to the Kaaba, degrees from true north. */
    private fun bearingToKaaba(lat: Double, lng: Double): Double {
        val phi = Math.toRadians(lat)
        val deltaLambda = Math.toRadians(KAABA_LNG - lng)
        val y = sin(deltaLambda)
        val x = cos(phi) * tan(Math.toRadians(KAABA_LAT)) - sin(phi) * cos(deltaLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun distanceToKaabaKm(lat: Double, lng: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, KAABA_LAT, KAABA_LNG, results)
        return results[0] / 1000.0
    }

    private companion object {
        const val KAABA_LAT = 21.4224779
        const val KAABA_LNG = 39.8251832
        const val HEADING_SMOOTHING = 0.12f
        const val RAW_SMOOTHING = 0.20f
    }
}
