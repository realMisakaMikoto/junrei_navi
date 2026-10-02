package cn.anitabi.navigator.measurement

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.diagnostics.DiscoveryVisualFrame
import com.amap.api.maps.MapView
import com.google.android.libraries.navigation.NavigationView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read-only native pixels. Shared SDK icon bitmaps are referenced, never recycled or exported. */
internal class MeasurementVisualProbe(private val application: DiscoveryMeasurementApplication) {
    private val diagnostics get() = application.discoveryDiagnostics
    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    private var workerThread: HandlerThread? = null
    private var activity = WeakReference<MainActivity>(null)
    private var resumed = false
    private var frame: DiscoveryVisualFrame? = null
    private var matchedFrame: DiscoveryVisualFrame? = null
    private var origin: Long? = null
    private var epoch = 0L
    private var inFlight = false
    private var samples = 0
    private var failures = 0
    private var stale = 0
    private var eligible = 0
    private var matched = 0
    private var eligiblePhotos = 0
    private var matchedPhotos = 0
    private var matchedMembers = 0L
    private var copyNanos = 0L
    private var matcherNanos = 0L
    private var firstPoints: Long? = null
    private var firstImage: Long? = null
    private var reason = "no_committed_frame"
    private var method = "NONE"
    private var lastCopyResult: Int? = null
    private val sample = Runnable { capture() }

    fun resume(value: MainActivity) {
        activity = WeakReference(value)
        resumed = true
        observe(diagnostics.visualFrame)
    }

    fun pause(value: MainActivity) {
        if (activity.get() !== value) return
        resumed = false
        epoch++
        frame = null
        matchedFrame = null
        main.removeCallbacks(sample)
        activity.clear()
        releaseWorkerIfIdle()
    }

    fun observe(value: DiscoveryVisualFrame?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronizeInterval()
        frame = value
        matchedFrame = null
        eligible = 0; matched = 0; eligiblePhotos = 0; matchedPhotos = 0; matchedMembers = 0
        main.removeCallbacks(sample)
        if (value == null) reason = "no_committed_frame" else schedule()
    }

    private fun synchronizeInterval() {
        val current = diagnostics.trace.snapshot().originNanos
        if (current == origin) return
        origin = current
        epoch++
        samples = 0; failures = 0; stale = 0; copyNanos = 0; matcherNanos = 0
        firstPoints = null; firstImage = null; matchedFrame = null
        lastCopyResult = null; method = "NONE"
        frame = null; eligible = 0; matched = 0; eligiblePhotos = 0; matchedPhotos = 0; matchedMembers = 0
        reason = "no_committed_frame"
        main.removeCallbacks(sample)
    }

    fun snapshot(): JsonObject {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronizeInterval()
        val current = frame?.takeIf { resumed && it === diagnostics.visualFrame && it.isCurrent() }
        return buildJsonObject {
            put("schema", 1)
            put("outcome", if (current != null && matchedFrame === current) "OBSERVED" else "NOT_OBSERVED")
            put("reason", reason); put("captureMethod", method)
            put("currentMarkerCount", current?.markers?.size ?: 0)
            put("currentMemberCount", current?.markers?.sumOf { it.memberCount.toLong() } ?: 0)
            put("eligibleMarkerCount", eligible); put("matchedMarkerCount", matched)
            put("committedPhotoCount", current?.markers?.count { it.isImage } ?: 0)
            put("eligiblePhotoCount", eligiblePhotos); put("matchedPhotoCount", matchedPhotos)
            put("matchedMemberCount", matchedMembers)
            put("allCurrentMarkerPixelsMatch", current != null && matchedFrame === current && matched == current.markers.size)
            put("sampleCount", samples); put("copyFailureCount", failures); put("staleSampleCount", stale)
            put("sampleInFlight", inFlight)
            put("lastCopyResult", lastCopyResult?.let(::JsonPrimitive) ?: JsonNull)
            put("copyNanos", copyNanos); put("matcherNanos", matcherNanos)
            put("firstViewportPointsDrawnOffsetNanos", firstPoints?.let(::JsonPrimitive) ?: JsonNull)
            put("firstVisibleImageOffsetNanos", firstImage?.let(::JsonPrimitive) ?: JsonNull)
            put("timingDefinition", "first_verified_copy_upper_bound_including_sampling_cost")
            put("geographicBasemap", "NOT_OBSERVED")
            put("screenCompositionObserved", method == "WINDOW_TEXTURE_PIXEL_COPY")
            put("mapWidth", current?.width ?: 0); put("mapHeight", current?.height ?: 0)
            put("contentWidth", current?.let { it.content.right - it.content.left } ?: 0f)
            put("contentHeight", current?.let { it.content.bottom - it.content.top } ?: 0f)
            put("maxDestinationBytes", MAX_PIXELS * 4)
        }
    }

