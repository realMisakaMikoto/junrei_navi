package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Authored bitmap fixtures; these checks do not infer either SDK's transparent-pixel hit behavior. */
class DiscoveryMarkerArtworkInstrumentedTest {
    private val point = DiscoveryMapPoint("fixture::0", 0, GeoPoint(0.0, 0.0), MapProvider.GOOGLE,
        "Hg8 \u4e2d\u6587", Color.MAGENTA)

    @Test fun labelsAndCountsUseScalableTextWithoutClippingOrMovingTheirAnchor() {
        val sheet = Bitmap.createBitmap(1100, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(Color.rgb(210, 215, 220)) }
        for ((row, scale) in listOf(1f, 2f).withIndex()) {
            val density = Density(2f, scale)
            val label = cluster(1, DiscoveryMarkerDecoration.LABEL)
            for ((column, dark) in listOf(false, true).withIndex()) {
                val artwork = discoveryMarkerArtwork(label, point, null, density, dark)
                saveDiagnostic(artwork.bitmap, "marker-label-font-$scale-dark-$dark")
                assertCanvasAndAnchor(artwork, density, decorated = true)
                val ink = if (dark) Color.rgb(243, 241, 238) else Color.rgb(36, 36, 38)
                val glyphs = pixelBounds(artwork.bitmap) { _, _, pixel -> pixel == ink }
                val expected = Rect().also { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 14.sp.toPx() } }
                    .getTextBounds(point.title, 0, point.title.length, it) }
                val diagnostic = "fontScale=$scale dark=$dark actual=${glyphs.width()}x${glyphs.height()} expected=${expected.width()}x${expected.height()}"
                assertTrue("Label glyphs retain the full scaled height: $diagnostic", glyphs.height() >= expected.height() - 2)
                assertTrue("Label glyphs retain the full scaled width: $diagnostic", glyphs.width() >= expected.width() - 3)
                assertTrue("Text has padding above and below", glyphs.top > 3 * density.density &&
                    glyphs.bottom < artwork.anchorY * artwork.bitmap.height - 9 * density.density)
                canvas.drawBitmap(artwork.bitmap, column * 550f, row * 190f, null)
            }
            for ((column, count) in listOf(2, 10_000, 100_000).withIndex()) {
                val artwork = discoveryMarkerArtwork(cluster(count), point, null, density, dark = false)
                saveDiagnostic(artwork.bitmap, "marker-count-$count-font-$scale")
                assertCanvasAndAnchor(artwork, density, decorated = false)
                val cx = artwork.bitmap.width / 2f
                val cy = artwork.bitmap.height / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = with(density) { 14.sp.toPx() }
                    textAlign = Paint.Align.CENTER
                    color = Color.WHITE
                }
                val text = count.toString()
                // Compare rasterized glyphs with identical text flags, including antialiased edges.
                // Paint().getTextBounds and exact-white pixels are not equivalent on API 26.
                val ink = Color.rgb(36, 36, 38)
                val reference = Bitmap.createBitmap(artwork.bitmap.width, artwork.bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(reference).apply {
                    drawColor(ink)
                    drawText(text, cx, cy - (paint.fontMetrics.top + paint.fontMetrics.bottom) / 2, paint)
                }
                saveDiagnostic(reference, "marker-count-reference-$count-font-$scale")
                val expected = pixelBounds(reference) { _, _, pixel -> pixel != ink }
                val glyphs = pixelBounds(artwork.bitmap) { x, y, pixel ->
                    pixel != ink && abs(x - cx) < paint.measureText(text) / 2 + 1 &&
                        abs(y - cy) < (paint.fontMetrics.bottom - paint.fontMetrics.top) / 2 + 1
                }
                val diagnostic = "count=$count fontScale=$scale textSizePx=${paint.textSize} actual=${glyphs.width()}x${glyphs.height()} expected=${expected.width()}x${expected.height()}"
                val defaultPaint = Paint().apply { textSize = paint.textSize }
                val defaultBounds = Rect().also { defaultPaint.getTextBounds(text, 0, text.length, it) }
                val antialiasedBounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
                println("MARKER_TEXT_RASTER $diagnostic defaultFlags=${defaultPaint.flags} referenceFlags=${paint.flags} " +
                    "defaultMetrics=${defaultBounds.width()}x${defaultBounds.height()} " +
                    "antialiasedMetrics=${antialiasedBounds.width()}x${antialiasedBounds.height()}")
                assertEquals("Every count digit remains legible: $diagnostic", expected, glyphs)
                for (y in expected.top until expected.bottom) for (x in expected.left until expected.right) {
                    assertEquals("Scaled reference glyph differs at $x,$y: $diagnostic",
                        reference.getPixel(x, y), artwork.bitmap.getPixel(x, y))
                }
                assertTrue("Counts retain padding inside the canvas", glyphs.left > 3 * density.density &&
                    glyphs.right < artwork.bitmap.width - 3 * density.density)
                canvas.drawBitmap(artwork.bitmap, column * 350f, 450f + row * 220f, null)
            }
        }
        if (InstrumentationRegistry.getArguments().getString("captureFrontendReview") == "true") {
            val app = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
            val directory = File(app.getExternalFilesDir(null), "frontend-review").apply { mkdirs() }
            File(directory, "marker-font-scaling.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun saveDiagnostic(bitmap: Bitmap, name: String) {
        val app = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
        val directory = File(app.getExternalFilesDir(null), "frontend-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allStylesKeepMinimumCanvasAndImageCardOffsetsAtDifferentDensities() {
        val photo = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        for (scale in listOf(1f, 1.5f, 2.75f)) for (fontScale in listOf(1f, 2f)) {
            val density = Density(scale, fontScale)
            for (style in DiscoveryMarkerDecoration.entries) for (selected in listOf(false, true)) {
                val marker = cluster(1, style).copy(selected = selected)
                val image = photo.takeIf { style == DiscoveryMarkerDecoration.IMAGE }
                val artwork = discoveryMarkerArtwork(marker, point, image, density, dark = false)
                assertCanvasAndAnchor(artwork, density, decorated = style != DiscoveryMarkerDecoration.DOT)
                val layout = discoveryMarkerLayout(marker, density, image != null)
                assertEquals(-artwork.anchorY * artwork.bitmap.height, layout.bounds.top, .001f)
                assertEquals(artwork.bitmap.height.toFloat(), layout.bounds.bottom - layout.bounds.top, .001f)
                if (image != null) {
                    val x = (artwork.anchorX * artwork.bitmap.width).roundToInt()
                    val y = (artwork.anchorY * artwork.bitmap.height - 35f * scale).roundToInt()
                    assertEquals("Photo keeps its offset above the geographic anchor", Color.CYAN, artwork.bitmap.getPixel(x, y))
                }
            }
            val missing = discoveryMarkerArtwork(cluster(1, DiscoveryMarkerDecoration.IMAGE), point, null, density, false)
            assertCanvasAndAnchor(missing, density, decorated = false)
        }
    }

    private fun assertCanvasAndAnchor(artwork: DiscoveryMarkerBitmap, density: Density, decorated: Boolean) {
        val bitmap = artwork.bitmap
        assertTrue(bitmap.width >= 48f * density.density)
        assertTrue(bitmap.height >= 48f * density.density)
        assertEquals(.5f, artwork.anchorX, 0f)
        if (!decorated) assertEquals(.5f, artwork.anchorY, 0f)
        else assertEquals("Connecting dot remains the anchor", point.colorArgb,
            bitmap.getPixel((artwork.anchorX * bitmap.width).roundToInt(), (artwork.anchorY * bitmap.height).roundToInt()))
        for (x in 0 until bitmap.width) {
            assertTrue("No opaque artwork is clipped at the top", Color.alpha(bitmap.getPixel(x, 0)) < 255)
            assertTrue("No opaque artwork is clipped at the bottom", Color.alpha(bitmap.getPixel(x, bitmap.height - 1)) < 255)
        }
    }

    private fun pixelBounds(bitmap: Bitmap, matches: (Int, Int, Int) -> Boolean): Rect {
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (matches(x, y, bitmap.getPixel(x, y))) {
            bounds.left = minOf(bounds.left, x)
            bounds.top = minOf(bounds.top, y)
            bounds.right = maxOf(bounds.right, x + 1)
            bounds.bottom = maxOf(bounds.bottom, y + 1)
        }
        assertTrue("Expected visible text pixels", bounds.width() > 0 && bounds.height() > 0)
        return bounds
    }

    private fun cluster(count: Int, style: DiscoveryMarkerDecoration = DiscoveryMarkerDecoration.DOT) =
        DiscoveryCluster("fixture-cluster", List(count) { "fixture::$it" }, point.id, ScreenPoint(200f, 200f), false, style)
}
