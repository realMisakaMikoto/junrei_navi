package cn.anitabi.navigator.data.images

import java.net.URI
import java.net.URLDecoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AnitabiImageVariant(val plan: String?) { THUMBNAIL("h160"), DISPLAY("h360"), ORIGINAL(null) }

/** Source-path repair is independent of the upstream discovery generation. */
object AnitabiImageReference {
    const val RULE_VERSION = 1
    private const val HOST = "image.anitabi.cn"
    private val knownFamilies = setOf("points", "user", "bangumi")
    private val variantQueryNames = setOf("plan", "v")

    fun normalize(value: String?): String? = parse(value)?.toString()

    fun request(value: String?, variant: AnitabiImageVariant): String? {
        val url = parse(value) ?: return null
        // Unknown query semantics may be signed. Preserve the resource rather than invalidate it.
        if (!url.queryParameterNames.all { it in variantQueryNames }) return url.toString()
        return url.newBuilder().removeAllQueryParameters("plan").apply {
            variant.plan?.let { addQueryParameter("plan", it) }
        }.build().toString()
    }

    /** Other image sources retain their existing, independent source validation. */
    fun displayModel(value: String?, variant: AnitabiImageVariant): String? {
        if (value == null) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val isAnitabi = value.startsWith("/") || uri.host.equals(HOST, ignoreCase = true)
        return if (isAnitabi) request(value, variant) else value
    }

    private fun parse(value: String?): HttpUrl? {
        if (value.isNullOrEmpty() || value.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
        if (value.startsWith("//")) return null
        val candidate = if (value.startsWith("/")) "https://$HOST$value" else value
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals(HOST, true) || uri.rawUserInfo != null ||
            uri.port !in listOf(-1, 443) || uri.rawFragment != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/") || path.contains("%25", ignoreCase = true)) return null
        for (segment in path.split('/')) {
            val decoded = runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            if (decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it < ' ' || it == '\u007f' }) return null
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        val segments = url.encodedPathSegments
        return if (segments.size >= 3 && segments[0] == "images" && segments[1] in knownFamilies) {
            url.newBuilder().removePathSegment(0).build()
        } else url
    }
}
