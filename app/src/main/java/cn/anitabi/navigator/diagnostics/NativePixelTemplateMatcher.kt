package cn.anitabi.navigator.diagnostics

internal data class NativePixelSample(val x: Int, val y: Int, val neighbors: IntArray)

/** Reference samples are captured on Main; the matcher reads only the owned pixel-copy destination. */
internal fun nativePixelTemplatesMatch(
    pixels: List<NativePixelSample>,
    glyph: List<NativePixelSample>,
    readPixel: (Int, Int) -> Int?,
): Boolean {
    fun expectedChannel(sample: NativePixelSample, shift: Int, fx: Float, fy: Float): Float {
        val x = -fx; val y = -fy
        val left = kotlin.math.floor(x).toInt(); val top = kotlin.math.floor(y).toInt()
        val ax = x - left; val ay = y - top
        fun channel(dx: Int, dy: Int) = (sample.neighbors[(top + dy + 1) * 3 + left + dx + 1] ushr shift and 255).toFloat()
        return (channel(0, 0) * (1 - ax) + channel(1, 0) * ax) * (1 - ay) +
            (channel(0, 1) * (1 - ax) + channel(1, 1) * ax) * ay
    }
    fun enough(samples: List<NativePixelSample>, threshold: Float, dx: Int, dy: Int, fx: Float, fy: Float): Boolean {
        var failures = 0
        for (sample in samples) {
            val actual = readPixel(sample.x + dx, sample.y + dy)
            val matched = actual != null && (0..16 step 8).all { shift ->
                kotlin.math.abs((actual ushr shift and 255) - expectedChannel(sample, shift, fx, fy)) <= 28
            }
            if (!matched) {
                failures++
                if ((samples.size - failures).toFloat() / samples.size < threshold) return false
            }
        }
        return true
    }
    fun at(dx: Int, dy: Int, fx: Float, fy: Float) =
        enough(pixels, .96f, dx, dy, fx, fy) && enough(glyph, .98f, dx, dy, fx, fy)
    if ((-2..2).any { dx -> (-2..2).any { dy -> at(dx, dy, 0f, 0f) } }) return true
    // SDK icons may land between screen pixels; compare the same template after bilinear translation.
    return (-2..2).any { dx -> (-2..2).any { dy -> (-4..4).any { fx -> (-4..4).any { fy ->
        at(dx, dy, fx / 8f, fy / 8f)
    } } } }
}
