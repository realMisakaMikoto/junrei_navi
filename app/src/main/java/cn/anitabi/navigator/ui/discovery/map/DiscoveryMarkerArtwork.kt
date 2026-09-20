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
import kotlin.math.roundToInt

/** The circle centre or the image's connecting dot is the exact geographic anchor. */
internal fun discoveryMarkerArtwork(
    cluster: DiscoveryCluster,
    point: DiscoveryMapPoint,
    image: Bitmap?,
    density: Float,
    dark: Boolean,
): DiscoveryMarkerBitmap {
    val decorated = cluster.decoration == DiscoveryMarkerDecoration.LABEL ||
        (cluster.decoration == DiscoveryMarkerDecoration.IMAGE && image != null)
    val widthDp = if (!decorated) 48f else if (image != null) 88f else 140f
    val heightDp = if (!decorated) 48f else if (image != null) 76f else 34f
    val width = (widthDp * density).roundToInt().coerceAtLeast(1)
    val height = (heightDp * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val foreground = if (dark) Color.rgb(243, 241, 238) else Color.rgb(36, 36, 38)
    val surface = if (dark) Color.rgb(40, 40, 42) else Color.WHITE
    val centerX = width / 2f
    val anchorY = if (decorated) height - 4f * density else height / 2f

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
                textSize = 12f * density
                textAlign = Paint.Align.CENTER
            }
            val text = TextUtils.ellipsize(point.title, textPaint, card.width() - 16f * density, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, centerX, card.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
        paint.color = point.colorArgb
        paint.strokeWidth = 2f * density
        canvas.drawLine(centerX, card.bottom, centerX, anchorY, paint)
    }

    val radius = when {
        decorated -> 4f
        cluster.memberIds.size > 1 -> 19f
        cluster.selected -> 11f
        else -> 8f
    } * density
    paint.color = if (cluster.selected) foreground else surface
    canvas.drawCircle(centerX, anchorY, radius + (if (cluster.selected) 3f else 2f) * density, paint)
    paint.color = if (cluster.memberIds.size > 1) foreground else point.colorArgb
    canvas.drawCircle(centerX, anchorY, radius, paint)
    if (cluster.memberIds.size > 1) {
        paint.textSize = (if (cluster.memberIds.size >= 10000) 10f else 13f) * density
        paint.textAlign = Paint.Align.CENTER
        paint.color = surface
        canvas.drawText(cluster.memberIds.size.toString(), centerX, anchorY - (paint.ascent() + paint.descent()) / 2, paint)
        if (cluster.selected) {
            paint.color = surface
            canvas.drawCircle(centerX + 12f * density, anchorY - 12f * density, 4f * density, paint)
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
