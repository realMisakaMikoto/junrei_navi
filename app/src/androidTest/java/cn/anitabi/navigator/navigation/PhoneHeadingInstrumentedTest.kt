package cn.anitabi.navigator.navigation

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Controlled rotation vectors exercise Android's matrix APIs, not a real compass or GNSS fix. */
class PhoneHeadingInstrumentedTest {
    @Test fun cardinalDirectionsRespectScreenRotationAndMagneticDeclination() {
        val displays = listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
        for (heading in listOf(0f, 90f, 180f, 270f)) for ((turns, display) in displays.withIndex()) {
            val halfRadians = Math.toRadians(-heading.toDouble()) / 2
            val vector = floatArrayOf(0f, 0f, sin(halfRadians).toFloat(), cos(halfRadians).toFloat())
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, vector)
            for (declination in listOf(-12f, 0f, 12f)) {
                val actual = phoneHeadingFromRotation(matrix, display, declination)!!
                val expected = normalizePhoneHeading(heading + turns * 90f + declination)!!
                val angularError = ((actual - expected + 540f) % 360f) - 180f
                assertEquals("Heading should follow the screen's top edge and true north", 0f, angularError, .001f)
            }
        }
    }

    @Test fun unknownScreenRotationOrUndefinedHorizontalDirectionDoesNotInventNorth() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertNull(phoneHeadingFromRotation(identity, -1, 0f))
        assertNull(phoneHeadingFromRotation(identity, Surface.ROTATION_0, Float.NaN))
        val topPointsUp = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertNull(phoneHeadingFromRotation(topPointsUp, Surface.ROTATION_0, 0f))
    }
}
