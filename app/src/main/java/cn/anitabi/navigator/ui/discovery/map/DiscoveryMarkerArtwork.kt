package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class DiscoveryMarkerLayout(
    val width: Int,
    val height: Int,
    val anchorY: Float,
    val radius: Float,
    val decorated: Boolean,
) {
    val bounds: ScreenRect get() = ScreenRect(-width / 2f, -anchorY, width / 2f, height - anchorY)
}

/** Shared by bitmap drawing and collision checks, including scalable text and transparent padding. */
internal fun discoveryMarkerLayout(
    cluster: DiscoveryCluster,
    density: Density,
    imageAvailable: Boolean,
): DiscoveryMarkerLayout {
    val scale = density.density
    val textSize = with(density) { 14.sp.toPx() }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize }
    val metrics = textPaint.fontMetrics
    val decorated = cluster.memberIds.size == 1 && (cluster.decoration == DiscoveryMarkerDecoration.LABEL ||
        (cluster.decoration == DiscoveryMarkerDecoration.IMAGE && imageAvailable))
    val radius = when {
        decorated -> 4f * scale
        cluster.memberIds.size > 1 -> maxOf(19f * scale, hypot(
            textPaint.measureText(cluster.memberIds.size.toString()) / 2 + 4f * scale,
            (metrics.bottom - metrics.top) / 2 + 4f * scale,
        ))
        cluster.selected -> 11f * scale
        else -> 8f * scale
    }
    val width = ceil(when {
        !decorated -> maxOf(48f * scale, 2 * (radius + 4f * scale))
        imageAvailable -> 88f * scale
        else -> maxOf(140f * scale, 120f * textSize / 14f + 20f * scale)
    }).toInt()
    val height = ceil(when {
        !decorated -> maxOf(48f * scale, 2 * (radius + 4f * scale))
        imageAvailable -> 80f * scale
        else -> maxOf(48f * scale, metrics.bottom - metrics.top + 27f * scale)
    }).toInt()
    // Preserve the image card's original 72 dp anchor and add canvas below its connecting dot.
    val anchorY = when {
        !decorated -> height / 2f
        imageAvailable -> 72f * scale
        else -> height - 8f * scale
    }
    return DiscoveryMarkerLayout(width, height, anchorY, radius, decorated)
}

/** The circle centre or the image's connecting dot is the exact geographic anchor. */
internal fun discoveryMarkerArtwork(
    cluster: DiscoveryCluster,
    point: DiscoveryMapPoint,
    image: Bitmap?,
    fontDensity: Density,
    dark: Boolean,
): DiscoveryMarkerBitmap {
    val density = fontDensity.density
    val layout = discoveryMarkerLayout(cluster, fontDensity, image != null)
    val decorated = layout.decorated
    val width = layout.width
    val height = layout.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val foreground = if (dark) Color.rgb(243, 241, 238) else Color.rgb(36, 36, 38)
    val surface = if (dark) Color.rgb(40, 40, 42) else Color.WHITE
    val centerX = width / 2f
    val anchorY = layout.anchorY

    if (decorated) {
        val card = RectF(2f * density, 2f * density, width - 2f * density, anchorY - 7f * density)
        paint.color = surface
        canvas.drawRoundRect(card, 9f * density, 9f * density, paint)
        paint.color = if (cluster.selected) foreground else point.colorArgb
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (cluster.selected) 3f else 2f) * density
        canvas.drawRoundRect(card, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.FILL
        if (image != null) {
            val photo = RectF(card.left + 3f * density, card.top + 3f * density, card.right - 3f * density, card.bottom - 3f * density)
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(photo, 6f * density, 6f * density, Path.Direction.CW) })
            val scale = maxOf(photo.width() / image.width, photo.height() / image.height)
            val sourceWidth = (photo.width() / scale).roundToInt().coerceIn(1, image.width)
            val sourceHeight = (photo.height() / scale).roundToInt().coerceIn(1, image.height)
            val left = (image.width - sourceWidth) / 2
            val top = (image.height - sourceHeight) / 2
            canvas.drawBitmap(image, Rect(left, top, left + sourceWidth, top + sourceHeight), photo, paint)
            canvas.restoreToCount(save)
        } else {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                textSize = with(fontDensity) { 14.sp.toPx() }
                textAlign = Paint.Align.CENTER
            }
            val text = TextUtils.ellipsize(point.title, textPaint, card.width() - 16f * density, TextUtils.TruncateAt.END).toString()
            val metrics = textPaint.fontMetrics
            canvas.drawText(text, centerX, card.centerY() - (metrics.top + metrics.bottom) / 2, textPaint)
        }
        paint.color = point.colorArgb
        paint.strokeWidth = 2f * density
        canvas.drawLine(centerX, card.bottom, centerX, anchorY, paint)
    }

    val radius = layout.radius
    paint.color = if (cluster.selected) foreground else surface
    canvas.drawCircle(centerX, anchorY, radius + (if (cluster.selected) 3f else 2f) * density, paint)
    paint.color = if (cluster.memberIds.size > 1) foreground else point.colorArgb
    canvas.drawCircle(centerX, anchorY, radius, paint)
    if (cluster.memberIds.size > 1) {
        paint.textSize = with(fontDensity) { 14.sp.toPx() }
        paint.textAlign = Paint.Align.CENTER
        paint.color = surface
        val metrics = paint.fontMetrics
        canvas.drawText(cluster.memberIds.size.toString(), centerX, anchorY - (metrics.top + metrics.bottom) / 2, paint)
        if (cluster.selected) {
            paint.color = surface
            canvas.drawCircle(centerX + radius * .72f, anchorY - radius * .72f, 4f * density, paint)
        }
    } else if (cluster.selected && !decorated) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(Path().apply {
            moveTo(centerX - 5f * density, anchorY)
            lineTo(centerX - 1f * density, anchorY + 4f * density)
            lineTo(centerX + 6f * density, anchorY - 4f * density)
        }, paint)
    }
    return DiscoveryMarkerBitmap(bitmap, .5f, anchorY / height)
}
