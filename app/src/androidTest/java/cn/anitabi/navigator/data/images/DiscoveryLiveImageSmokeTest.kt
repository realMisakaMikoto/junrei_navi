package cn.anitabi.navigator.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.createAppUserAgentInterceptor
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.HttpDiscoverySource
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.ui.discovery.DiscoveryImageViewer
import cn.anitabi.navigator.ui.discovery.DiscoveryThumbnail
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit public-network smoke only. All real source fields and pixels remain in memory. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class DiscoveryLiveImageSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val observer = LiveObserver()
    private val decodes = CopyOnWriteArrayList<DecodeObservation>()
    private val uiBitmap = AtomicReference<Bitmap?>(null)
    private val stage = mutableStateOf(0)
    private var uiUrl: String? = null
    private var sampledSubjects = 0
    private var sampledImages = 0
    private var sourceSubjects = 0
    private var sourcePoints = 0
    private var pageSubjects = 0
    private var snapshotRoundTrip = false
    private var legacyRoundTrip = false
    private var legacyPrefixObserved = false
    private var thumbnailPixels = false
    private var viewerPixels = false
    private var failure: String? = null

    @Test fun approvedPublicReferencesReachProductionAndroidDecoderAndUi() {
        assumeTrue("Requires explicit bounded public-network approval",
            InstrumentationRegistry.getArguments().getString("liveImageSmokeApproved") == "true")
        var previous: ImageLoader? = null
        var loader: ImageLoader? = null
        try {
            val samples = runBlocking { sampleOnePage() }
            val first = samples.first()
            val liveLoader = createAppImageLoader(context, createAppImageHttpClient().newBuilder()
                .addNetworkInterceptor(observer).build()).newBuilder()
                // Public references and response bytes must not enter the device's disk image cache.
                .diskCachePolicy(CachePolicy.DISABLED).allowHardware(false)
                .eventListenerFactory { eventListener(observer.phase.get()) }.build()
            loader = liveLoader
            previous = SingletonImageLoader.get(context)
            SingletonImageLoader.setUnsafe(liveLoader)
            runBlocking {
                samples.forEachIndexed { index, sample ->
                    observer.phase.set(Phase("decoder", index + 1, sample.family, sample.prefixKind, "h160"))
                    val result = liveLoader.execute(ImageRequest.Builder(context)
                        .data(AnitabiImageReference.request(sample.storedReference, AnitabiImageVariant.THUMBNAIL))
                        .size(Size.ORIGINAL).allowHardware(false).diskCachePolicy(CachePolicy.DISABLED).build())
                    requireSmoke(result is SuccessResult, "android_decode")
                    val bitmap = (result as SuccessResult).image.toBitmap()
                    requireSmoke(bitmap.width > 0 && bitmap.height > 0, "android_decode_dimensions")
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                }
            }
            observer.phase.set(Phase("thumbnail_ui", 1, first.family, first.prefixKind, "h160"))
            uiBitmap.getAndSet(null)?.recycle()
            stage.value = 1
            compose.setContent { AnitabiTheme {
                when (stage.value) {
                    1 -> DiscoveryThumbnail(uiUrl, Modifier.size(120.dp).testTag("live-thumbnail"))
                    2 -> DiscoveryImageViewer(requireNotNull(uiUrl)) { stage.value = 0 }
                }
            } }
            awaitUiSuccess("thumbnail_ui")
            compose.waitUntil(20_000) {
                val expected = uiBitmap.get() ?: return@waitUntil false
                val actual = compose.onNodeWithTag("live-thumbnail").captureToImage().asAndroidBitmap()
                matchesPixels(expected, actual, crop = true)
            }
            thumbnailPixels = true
            observer.phase.set(Phase("fullscreen_ui", 1, first.family, first.prefixKind, "h360"))
            uiBitmap.getAndSet(null)?.recycle()
            compose.runOnIdle { stage.value = 2 }
            awaitUiSuccess("fullscreen_ui")
            compose.waitUntil(20_000) {
                val expected = uiBitmap.get() ?: return@waitUntil false
                val actual = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: return@waitUntil false
                try { matchesPixels(expected, actual, crop = false) } finally { actual.recycle() }
            }
            viewerPixels = true
            requireSmoke(observer.rows.all { it.userAgent && it.headers && it.host && it.redirectApproved }, "request_policy")
            requireSmoke(observer.rows.count { it.phase.name == "index" } == 1 &&
                observer.rows.count { it.phase.name == "page" } == 1, "source_request_budget")
        } catch (error: Throwable) {
            failure = if (error is SmokeFailure) error.category else coarseFailure(error)
        } finally {
            runCatching { compose.runOnIdle { stage.value = 0 } }
            previous?.let(SingletonImageLoader::setUnsafe)
            loader?.shutdown()
            uiBitmap.getAndSet(null)?.recycle()
            uiUrl = null
            writeReport()
        }
        if (failure != null) throw AssertionError("Live image smoke did not complete: $failure; inspect scalar report")
    }

    private suspend fun sampleOnePage(): List<Sample> {
        val source = HttpDiscoverySource(createAppUserAgentInterceptor(), OkHttpClient.Builder().addNetworkInterceptor(observer))
        val token = (System.currentTimeMillis() / 60_000 / 24 + 6).toString(36)
        observer.phase.set(Phase("index"))
        val rawIndex = source.index(token)
        val index = DiscoveryParser.index(rawIndex)
        sourceSubjects = index.subjects.size
        sourcePoints = index.points.size
        val pageNumber = index.subjects.chunked(index.pageSize).indexOfFirst { group -> group.count { it.pointIds.isNotEmpty() } >= 3 }
        requireSmoke(pageNumber >= 0, "insufficient_index_subjects")
        observer.phase.set(Phase("page"))
        val rawPage = source.page(pageNumber, token)
        val details = DiscoveryParser.page(rawPage)
        val expected = index.subjects.drop(pageNumber * index.pageSize).take(index.pageSize).map { it.id }.toSet()
        requireSmoke(details.map { it.subjectId }.toSet() == expected, "page_membership")
        pageSubjects = details.size
        val rawRows = (rawIndex as JsonArray)[0] as JsonArray
        val covers = rawRows.associate { row -> val cells = row as JsonArray
            requireNotNull((cells[0] as? JsonPrimitive)?.longOrNull) to cells[6].sourceText()
        }
        val rawImages = (rawPage as JsonArray).associate { row -> val cells = row as JsonArray
            requireNotNull((cells[0] as? JsonPrimitive)?.longOrNull) to (cells[2] as JsonArray).associate { point ->
                val fields = point as JsonArray
                requireNotNull(fields[0].sourceText()) to fields[6].sourceText()
            }
        }
        val indexedPointIds = index.subjects.associate { it.id to it.pointIds.toSet() }
        val available = details.mapNotNull { subject ->
            val points = subject.points.filter { !it.isFolder &&
                "${subject.subjectId}::${it.rawId}" in indexedPointIds[subject.subjectId].orEmpty()
            }.mapNotNull { detail ->
                sample(subject.subjectId, detail.rawId, rawImages[subject.subjectId]?.get(detail.rawId), detail.imageUrl)
            }
            if (points.isEmpty()) null else Triple(subject.subjectId, points,
                sample(subject.subjectId, null, covers[subject.subjectId], index.subjects.first { it.id == subject.subjectId }.anime.imageUrl))
        }.take(4)
        requireSmoke(available.size in 3..5, "insufficient_page_subjects")
        sampledSubjects = available.size
        val selected = linkedMapOf<String, Sample>()
        fun add(value: Sample?) { if (value != null && selected.size < 6) selected.putIfAbsent(value.storedReference, value) }
        available.forEach { add(it.second.first()) }
        available.forEach { add(it.third) }
        available.forEach { it.second.drop(1).forEach(::add) }
        requireSmoke(selected.size in 3..6, "image_sample_budget")
        sampledImages = selected.size
        val subjectIds = available.map { it.first }.toSet()
        val merged = DiscoveryParser.merge(index, details)
        val subset = merged.copy(subjects = merged.subjects.filter { it.id in subjectIds },
            points = merged.points.filter { it.subjectId in subjectIds }, loadedPages = emptySet(),
            currentSubjectIds = merged.currentSubjectIds.intersect(subjectIds), endVersionVerified = false)
        val json = Json { ignoreUnknownKeys = true }
        val cached = json.decodeFromString(DiscoverySnapshot.serializer(), json.encodeToString(DiscoverySnapshot.serializer(), subset))
        val result = selected.values.map { sample ->
            val stored = if (sample.rawPointId == null) cached.subjects.first { it.id == sample.subjectId }.anime.imageUrl
            else cached.points.first { it.subjectId == sample.subjectId && it.rawId == sample.rawPointId }.imageUrl
            requireSmoke(stored == sample.storedReference, "cache_reference_roundtrip")
            sample.copy(storedReference = requireNotNull(stored))
        }
        snapshotRoundTrip = true
        val first = result.first()
        val point = cached.points.first { it.subjectId == first.subjectId && it.rawId == first.rawPointId }
        val legacy = if (first.rawReference.startsWith("/")) "https://image.anitabi.cn${first.rawReference}" else first.rawReference
        val oldPoint = json.decodeFromString(DiscoveryPoint.serializer(),
            json.encodeToString(DiscoveryPoint.serializer(), point.copy(imageUrl = legacy)))
        uiUrl = requireNotNull(oldPoint.imageUrl)
        legacyRoundTrip = true
        legacyPrefixObserved = legacy != first.storedReference
        return result
    }

    private fun sample(subjectId: Long, pointId: String?, raw: String?, parsed: String?): Sample? {
        if (raw == null || parsed == null || AnitabiImageReference.normalize(raw) != parsed) return null
        val thumbnail = AnitabiImageReference.request(parsed, AnitabiImageVariant.THUMBNAIL)?.toHttpUrlOrNull() ?: return null
        val display = AnitabiImageReference.request(parsed, AnitabiImageVariant.DISPLAY)?.toHttpUrlOrNull() ?: return null
        if (thumbnail.queryParameter("plan") != "h160" || display.queryParameter("plan") != "h360") return null
        val family = thumbnail.pathSegments.firstOrNull()?.takeIf { it in setOf("points", "user", "bangumi") } ?: return null
        val prefix = if (raw.startsWith("/images/")) "legacy_relative"
            else if (raw.startsWith("https://image.anitabi.cn/images/")) "legacy_absolute"
            else if (raw.startsWith("/")) "canonical_relative" else "canonical_absolute"
        return Sample(subjectId, pointId, raw, parsed, family, prefix)
    }

    private fun eventListener(phase: Phase): EventListener = object : EventListener() {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            val bitmap = result.image.toBitmap()
            decodes += DecodeObservation(phase, true, result.dataSource.name, bitmap.width, bitmap.height, null)
            if (phase.name.endsWith("_ui")) uiBitmap.getAndSet(bitmap.copy(Bitmap.Config.ARGB_8888, false))?.recycle()
        }
        override fun onError(request: ImageRequest, result: ErrorResult) {
            decodes += DecodeObservation(phase, false, null, 0, 0, coarseFailure(result.throwable))
        }
    }

    private fun awaitUiSuccess(name: String) {
        compose.waitUntil(40_000) { decodes.any { it.phase.name == name } }
        requireSmoke(decodes.last { it.phase.name == name }.success, "${name}_decode")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("\u6b63\u5728\u52a0\u8f7d\u56fe\u7247").fetchSemanticsNodes().isEmpty()
        }
    }

    /** Nine interior patches compare actual rendered pixels against the real UI decoder bitmap. */
    private fun matchesPixels(expected: Bitmap, actual: Bitmap, crop: Boolean): Boolean {
        val scale = if (crop) max(actual.width.toFloat() / expected.width, actual.height.toFloat() / expected.height)
            else min(actual.width.toFloat() / expected.width, actual.height.toFloat() / expected.height)
        val left = (actual.width - expected.width * scale) / 2f
        val top = (actual.height - expected.height * scale) / 2f
        var matches = 0
        for (fy in listOf(.25f, .5f, .75f)) for (fx in listOf(.25f, .5f, .75f)) {
            val centerX = if (crop) actual.width * fx else left + expected.width * scale * fx
            val centerY = if (crop) actual.height * fy else top + expected.height * scale * fy
            var error = 0.0
            var count = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (centerX.toInt() + dx).coerceIn(0, actual.width - 1)
                val y = (centerY.toInt() + dy).coerceIn(0, actual.height - 1)
                val sx = (x + .5f - left) / scale - .5f
                val sy = (y + .5f - top) / scale - .5f
                val pixel = actual.getPixel(x, y)
                val source = expected.getPixel(sx.toInt().coerceIn(0, expected.width - 1), sy.toInt().coerceIn(0, expected.height - 1))
                if (Color.alpha(source) < 250) continue
                for (shift in listOf(16, 8, 0)) {
                    val bx = floor(sx).toInt(); val by = floor(sy).toInt()
                    val tx = sx - bx; val ty = sy - by
                    fun channel(ix: Int, iy: Int) = (expected.getPixel(ix.coerceIn(0, expected.width - 1),
                        iy.coerceIn(0, expected.height - 1)) shr shift) and 255
                    val predicted = (channel(bx, by) * (1 - tx) + channel(bx + 1, by) * tx) * (1 - ty) +
                        (channel(bx, by + 1) * (1 - tx) + channel(bx + 1, by + 1) * tx) * ty
                    error += abs(((pixel shr shift) and 255) - predicted)
                    count++
                }
            }
            if (count >= 30 && error / count <= 18) matches++
        }
        return matches >= 7
    }

    private fun writeReport() {
        val directory = File(context.getExternalFilesDir(null), "frontend-fix-v2").apply { mkdirs() }
        File(directory, "live-image-android.json").writeText(buildJsonObject {
            put("publicNetworkOptIn", true); put("syntheticTransport", false); put("productionParserAndLoader", true)
            put("sourceSubjects", sourceSubjects); put("sourcePointTuples", sourcePoints); put("pageSubjects", pageSubjects)
            put("sampledSubjects", sampledSubjects); put("distinctSourceImages", sampledImages)
            put("sampledSnapshotRoundTrip", snapshotRoundTrip); put("legacyPointRoundTrip", legacyRoundTrip)
            put("legacyPrefixObserved", legacyPrefixObserved); put("thumbnailPixelsMatched", thumbnailPixels)
            put("fullscreenPixelsMatched", viewerPixels); put("originalRequested", false)
            put("diskImageCacheEnabled", false); put("rawIdentifiersBodiesOrPixelsSaved", false)
            put("endIndexRecheckPerformed", false); put("minimumRequestSpacingMillis", 1_050)
            put("androidApi", android.os.Build.VERSION.SDK_INT)
            put("pixelCheck", "at least 7 of 9 interior patches, mean RGB delta at most 18")
            put("failureCategory", failure ?: "none")
            put("network", buildJsonArray { observer.rows.forEach { row -> add(buildJsonObject {
                row.phase.write(this); put("status", row.status); put("mime", row.mime)
                put("bodyBytesRead", row.bytes.get()); put("elapsedMillis", row.elapsed.get())
                put("userAgentCorrect", row.userAgent); put("sensitiveHeadersAbsent", row.headers)
                put("approvedHost", row.host); put("redirectApproved", row.redirectApproved)
                put("actualVariant", row.actualVariant); put("variantCorrect", row.variantCorrect)
                put("errorCategory", row.error ?: "none")
            }) } })
            put("decodes", buildJsonArray { decodes.forEach { row -> add(buildJsonObject {
                row.phase.write(this); put("success", row.success); put("dataSource", row.source ?: "none")
                put("width", row.width); put("height", row.height); put("errorCategory", row.error ?: "none")
            }) } })
        }.toString())
    }

    private class LiveObserver : Interceptor {
        val phase = AtomicReference(Phase("unassigned"))
        val rows = CopyOnWriteArrayList<NetworkObservation>()
        private var lastStart = 0L
        private val expectedAgent = createAppUserAgentInterceptor().value
        @Synchronized private fun pace() {
            val remaining = 1_050 - (SystemClock.elapsedRealtime() - lastStart)
            if (remaining > 0) Thread.sleep(remaining)
            lastStart = SystemClock.elapsedRealtime()
        }
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            requireSmoke(rows.size < 18, "network_request_budget")
            pace()
            val request = chain.request()
            val source = request.url.host == "www.anitabi.cn" && request.url.encodedPath.matches(Regex("/d/g[0-9]*\\.json"))
            val image = request.url.host == "image.anitabi.cn" && AnitabiImageReference.normalize(request.url.toString()) == request.url.toString()
            val row = NetworkObservation(phase.get(), userAgent = request.header("User-Agent") == expectedAgent,
                headers = listOf("Authorization", "Cookie", "X-Goog-Api-Key", "X-Api-Key", "X-Amap-Key", "Referer")
                    .all { request.header(it) == null },
                host = request.url.scheme == "https" && request.url.port == 443 &&
                    request.url.username.isEmpty() && request.url.password.isEmpty() && (source || image))
            row.actualVariant = request.url.queryParameter("plan")?.takeIf { it == "h160" || it == "h360" }
                ?: if (source) "none" else "other_or_original"
            row.variantCorrect = source || row.actualVariant == row.phase.variant
            rows += row
            requireSmoke(row.userAgent && row.headers && row.host && row.variantCorrect, "outgoing_request_policy")
            val started = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                row.status = response.code
                row.mime = response.header("Content-Type")?.substringBefore(';')?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) } ?: "other"
                row.error = when {
                    response.code == 403 -> "denied"
                    response.code == 404 -> "not_found"
                    response.code == 429 -> "rate_limited"
                    response.code >= 400 -> "http"
                    image && response.code == 200 && !row.mime.startsWith("image/") -> "unexpected_mime"
                    else -> null
                }
                if (response.isRedirect) {
                    val target = response.header("Location")?.let(request.url::resolve)
                    row.redirectApproved = image && target != null && AnitabiImageReference.normalize(target.toString()) == target.toString()
                }
                val body = response.body
                val counted = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        super.read(sink, byteCount).also { count ->
                            if (count > 0) requireSmoke(row.bytes.addAndGet(count) <=
                                (if (source) 16L else 4L) * 1024 * 1024, "body_limit")
                            row.elapsed.set(SystemClock.elapsedRealtime() - started)
                        }
                    } catch (error: IOException) { row.error = coarseFailure(error); throw error }
                }.buffer()
                return response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = counted
                }).build()
            } catch (error: IOException) {
                row.error = coarseFailure(error)
                row.elapsed.set(SystemClock.elapsedRealtime() - started)
                throw error
            }
        }
    }

    private data class Phase(val name: String, val sample: Int = 0, val family: String = "none", val prefix: String = "none", val variant: String = "none") {
        fun write(target: kotlinx.serialization.json.JsonObjectBuilder) = with(target) {
            put("stage", name); put("sample", sample); put("family", family); put("prefixKind", prefix); put("variant", variant)
        }
    }
    private data class Sample(val subjectId: Long, val rawPointId: String?, val rawReference: String,
        val storedReference: String, val family: String, val prefixKind: String)
    private data class DecodeObservation(val phase: Phase, val success: Boolean, val source: String?, val width: Int, val height: Int, val error: String?)
    private class NetworkObservation(val phase: Phase, val userAgent: Boolean, val headers: Boolean, val host: Boolean) {
        @Volatile var status = 0
        @Volatile var mime = "other"
        @Volatile var redirectApproved = true
        @Volatile var actualVariant = "none"
        @Volatile var variantCorrect = false
        @Volatile var error: String? = null
        val bytes = AtomicLong()
        val elapsed = AtomicLong()
    }
    private class SmokeFailure(val category: String) : IOException(category)
    companion object {
        private fun requireSmoke(condition: Boolean, category: String) { if (!condition) throw SmokeFailure(category) }
        private fun JsonElement?.sourceText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun coarseFailure(error: Throwable): String {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            causes.filterIsInstance<SmokeFailure>().firstOrNull()?.let { return it.category }
            return when {
                causes.any { it is UnknownHostException } -> "dns"
                causes.any { it is SSLException } -> "tls"
                causes.any { it is SocketTimeoutException } -> "timeout"
                causes.any { it is ApiException.InvalidResponse } -> "source_format"
                causes.any { it is ApiException.Http } -> "http"
                causes.any { it is IOException } -> "network"
                else -> "decode_or_ui"
            }
        }
    }
}
