package cn.anitabi.navigator.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.data.images.ControlledImageTransport
import cn.anitabi.navigator.data.images.createAppImageLoader
import cn.anitabi.navigator.data.repository.PilgrimageData
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Explicit controlled-HTTPS selector with real Coil/Android PNG decoding in the search point row.
 * Run with a fresh ControlledImageServer: its retry resource fails the first request per server.
 * All metadata is authored; no public image/data request, map, route or device permission is used.
 */
@OptIn(coil3.annotation.DelicateCoilApi::class)
class SearchPointImageInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var transport: ControlledImageTransport
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader

    @Before fun installControlledTransport() {
        transport = ControlledImageTransport(context)
        previous = SingletonImageLoader.get(context)
        loader = createAppImageLoader(context, transport.client).newBuilder()
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restoreLoader() {
        if (::previous.isInitialized) SingletonImageLoader.setUnsafe(previous)
        if (::loader.isInitialized) loader.shutdown()
    }

    @Test fun pointThumbnailMissingFailureAndRetryKeepRowSelectionAndDecodedPixels() {
        val anime = Anime(901L, "Synthetic search work")
        val point = PilgrimagePoint("synthetic-search-point", POINT_NAME, GeoPoint(0.0, 0.0))
        val data = PilgrimageData(anime, listOf(point), 1)
        var state by mutableStateOf(SearchUiState(
            selectedAnimeData = mapOf(anime.subjectId to data), showList = true, selectionOpen = true,
        ))
        var toggles = 0
        compose.setContent { AnitabiTheme {
            PilgrimageSelectionScreen(state = state, forceListMode = true,
                onBack = {}, onBoundsChanged = {}, onSelectVisible = {}, onClearSelection = {},
                onShowList = {}, onMapUnavailable = {}, onPlan = {},
                onTogglePoint = { id ->
                    assertEquals(SCOPED_POINT_ID, id)
                    toggles++
                    state = state.copy(selectedPointIds = if (id in state.selectedPointIds) emptySet() else setOf(id))
                },
            )
        } }
        compose.onNodeWithContentDescription(NO_IMAGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(RETRY).assertDoesNotExist()
        assertTrue("NoImage must not perform an image request", transport.responses.isEmpty())
        compose.onNodeWithText(POINT_NAME).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            state = state.copy(selectedAnimeData = mapOf(anime.subjectId to data.copy(points = listOf(point.copy(
                imageUrl = "/images/points/synthetic-owner/retry.png?v=search-ui-fixture",
            )))))
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(RETRY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(NO_IMAGE, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(404), transport.responses.map { it.status })
        compose.onNodeWithText(POINT_NAME).assertIsDisplayed().assertIsOn()
        compose.onNodeWithContentDescription(RETRY).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            val image = compose.onNodeWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true).captureToImage()
            val pixel = image.toPixelMap()[image.width / 2, image.height / 2]
            abs(pixel.red * 255 - 31) < 1 && abs(pixel.green * 255 - 113) < 1 && abs(pixel.blue * 255 - 179) < 1
        }
        assertEquals(listOf(404, 200), transport.responses.map { it.status })
        assertTrue(transport.responses.all { it.anitabi && !it.control && !it.legacyPrefix &&
            it.plan == "h160" && it.sensitiveHeadersAbsent })
        compose.onNodeWithContentDescription(RETRY).assertDoesNotExist()
        compose.onNodeWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true)
            .assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(72.dp)
        compose.onNodeWithText(POINT_NAME).assertIsOn()
        compose.runOnIdle {
            assertEquals("Image retry must not toggle the parent checkbox row", 1, toggles)
            assertEquals(setOf(SCOPED_POINT_ID), state.selectedPointIds)
            assertTrue("Image recovery must preserve the authored WGS84 position",
                state.combinedPilgrimageData!!.points.single().coordinate == point.coordinate)
        }
        compose.onNodeWithText(POINT_NAME).performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, toggles) }
    }

    private companion object {
        const val POINT_NAME = "Synthetic search point"
        const val SCOPED_POINT_ID = "901::synthetic-search-point"
        const val IMAGE_DESCRIPTION = "$POINT_NAME \u5de1\u793c\u622a\u56fe"
        const val NO_IMAGE = "\u6682\u65e0\u56fe\u7247"
        const val RETRY = "\u56fe\u7247\u52a0\u8f7d\u5931\u8d25\uff0c\u91cd\u8bd5"
    }
}
