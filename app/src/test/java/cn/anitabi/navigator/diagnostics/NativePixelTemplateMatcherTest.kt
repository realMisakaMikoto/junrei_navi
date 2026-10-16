package cn.anitabi.navigator.diagnostics

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePixelTemplateMatcherTest {
    private fun noise(seed: Long) = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB).apply {
        val random = Random(seed)
        repeat(height) { y -> repeat(width) { x -> setRGB(x, y, -0x1000000 or random.nextInt(0x1000000)) } }
    }

    private fun count(text: String) = BufferedImage(160, 96, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().also { graphics ->
            try {
                graphics.color = Color(36, 36, 38)
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.font = Font("Dialog", Font.PLAIN, 36)
                graphics.color = Color.WHITE
                graphics.drawString(text, 28, 63)
            } finally { graphics.dispose() }
        }
    }

    private fun samples(image: BufferedImage, left: Int = 8, right: Int = image.width - 8) = buildList {
        for (y in 8 until image.height - 8 step 3) for (x in left until right step 3) {
            add(NativePixelSample(x, y, IntArray(9) { index -> image.getRGB(x + index % 3 - 1, y + index / 3 - 1) }))
        }
    }

    private fun rendered(image: BufferedImage, dx: Double, dy: Double) = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().also { graphics ->
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(image, AffineTransform.getTranslateInstance(dx, dy), null)
            } finally { graphics.dispose() }
        }
    }

    private fun matches(reference: BufferedImage, actual: BufferedImage, glyph: Boolean = false): Boolean {
        val selected = samples(reference, if (glyph) 28 else 8, if (glyph) 127 else reference.width - 8)
        return nativePixelTemplatesMatch(selected, if (glyph) selected else emptyList()) { x, y ->
            if (x in 0 until actual.width && y in 0 until actual.height) actual.getRGB(x, y) else null
        }
    }

    @Test fun integerOffsetsMatchActualRasterizerOutput() {
        val reference = noise(37)
        for (dx in -2..2) for (dy in -2..2) assertTrue(matches(reference, rendered(reference, dx.toDouble(), dy.toDouble())))
    }

    @Test fun fractionalImageOffsetsMatchActualBilinearRasterizerOutput() {
        val reference = noise(37)
        for (dx in listOf(-.5, .125, .5, .875)) for (dy in listOf(-.5, .25, .625)) {
            assertTrue("fractional image $dx $dy", matches(reference, rendered(reference, dx, dy)))
        }
    }

    @Test fun fractionalZeroGlyphMatchesActualBilinearRasterizerOutput() {
        val reference = count("1110")
        for (offset in listOf(.125, .25, .5, .75)) assertTrue(matches(reference, rendered(reference, offset, offset), glyph = true))
    }

    @Test fun differentImageCannotPassByChoosingAnotherOffset() {
        assertFalse(matches(noise(37), rendered(noise(99), .5, .5)))
    }

    @Test fun differentCountCannotPassByChoosingAnotherOffset() {
        assertFalse(matches(count("1110"), rendered(count("1111"), .5, .5), glyph = true))
    }

    @Test fun occludingControlCannotPassAsTheCompleteImage() {
        val reference = noise(37)
        val actual = rendered(reference, .5, .5)
        actual.createGraphics().also { graphics ->
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(36, 16, 48, 48)
            } finally { graphics.dispose() }
        }
        assertFalse(matches(reference, actual))
    }
}
