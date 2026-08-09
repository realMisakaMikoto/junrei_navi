package cn.anitabi.navigator.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.net.toUri
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TravelMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AmapExternalNavigationLauncher internal constructor(
    private val sourceApplication: String,
    private val urlFactory: AmapRouteUrlFactory,
    private val starter: AmapRouteStarter,
) {
    constructor(context: Context) : this(
        sourceApplication = context.packageName,
        urlFactory = AndroidAmapRouteUrlFactory,
        starter = AndroidAmapRouteStarter(context),
    )

    fun launch(
        originWgs84: GeoPoint,
        destinationWgs84: GeoPoint,
        originName: String,
        destinationName: String,
        mode: TravelMode,
    ): Boolean {
        val url = urlFactory.build(
            amapRouteUrlSpec(
                sourceApplication = sourceApplication,
                originWgs84 = originWgs84,
                destinationWgs84 = destinationWgs84,
                originName = originName,
                destinationName = destinationName,
                mode = mode,
            ),
        )
        return starter.start(url, AMAP_PACKAGE)
    }

    internal companion object {
        const val AMAP_PACKAGE = "com.autonavi.minimap"
    }
}

internal fun interface AmapRouteUrlFactory {
    fun build(spec: AmapRouteUrlSpec): String
}

internal fun interface AmapRouteStarter {
    fun start(url: String, packageName: String): Boolean
}

internal data class AmapRouteUrlSpec(
    val scheme: String,
    val authority: String,
    val path: String,
    val queryParameters: List<Pair<String, String>>,
)

internal fun amapRouteUrlSpec(
    sourceApplication: String,
    originWgs84: GeoPoint,
    destinationWgs84: GeoPoint,
    originName: String,
    destinationName: String,
    mode: TravelMode,
): AmapRouteUrlSpec {
    val safeSourceApplication = sourceApplication.validatedAmapText("sourceApplication", 128)
    val safeOriginName = originName.validatedAmapText("origin name", 256)
    val safeDestinationName = destinationName.validatedAmapText("destination name", 256)
    return AmapRouteUrlSpec(
        scheme = "amapuri",
        authority = "route",
        path = "/plan/",
        queryParameters = listOf(
            "sourceApplication" to safeSourceApplication,
            "sname" to safeOriginName,
            "slat" to originWgs84.latitude.toAmapCoordinate(),
            "slon" to originWgs84.longitude.toAmapCoordinate(),
            "dname" to safeDestinationName,
            "dlat" to destinationWgs84.latitude.toAmapCoordinate(),
            "dlon" to destinationWgs84.longitude.toAmapCoordinate(),
            "dev" to "1",
            "t" to mode.amapRouteType(),
        ),
    )
}

private fun String.validatedAmapText(field: String, maxLength: Int): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "$field must not be blank" }
    require(normalized.length <= maxLength) { "$field is too long" }
    require(normalized.none(Char::isISOControl)) { "$field contains control characters" }
    return normalized
}

internal fun Double.toAmapCoordinate(): String {
    require(isFinite()) { "AMap coordinate must be finite" }
    val plain = BigDecimal.valueOf(this)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    return if (plain == "-0") "0" else plain
}

private fun TravelMode.amapRouteType(): String = when (this) {
    TravelMode.DRIVE -> "0"
    TravelMode.TRANSIT -> "1"
    TravelMode.WALK -> "2"
    TravelMode.BIKE -> "3"
}

private object AndroidAmapRouteUrlFactory : AmapRouteUrlFactory {
    override fun build(spec: AmapRouteUrlSpec): String = amapRouteUrl(spec)
}

internal fun amapRouteUrl(spec: AmapRouteUrlSpec): String = buildString {
    append(spec.scheme)
    append("://")
    append(spec.authority)
    append(spec.path)
    append('?')
    append(
        spec.queryParameters.joinToString("&") { (name, value) ->
            "${name.encodeQueryValue()}=${value.encodeQueryValue()}"
        },
    )
}

private fun String.encodeQueryValue(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private class AndroidAmapRouteStarter(
    private val context: Context,
) : AmapRouteStarter {
    override fun start(url: String, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            setPackage(packageName)
            if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startResolvableAmapIntent(
            hasHandler = intent.resolveActivity(context.packageManager) != null,
        ) {
            context.startActivity(intent)
        }
    }
}

internal fun startResolvableAmapIntent(
    hasHandler: Boolean,
    startActivity: () -> Unit,
): Boolean {
    if (!hasHandler) return false
    return try {
        startActivity()
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
