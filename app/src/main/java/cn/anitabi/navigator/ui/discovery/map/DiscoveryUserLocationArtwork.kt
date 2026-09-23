package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil

internal data class DiscoveryUserLocationStyle(val density: Float, val dark: Boolean, val headingAvailable: Boolean)

/** North points up; rotation belongs to the SDK marker, never to a newly rasterized bitmap. */
internal fun discoveryUserLocationArtwork(style: DiscoveryUserLocationStyle): DiscoveryMarkerBitmap {
    val scale = style.density
    val size = ceil(40f * scale).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val blue = if (style.dark) Color.rgb(138, 180, 248) else Color.rgb(23, 105, 224)
    val outline = if (style.dark) Color.rgb(32, 34, 38) else Color.WHITE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (style.headingAvailable) {
        val arrow = Path().apply {
            moveTo(center, center - 17f * scale)
            lineTo(center - 10f * scale, center - 5f * scale)
            lineTo(center + 10f * scale, center - 5f * scale)
            close()
        }
        paint.color = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * scale
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = blue
        canvas.drawPath(arrow, paint)
    }
    paint.color = outline
    canvas.drawCircle(center, center, 9f * scale, paint)
    paint.color = blue
    canvas.drawCircle(center, center, 6f * scale, paint)
    return DiscoveryMarkerBitmap(bitmap, .5f, .5f)
}
