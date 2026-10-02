package cn.anitabi.navigator.data.images

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.ui.discovery.DiscoveryThumbnail
import cn.anitabi.navigator.ui.discovery.DiscoveryImageViewer
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Explicit host-HTTPS integration selector, using production Coil and actual composable pixels. */
@OptIn(coil3.annotation.DelicateCoilApi::class)
class DiscoveryImageUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    private val responses = CopyOnWriteArrayList<Pair<Int, String?>>()

    @Before fun installFixtureTransport() {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("imageFixturePort")?.toIntOrNull())
        val certificate = File(context.getExternalFilesDir(null), "frontend-fix-v2/fixture-cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val client = createAppImageHttpClient().newBuilder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .addInterceptor { chain ->
                val original = chain.request()
                check(original.url.host == "image.anitabi.cn" && original.url.scheme == "https")
                val local = HttpUrl.Builder().scheme("https").host("localhost").port(port)
                    .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(local).build()).also {
                    responses += it.code to original.url.queryParameter("plan")
                }
            }.build()
        previous = SingletonImageLoader.get(context)
        loader = createAppImageLoader(context, client).newBuilder()
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restoreLoader() {
        if (::previous.isInitialized) SingletonImageLoader.setUnsafe(previous)
        if (::loader.isInitialized) loader.shutdown()
        val directory = File(context.getExternalFilesDir(null), "frontend-fix-v2").apply { mkdirs() }
        File(directory, "image-ui-${responses.size}-${responses.firstOrNull()?.first ?: 0}.json").writeText(buildJsonObject {
            put("controlledHttps", true); put("fakeImages", false)
            put("requests", buildJsonArray { responses.forEach { row -> add(buildJsonObject {
                put("status", row.first); put("plan", row.second ?: "original")
            }) } })
        }.toString())
    }

    @Test fun missingImageThenFailureRetryAndNewResourceUseActualDecodedPixels() {
        val url = mutableStateOf<String?>(null)
        compose.setContent { AnitabiTheme {
            DiscoveryThumbnail(url.value, Modifier.size(56.dp).testTag("real-image"))
        } }
        compose.onNodeWithContentDescription("\u6682\u65e0\u56fe\u7247").assertIsDisplayed()
        assertTrue(responses.isEmpty())
        compose.runOnIdle { url.value = "https://image.anitabi.cn/images/points/synthetic-owner/retry.png?v=ui-fixture" }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(COMPACT_RETRY).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(404 to "h160"), responses.toList())
        compose.onNodeWithContentDescription(COMPACT_RETRY).assertIsDisplayed().performClick()
        awaitDecodedPixel()
        assertEquals(listOf(404 to "h160", 200 to "h160"), responses.toList())
        compose.onNodeWithContentDescription(COMPACT_RETRY).assertDoesNotExist()
        compose.runOnIdle { url.value = "/images/user/synthetic-owner/upload.png?v=ui-fixture" }
        compose.waitUntil(10_000) { responses.size == 3 }
        awaitDecodedPixel()
        compose.onNodeWithContentDescription(COMPACT_RETRY).assertDoesNotExist()
    }

    @Test fun fullscreenStartsAtDisplaySizeAndOriginalNeedsExplicitAction() {
        compose.setContent { AnitabiTheme {
            DiscoveryImageViewer("https://image.anitabi.cn/images/points/synthetic-owner/upload.png?v=ui-fixture") { }
        } }
        compose.waitUntil(10_000) { responses.size == 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(LOADING).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(200 to "h360"), responses.toList())
        compose.onNodeWithText(RETRY).assertDoesNotExist()
        compose.waitUntil(10_000) {
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val center = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
            val ready = center == android.graphics.Color.rgb(31, 113, 179)
            if (ready) File(context.getExternalFilesDir(null), "frontend-fix-v2/fullscreen-loaded.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            ready
        }
        compose.onNodeWithText("\u52a0\u8f7d\u539f\u56fe").performClick()
        compose.waitUntil(10_000) { responses.size == 2 }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(LOADING).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(200 to "h360", 200 to null), responses.toList())
        compose.onNodeWithText(RETRY).assertDoesNotExist()
        compose.onNodeWithContentDescription("\u5173\u95ed\u56fe\u7247").assertIsDisplayed()
    }

    private fun awaitDecodedPixel() {
        compose.waitUntil(10_000) {
            val image = compose.onNodeWithTag("real-image").captureToImage()
            val pixel = image.toPixelMap()[image.width / 2, image.height / 2]
            kotlin.math.abs(pixel.red * 255 - 31) < 1 && kotlin.math.abs(pixel.green * 255 - 113) < 1 && kotlin.math.abs(pixel.blue * 255 - 179) < 1
        }
    }

    companion object {
        private const val RETRY = "\u91cd\u8bd5\u56fe\u7247"
        private const val COMPACT_RETRY = "\u56fe\u7247\u52a0\u8f7d\u5931\u8d25\uff0c\u91cd\u8bd5"
        private const val LOADING = "\u6b63\u5728\u52a0\u8f7d\u56fe\u7247"
    }
}
