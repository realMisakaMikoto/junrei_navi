package cn.anitabi.navigator.diagnostics

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.staticCompositionLocalOf
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadSnapshot
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMapAdapter
import java.io.Closeable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val LocalDiscoveryTrace = staticCompositionLocalOf { DiscoveryLoadTrace() }

@Serializable
data class DiscoveryWindowFrame(
    val totalNanos: Long,
    val firstDraw: Boolean,
    val unknownDelayNanos: Long?,
    val layoutNanos: Long?,
    val drawNanos: Long?,
)

@Serializable
private data class DiscoveryDiagnosticsSnapshot(
    val apiLevel: Int,
    val shellProfilingSupported: Boolean,
    val trace: DiscoveryLoadSnapshot,
    /** Window frames include the application UI, not a count of the SDK's private GL frames. */
    val windowFrames: List<DiscoveryWindowFrame>,
    val discardedWindowFrames: Long,
    val memorySamplingIntervalMillis: Long = 500,
)

/** No listeners, files, logging or telemetry are created unless the build explicitly opts in. */
class DiscoveryDiagnostics(enabled: Boolean) {
    val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = enabled, maxEvents = 1024))
    private val lock = Any()
    private val frames = ArrayList<DiscoveryWindowFrame>()
    private var discardedFrames = 0L
    private var epoch = 0L
    private var startNanos = if (enabled) System.nanoTime() else 0L
    private val json = Json { encodeDefaults = true }
    @Volatile internal var visualFrame: DiscoveryVisualFrame? = null
        private set
    internal var visualFrameObserver: ((DiscoveryVisualFrame?) -> Unit)? = null

    internal fun publishVisualFrame(frame: DiscoveryVisualFrame) {
        if (!BuildConfig.DISCOVERY_MEASUREMENT) return
        check(Looper.myLooper() == Looper.getMainLooper())
        visualFrame = frame
        notifyVisualFrame(frame)
    }

    internal fun clearVisualFrame(adapter: DiscoveryMapAdapter) {
        if (!BuildConfig.DISCOVERY_MEASUREMENT || visualFrame?.adapter !== adapter) return
        check(Looper.myLooper() == Looper.getMainLooper())
        visualFrame = null
        notifyVisualFrame(null)
    }

    private fun notifyVisualFrame(frame: DiscoveryVisualFrame?) {
        val observer = visualFrameObserver ?: return
        val notify = Runnable { if (visualFrame === frame) runCatching { observer(frame) } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run()
        else Handler(Looper.getMainLooper()).post(notify)
    }

    fun reset() = synchronized(lock) {
        if (!trace.enabled) return@synchronized
        epoch++
        startNanos = System.nanoTime()
        frames.clear()
        discardedFrames = 0
        trace.reset()
        visualFrame = null
        notifyVisualFrame(null)
    }

    fun snapshotJson(): String = synchronized(lock) {
        json.encodeToString(DiscoveryDiagnosticsSnapshot.serializer(), DiscoveryDiagnosticsSnapshot(
            Build.VERSION.SDK_INT, Build.VERSION.SDK_INT >= 29, trace.snapshot(), frames.toList(), discardedFrames,
        ))
    }

    fun observeWindow(window: Window): Closeable {
        if (!trace.enabled) return Closeable { }
        val thread = HandlerThread("Discovery window metrics").apply { start() }
        val handler = Handler(thread.looper)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            // Read scalar values now: Android reuses this FrameMetrics object after the callback.
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(lock) {
                if (intended >= startNanos && total >= 0) {
                    val frame = DiscoveryWindowFrame(total,
                        metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                        metrics.available(FrameMetrics.UNKNOWN_DELAY_DURATION),
                        metrics.available(FrameMetrics.LAYOUT_MEASURE_DURATION),
                        metrics.available(FrameMetrics.DRAW_DURATION))
                    if (frames.size < 6000) frames += frame else discardedFrames++
                    trace.increment(DiscoveryLoadCounter.FRAME_COUNT)
                    trace.increment(DiscoveryLoadCounter.FRAME_TOTAL_DURATION_NANOS, total)
                    if (frame.firstDraw) trace.increment(DiscoveryLoadCounter.FIRST_DRAW_FRAME_COUNT)
                    frame.unknownDelayNanos?.let { trace.increment(DiscoveryLoadCounter.FRAME_UNKNOWN_DELAY_NANOS, it) }
                    frame.layoutNanos?.let { trace.increment(DiscoveryLoadCounter.FRAME_LAYOUT_MEASURE_NANOS, it) }
                    frame.drawNanos?.let { trace.increment(DiscoveryLoadCounter.FRAME_DRAW_NANOS, it) }
                    trace.increment(DiscoveryLoadCounter.FRAME_REPORT_DROPPED_COUNT, dropped.toLong())
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        val memory = object : Runnable {
            override fun run() {
                val sampleEpoch = synchronized(lock) { epoch }
                val runtime = Runtime.getRuntime()
                val javaBytes = runtime.totalMemory() - runtime.freeMemory()
                val nativeBytes = Debug.getNativeHeapAllocatedSize()
                val pssBytes = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024 }.getOrNull()
                synchronized(lock) {
                    if (epoch == sampleEpoch) {
                        trace.increment(DiscoveryLoadCounter.JAVA_HEAP_SAMPLE_COUNT)
                        trace.maximum(DiscoveryLoadCounter.JAVA_HEAP_PEAK_BYTES, javaBytes)
                        trace.increment(DiscoveryLoadCounter.NATIVE_HEAP_SAMPLE_COUNT)
                        trace.maximum(DiscoveryLoadCounter.NATIVE_HEAP_PEAK_BYTES, nativeBytes)
                        pssBytes?.let {
                            trace.increment(DiscoveryLoadCounter.PSS_SAMPLE_COUNT)
                            trace.maximum(DiscoveryLoadCounter.PSS_PEAK_BYTES, it)
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(memory)
        return Closeable {
            window.removeOnFrameMetricsAvailableListener(listener)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }
}

private fun FrameMetrics.available(metric: Int): Long? = getMetric(metric).takeIf { it >= 0 }
