package app.proview.camera.night

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live hand-shake reading for the night UI and planner. */
data class MotionReading(
    val steadiness: Steadiness = Steadiness.HANDHELD,
    val rmsRadPerSec: Float = 0f,
    /** Accumulated rotation since [MotionSensor.resetDrift], radians (x = pitch, y = yaw). */
    val driftX: Float = 0f,
    val driftY: Float = 0f,
)

/** One raw gyroscope sample, recorded during a burst for the merge to use (N2). */
data class GyroSample(val timestampNs: Long, val x: Float, val y: Float, val z: Float)

/**
 * Gyroscope listener. Feeds [SteadinessTracker], integrates drift for the steadiness crosshair,
 * and records samples while a burst is being captured.
 */
class MotionSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val tracker = SteadinessTracker()
    private var lastTs = 0L
    private var driftX = 0f
    private var driftY = 0f
    private var recording: MutableList<GyroSample>? = null

    private val _reading = MutableStateFlow(MotionReading())
    val reading: StateFlow<MotionReading> = _reading.asStateFlow()

    val available: Boolean get() = gyro != null

    fun start() {
        gyro?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    fun resetDrift() {
        driftX = 0f
        driftY = 0f
    }

    @Synchronized
    fun startRecording() {
        recording = mutableListOf()
    }

    @Synchronized
    fun stopRecording(): List<GyroSample> {
        val out = recording.orEmpty()
        recording = null
        return out
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        tracker.add(event.timestamp, x, y, z)
        if (lastTs != 0L) {
            val dt = (event.timestamp - lastTs) / 1e9f
            driftX += x * dt
            driftY += y * dt
        }
        lastTs = event.timestamp
        synchronized(this) { recording?.add(GyroSample(event.timestamp, x, y, z)) }
        _reading.value = MotionReading(tracker.steadiness, tracker.rms, driftX, driftY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