    private fun schedule() {
        if (!resumed || frame == null || inFlight) return
        if (samples >= MAX_SAMPLES || origin?.let { System.nanoTime() - it >= WATCHDOG_NANOS } != false) {
            reason = "visual_watchdog_or_sample_limit"
            return
        }
        main.postDelayed(sample, 100)
    }

    private fun capture() {
        val current = frame ?: return
        val owner = activity.get()
        if (!resumed || owner == null || !owner.hasWindowFocus() || !current.isCurrent()) {
            reason = "inactive_or_invalidated_frame"; schedule(); return
        }
        val views = descendants(owner.window.decorView).filter { it.isShown && it.isAttachedToWindow &&
            (it is NavigationView || it is MapView) }
        val map = views.singleOrNull()
        if (map == null || (current.adapter.provider == MapProvider.GOOGLE) != (map is NavigationView)) {
            reason = "native_view_not_unique"; schedule(); return
        }
        if (map.width != current.width || map.height != current.height) {
            reason = "native_layout_changed"; schedule(); return
        }
        if (current.markers.size > 64) { reason = "render_plan_marker_bound"; return }
        val patches = runCatching { patches(current) }.getOrElse {
            reason = "projection_or_icon_unavailable"; schedule(); return
        }
        eligible = patches.size; eligiblePhotos = patches.count { it.image }
        if (patches.isEmpty()) { reason = "no_fully_visible_icons"; schedule(); return }
        val area = Rect(patches.first().bounds).also { rect -> patches.drop(1).forEach { rect.union(it.bounds) } }
        if (area.width().toLong() * area.height() > MAX_PIXELS) {
            reason = "destination_memory_bound"; return
        }
        val nativeChildren = descendants(map).filter { it.isShown && it.width > 0 && it.height > 0 }
        val surfaces = nativeChildren.filterIsInstance<SurfaceView>()
        val textures = nativeChildren.filterIsInstance<TextureView>().filter { it.isAvailable }
        val surface = surfaces.singleOrNull()
        val texture = textures.singleOrNull()
        if (!(surface != null && textures.isEmpty() || surfaces.isEmpty() && texture != null)) {
            reason = "unsupported_or_ambiguous_renderer"; schedule(); return
        }
        val offset = IntArray(2).also(map::getLocationInWindow)
        val source = Rect(area)
        if (surface != null) {
            val location = IntArray(2).also(surface::getLocationInWindow)
            source.offset(offset[0] - location[0], offset[1] - location[1])
            if (surface.holder.surfaceFrame.width() != surface.width || surface.holder.surfaceFrame.height() != surface.height ||
                !surface.holder.surface.isValid || !Rect(0, 0, surface.width, surface.height).contains(source)) {
                reason = "surface_not_ready_or_scaled"; schedule(); return
            }
            method = "SURFACE_VIEW_PIXEL_COPY"
        } else {
            val location = IntArray(2).also(requireNotNull(texture)::getLocationInWindow)
            source.offset(offset[0], offset[1])
            if (!Rect(location[0], location[1], location[0] + texture.width, location[1] + texture.height).contains(source)) {
                reason = "texture_does_not_cover_samples"; return
            }
            method = "WINDOW_TEXTURE_PIXEL_COPY"
        }
        val destination = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        val callbackHandler = worker ?: Handler(HandlerThread("Discovery pixel copy").also {
            it.start(); workerThread = it
        }.looper).also { worker = it }
        val captureEpoch = epoch
        val started = System.nanoTime()
        inFlight = true
        samples++
        val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
            val copied = System.nanoTime()
            val observed = if (result == PixelCopy.SUCCESS) patches.map { matches(destination, it, area) } else patches.map { false }
            val finished = System.nanoTime()
            destination.recycle() // Only this destination is owned; shared icon bitmaps remain untouched.
            main.post {
                inFlight = false
                if (epoch == captureEpoch) {
                    copyNanos += copied - started; matcherNanos += finished - copied
                    lastCopyResult = result
                }
                if (epoch != captureEpoch) Unit
                else if (frame !== current || diagnostics.visualFrame !== current ||
                    activity.get() !== owner || !resumed || !owner.hasWindowFocus() || !map.isShown || !map.isAttachedToWindow ||
                    surface?.let { !it.isAttachedToWindow || !it.holder.surface.isValid } == true ||
                    texture?.let { !it.isAttachedToWindow || !it.isAvailable } == true || !current.isCurrent()) stale++
                else {
                    if (result != PixelCopy.SUCCESS) { failures++; reason = "pixel_copy_error" }
                    else {
                        matched = observed.count { it }; matchedPhotos = patches.indices.count { observed[it] && patches[it].image }
                        matchedMembers = patches.indices.filter { observed[it] }.sumOf { patches[it].members.toLong() }
                        if (matched > 0 && firstPoints == null) {
                            firstPoints = System.nanoTime() - requireNotNull(origin)
                            diagnostics.trace.mark(DiscoveryLoadPhase.FIRST_VIEWPORT_POINTS_DRAWN, itemCount = matchedMembers)
                        }
                        if (matchedPhotos > 0 && firstImage == null) {
                            firstImage = System.nanoTime() - requireNotNull(origin)
                            diagnostics.trace.mark(DiscoveryLoadPhase.FIRST_VISIBLE_IMAGE, itemCount = matchedPhotos.toLong())
                        }
                        matchedFrame = current.takeIf { matched == current.markers.size }
                        reason = if (matchedFrame != null) "current_icons_matched" else "current_icons_not_yet_matched"
                    }
                }
                if (matchedFrame !== frame) schedule()
                releaseWorkerIfIdle()
            }
        }
        try {
            if (surface != null) PixelCopy.request(surface, source, destination, listener, callbackHandler)
            else PixelCopy.request(owner.window, source, destination, listener, callbackHandler)
        } catch (_: IllegalArgumentException) {
            inFlight = false; destination.recycle(); failures++; reason = "pixel_copy_source_unavailable"; schedule()
        }
    }

    private fun releaseWorkerIfIdle() {
        if (resumed || inFlight) return
        workerThread?.quitSafely()
        workerThread = null
        worker = null
    }

    private data class Pixel(val x: Int, val y: Int, val color: Int)
    private data class Patch(val bounds: Rect, val pixels: List<Pixel>, val glyph: List<Pixel>, val image: Boolean, val members: Int)

    private fun patches(current: DiscoveryVisualFrame): List<Patch> = current.markers.mapNotNull { marker ->
        val bitmap = marker.icon.bitmap
        if (bitmap.isRecycled) return@mapNotNull null
        val point = current.adapter.project(marker.displayCoordinate)
        val left = (point.x - marker.icon.anchorX * bitmap.width).roundToInt()
        val top = (point.y - marker.icon.anchorY * bitmap.height).roundToInt()
        val bounds = Rect(left, top, left + bitmap.width, top + bitmap.height)
        if (bounds.left < current.content.left || bounds.top < current.content.top ||
            bounds.right > current.content.right || bounds.bottom > current.content.bottom) return@mapNotNull null
        fun grid(rect: Rect, columns: Int, rows: Int): List<Pixel> = buildList {
            repeat(rows) { row -> repeat(columns) { column ->
                val x = (rect.left + (column + .5f) * rect.width() / columns).toInt().coerceIn(0, bitmap.width - 1)
                val y = (rect.top + (row + .5f) * rect.height() / rows).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) == 255) add(Pixel(x, y, color))
            } }
        }
        val pixels = grid(Rect(0, 0, bitmap.width, bitmap.height), 24, 24)
        val anchor = (marker.icon.anchorY * bitmap.height).roundToInt()
        val glyph = if (marker.memberCount > 1) grid(Rect((bitmap.width * .12f).toInt(),
            (anchor - bitmap.height * .16f).roundToInt(), (bitmap.width * .88f).toInt(),
            (anchor + bitmap.height * .16f).roundToInt()), 40, 20) else emptyList()
        if (pixels.size < 20) null else Patch(bounds, pixels, glyph, marker.isImage, marker.memberCount)
    }

    private fun matches(bitmap: Bitmap, patch: Patch, area: Rect): Boolean {
        fun ratio(pixels: List<Pixel>, dx: Int, dy: Int): Float = if (pixels.isEmpty()) 1f else pixels.count { reference ->
            val x = patch.bounds.left - area.left + reference.x + dx
            val y = patch.bounds.top - area.top + reference.y + dy
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) false else {
                val actual = bitmap.getPixel(x, y)
                abs(Color.red(actual) - Color.red(reference.color)) <= 28 &&
                    abs(Color.green(actual) - Color.green(reference.color)) <= 28 &&
                    abs(Color.blue(actual) - Color.blue(reference.color)) <= 28
            }
        }.toFloat() / pixels.size
        return (-2..2).any { dx -> (-2..2).any { dy ->
            ratio(patch.pixels, dx, dy) >= .96f && ratio(patch.glyph, dx, dy) >= .98f
        } }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private companion object {
        const val MAX_PIXELS = 4_000_000
        const val MAX_SAMPLES = 1_200
        const val WATCHDOG_NANOS = 120_000_000_000L
    }
}
