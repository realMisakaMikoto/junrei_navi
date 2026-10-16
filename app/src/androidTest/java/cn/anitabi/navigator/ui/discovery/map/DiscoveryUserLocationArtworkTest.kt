package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Color
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryUserLocationArtworkTest {
    @Test fun unknownHeadingHasOnlyADotAndKnownHeadingAddsANorthArrowWithoutMovingTheDot() {
        for (density in listOf(1f, 1.5f, 2.75f)) for (dark in listOf(false, true)) {
            val dot = discoveryUserLocationArtwork(DiscoveryUserLocationStyle(density, dark, false))
            val arrow = discoveryUserLocationArtwork(DiscoveryUserLocationStyle(density, dark, true))
            try {
                assertEquals(.5f, dot.anchorX, 0f)
                assertEquals(.5f, dot.anchorY, 0f)
                assertEquals(dot.anchorX, arrow.anchorX, 0f)
                assertEquals(dot.anchorY, arrow.anchorY, 0f)
                assertEquals(dot.bitmap.width, arrow.bitmap.width)
                val center = dot.bitmap.width / 2
                val north = (center - 14f * density).roundToInt()
                assertEquals(0, Color.alpha(dot.bitmap.getPixel(center, north)))
                assertTrue(Color.alpha(arrow.bitmap.getPixel(center, north)) > 0)
                for (y in center - (4 * density).toInt()..center + (4 * density).toInt()) {
                    for (x in center - (4 * density).toInt()..center + (4 * density).toInt()) {
                        assertEquals(dot.bitmap.getPixel(x, y), arrow.bitmap.getPixel(x, y))
                    }
                }
                val south = (center + 14f * density).roundToInt()
                assertEquals(0, Color.alpha(arrow.bitmap.getPixel(center, south)))
            } finally {
                dot.bitmap.recycle()
                arrow.bitmap.recycle()
            }
        }
    }

    @Test fun artworkKeepsTransparentPaddingAtEachDensityInBothThemes() {
        for (density in listOf(1f, 1.5f, 2.75f)) for (dark in listOf(false, true)) {
            val artwork = discoveryUserLocationArtwork(DiscoveryUserLocationStyle(density, dark, true))
            try {
                val bitmap = artwork.bitmap
                for (x in 0 until bitmap.width) {
                    assertEquals(0, Color.alpha(bitmap.getPixel(x, 0)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(x, bitmap.height - 1)))
                }
                for (y in 0 until bitmap.height) {
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, y)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width - 1, y)))
                }
            } finally {
                artwork.bitmap.recycle()
            }
        }
    }
}
