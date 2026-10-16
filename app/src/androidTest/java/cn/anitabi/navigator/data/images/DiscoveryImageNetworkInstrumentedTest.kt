package cn.anitabi.navigator.data.images

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.createAppUserAgentInterceptor
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real production parser, Coil network fetch and Android decoder; only TLS transport is a fixture. */
@RunWith(AndroidJUnit4::class)
class DiscoveryImageNetworkInstrumentedTest {
    @Test fun staticImagePathsReachRealNetworkAndDecoder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixturePort = requireNotNull(InstrumentationRegistry.getArguments().getString("imageFixturePort")?.toIntOrNull())
        require(fixturePort in 1..65535)
        val certificate = File(context.getExternalFilesDir(null), "frontend-fix-v2/fixture-cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val resources = listOf("/points/synthetic-owner/upload.png", "/user/synthetic-owner/upload.png", "/bangumi/synthetic-work/cover.png")
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val responses = CopyOnWriteArrayList<ResponseObservation>()
        val outcomes = mutableListOf<Pair<Boolean, Boolean>>()
            val client = createAppImageHttpClient().newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .addInterceptor { chain ->
                    val original = chain.request()
                    check(original.url.scheme == "https" && original.url.host == "image.anitabi.cn")
                    val destination = HttpUrl.Builder().scheme("https").host("localhost").port(fixturePort)
                        .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
                    val response = chain.proceed(original.newBuilder().url(destination).build())
                    responses += ResponseObservation(
                        prefixed = original.url.encodedPath.startsWith("/images/"),
                        status = response.code,
                        imageMime = response.header("Content-Type") == "image/png",
                        bytes = response.body.contentLength(),
                        planCorrect = original.url.queryParameter("plan") == "h160",
                        userAgentCorrect = original.header("User-Agent") == createAppUserAgentInterceptor().value,
                        sensitiveHeadersAbsent = listOf("Authorization", "Cookie", "X-Goog-Api-Key").all { original.header(it) == null },
                    )
                    response
                }.build()
            val loader = createAppImageLoader(context, client)
            try {
                for (resource in resources) {
                    for (prefixed in listOf(false, true)) {
                        val path = if (prefixed) "/images$resource" else resource
                        val url = parsedPageImage("$path?plan=h160&v=synthetic-version")
                        val result = loader.execute(ImageRequest.Builder(context).data(url)
                            .size(8, 6).allowHardware(false)
                            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
                        val decoded = (result as? SuccessResult)?.image?.toBitmap()?.let { bitmap ->
                            bitmap.width == 8 && bitmap.height == 6 && bitmap.getPixel(3, 2) == Color.rgb(31, 113, 179)
                        } == true
                        outcomes += prefixed to decoded
                    }
                }
            } finally {
                loader.shutdown()
                val report = buildJsonObject {
                    put("controlledHttpsFixture", true)
                    put("productionLoaderFactory", true)
                    put("realPngDecoderControlPassed", outcomes.count { !it.first && it.second } == resources.size)
                    put("canonicalControlsDecoded", outcomes.count { !it.first && it.second })
                    put("prefixedInputsDecoded", outcomes.count { it.first && it.second })
                    put("requests", responses.size)
                    put("responseObservations", buildJsonArray {
                        responses.forEach { row -> add(buildJsonObject {
                            put("extraImagesPrefix", row.prefixed); put("controlledHttpStatus", row.status)
                            put("imageMime", row.imageMime); put("responseContentLength", row.bytes)
                            put("planCorrect", row.planCorrect); put("userAgentCorrect", row.userAgentCorrect)
                            put("sensitiveHeadersAbsent", row.sensitiveHeadersAbsent)
                        }) }
                    })
                    put("rawUrlsOrUserDataRetained", false)
                }
                val directory = File(context.getExternalFilesDir(null), "frontend-fix-v2").apply { mkdirs() }
                File(directory, "image-network.json").writeText(report.toString())
            }
        assertEquals("Every controlled fixture must issue its actual HTTPS request", 6, responses.size)
        assertTrue("Production user agent and credential isolation must be retained", responses.all { it.userAgentCorrect && it.sensitiveHeadersAbsent && it.planCorrect })
        assertEquals("Canonical positive controls must prove the real network/PNG decoder works", 3, outcomes.count { !it.first && it.second })
        assertEquals("Static image inputs must reach the canonical resource and real PNG decoder", 3, outcomes.count { it.first && it.second })
    }

    private fun parsedPageImage(path: String): String {
        val point = JsonArray(List(15) { JsonPrimitive(0) }.toMutableList().apply {
            this[0] = JsonPrimitive("synthetic-point")
            this[1] = JsonPrimitive("Synthetic image")
            this[6] = JsonPrimitive(path)
        })
        val subject = JsonArray(listOf(JsonPrimitive(1), JsonArray(emptyList()), JsonArray(listOf(point)), JsonPrimitive(100)))
        return requireNotNull(DiscoveryParser.page(JsonArray(listOf(subject))).single().points.single().imageUrl)
    }

    private data class ResponseObservation(
        val prefixed: Boolean, val status: Int, val imageMime: Boolean, val bytes: Long,
        val planCorrect: Boolean, val userAgentCorrect: Boolean, val sensitiveHeadersAbsent: Boolean,
    )
}
