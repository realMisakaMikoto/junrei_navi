package cn.anitabi.navigator.navigation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import cn.anitabi.navigator.core.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.math.hypot

/** A cold, map-scoped sensor subscription. It never reads movement bearing or starts navigation. */
class PhoneHeadingProvider(context: Context, private val screenRotation: () -> Int) {
    private val appContext = context.applicationContext

    fun observe(coordinate: GeoPoint): Flow<Float?> = callbackFlow<PhoneHeadingSample?> {
        trySend(null)
        val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        // Sea level is sufficient for the local declination estimate; no location leaves the app.
        val declination = GeomagneticField(coordinate.latitude.toFloat(), coordinate.longitude.toFloat(),
            0f, System.currentTimeMillis()).declination
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                    event.values.size < 3 || (0 until minOf(4, event.values.size)).any { !event.values[it].isFinite() }) {
                    trySend(null)
                    return
                }
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val heading = phoneHeadingFromRotation(rotation, screenRotation(), declination, remapped, orientation)
                trySend(heading?.let { PhoneHeadingSample(it, event.timestamp) })
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) trySend(null)
            }
        }
        val registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, Handler(Looper.getMainLooper()))
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose { manager.unregisterListener(listener) }
    }.freshPhoneHeadings(SystemClock::elapsedRealtimeNanos)
}

internal data class PhoneHeadingSample(val degrees: Float, val elapsedRealtimeNanos: Long)

internal fun normalizePhoneHeading(degrees: Float): Float? =
    if (degrees.isFinite()) ((degrees % 360f) + 360f) % 360f else null

internal fun phoneHeadingFromRotation(
    rotation: FloatArray,
    screenRotation: Int,
    declinationDegrees: Float,
    remapped: FloatArray = FloatArray(9),
    orientation: FloatArray = FloatArray(3),
): Float? {
    val axes = phoneHeadingAxes(screenRotation) ?: return null
    if (!SensorManager.remapCoordinateSystem(rotation, axes.first, axes.second, remapped) ||
        hypot(remapped[1], remapped[4]) < .0001f) return null
    SensorManager.getOrientation(remapped, orientation)
    return normalizePhoneHeading(Math.toDegrees(orientation[0].toDouble()).toFloat() + declinationDegrees)
}

internal fun phoneHeadingAxes(screenRotation: Int): Pair<Int, Int>? = when (screenRotation) {
    Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> null
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<PhoneHeadingSample?>.freshPhoneHeadings(nowNanos: () -> Long): Flow<Float?> = transformLatest { sample ->
    val age = sample?.let { nowNanos() - it.elapsedRealtimeNanos }
    val heading = sample?.degrees?.let(::normalizePhoneHeading)
    if (age == null || age !in 0 until PHONE_HEADING_MAX_AGE_NANOS || heading == null) {
        emit(null)
    } else {
        emit(heading)
        delay((PHONE_HEADING_MAX_AGE_NANOS - age + 999_999L) / 1_000_000L)
        emit(null)
    }
}.distinctUntilChanged().conflate()

private const val PHONE_HEADING_MAX_AGE_NANOS = 2_000_000_000L
