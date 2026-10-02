package cn.anitabi.navigator.data.discovery

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.LogManager
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer

/** Standalone, loopback-only synthetic data host. It has no HTTP client or upstream forwarding. */
object ControlledDiscoveryMeasurementServer {
    private const val PORT = 18443
    private const val POINTS_PER_SUBJECT = 128
    private const val SUBJECTS_PER_PAGE = 16
    private val sizes = listOf(1_000, 10_000, 100_000, 51_828)
    private val resources = listOf(
        "/points/synthetic-measurement/photo-0.png", "/user/synthetic-measurement/photo-1.png",
        "/bangumi/synthetic-measurement/photo-2.png", "/points/synthetic-measurement/photo-3.png",
        "/user/synthetic-measurement/photo-4.png", "/bangumi/synthetic-measurement/photo-5.png",
    )

    // Authored fixture centers, never observations of a person or production pilgrimage data.
    private enum class Provider(val latitude: Double, val longitude: Double, val subjectBase: Long) {
        GOOGLE(35.65, 139.72, 900_000_000L),
        AMAP(31.16, 121.43, 900_100_000L),
    }
    private enum class DataProfile { NORMAL, DELAYED }
    private enum class ImageProfile { NORMAL, THROTTLED }
    private enum class Endpoint { LAYOUT, INDEX, PAGE, SUBJECT, IMAGE, REJECTED }
    private data class ImagePayload(val width: Int, val height: Int, val bytes: ByteArray)
    private data class Dataset(
        val provider: Provider, val size: Int, val subjectCount: Int, val index: ByteArray,
        val pages: List<ByteArray>, val subjects: Map<Long, ByteArray>, val layout: ByteArray, val sha256: String,
    )
    private data class CountKey(val provider: String, val size: Int, val profile: String, val endpoint: Endpoint, val status: Int)
    private class Count { val requests = AtomicLong(); val scheduledBytes = AtomicLong() }

    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 1)
            serve(File(args.single()).canonicalFile)
        } catch (failure: Throwable) {
            // Exception messages can contain request data; only a coarse class is allowed here.
            System.err.println("Controlled discovery fixture failed: ${failure.javaClass.simpleName}")
            if (failure is NoClassDefFoundError && failure.message?.matches(Regex("[A-Za-z0-9_.$/]+")) == true) {
                System.err.println("Missing host runtime class: ${failure.message}")
            }
            exitProcess(1)
        }
    }

    private fun serve(directory: File) {
        require(!directory.exists())
        check(directory.mkdirs())
        LogManager.getLogManager().reset()
        val datasets = Provider.entries.flatMap { provider -> sizes.map { size -> buildDataset(provider, size) } }
        val byDataset = datasets.associateBy { it.provider to it.size }
        val images = resources.indices.flatMap { slot -> listOf("h160" to (240 to 160), "h360" to (540 to 360)).map { (plan, dimensions) ->
            (resources[slot] to plan) to ImagePayload(dimensions.first, dimensions.second, png(dimensions.first, dimensions.second, slot))
        } }.toMap()
        check(images.values.all { it.bytes.size > 32 * 1024 })
        val certificate = HeldCertificate.Builder().commonName("Synthetic discovery measurement")
            .addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val counts = ConcurrentHashMap<CountKey, Count>()
        fun response(key: CountKey, body: ByteArray = byteArrayOf(), mime: String = "application/json"): MockResponse {
            counts.computeIfAbsent(key) { Count() }.also {
                it.requests.incrementAndGet(); it.scheduledBytes.addAndGet(body.size.toLong())
            }
            return MockResponse().setResponseCode(key.status).setHeader("Content-Type", mime)
                .setHeader("Cache-Control", if (key.endpoint == Endpoint.IMAGE) "public, max-age=86400" else "no-store")
                .setBody(Buffer().write(body))
        }
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl
                    fun rejected(status: Int) = response(CountKey("none", 0, "none", Endpoint.REJECTED, status))
                    if (request.method != "GET" || url?.host != "localhost" ||
                        listOf("Authorization", "Cookie", "X-Goog-Api-Key", "X-Amap-Key").any { request.getHeader(it) != null }
                    ) return rejected(403)
                    val parts = url.pathSegments
                    if (parts.firstOrNull() != "fixture") return rejected(404)
                    val imageProfile = parts.getOrNull(1)?.let { value -> ImageProfile.entries.find { it.name == value } }
                    if (imageProfile != null) {
                        val path = "/" + parts.drop(2).joinToString("/")
                        val plans = url.queryParameterValues("plan")
                        if (plans.size != 1 || url.queryParameterNames.any { it !in setOf("plan", "v") }) return rejected(400)
                        val plan = plans.single()?.takeIf { it == "h160" || it == "h360" } ?: return rejected(400)
                        val image = images[path to plan] ?: return rejected(404)
                        return response(CountKey("none", 0, imageProfile.name, Endpoint.IMAGE, 200), image.bytes, "image/png")
                            .apply { if (imageProfile == ImageProfile.THROTTLED) throttleBody(16_384, 150, TimeUnit.MILLISECONDS) }
                    }
                    val provider = parts.getOrNull(1)?.let { value -> Provider.entries.find { it.name == value } } ?: return rejected(404)
                    val size = parts.getOrNull(2)?.toIntOrNull() ?: return rejected(404)
                    val dataset = byDataset[provider to size] ?: return rejected(404)
                    if (parts.size == 4 && parts[3] == "layout.json") {
                        return response(CountKey(provider.name, size, "CONFIG", Endpoint.LAYOUT, 200), dataset.layout)
                    }
                    val profile = parts.getOrNull(3)?.let { value -> DataProfile.entries.find { it.name == value } } ?: return rejected(404)
                    val tail = parts.drop(4)
                    val payload: Pair<Endpoint, ByteArray> = when {
                        tail == listOf("d", "g.json") -> Endpoint.INDEX to dataset.index
                        tail.size == 2 && tail[0] == "d" && tail[1].matches(Regex("g[0-9]+\\.json")) -> {
                            val page = tail[1].removePrefix("g").removeSuffix(".json").toIntOrNull() ?: return rejected(404)
                            Endpoint.PAGE to (dataset.pages.getOrNull(page) ?: return rejected(404))
                        }
                        tail.size == 4 && tail[0] == "bangumi" && tail[2] == "points" && tail[3] == "detail" -> {
                            val subject = tail[1].toLongOrNull() ?: return rejected(404)
                            Endpoint.SUBJECT to (dataset.subjects[subject] ?: return rejected(404))
                        }
                        else -> return rejected(404)
                    }
                    return response(CountKey(provider.name, size, profile.name, payload.first, 200), payload.second)
                        .apply { if (profile == DataProfile.DELAYED) setHeadersDelay(750, TimeUnit.MILLISECONDS) }
                }
            }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT)
            File(directory, "certificate.pem").writeText(certificate.certificatePem())
            File(directory, "ready.json").writeText(buildJsonObject {
                put("schema", 1); put("port", PORT); put("controlledHttps", true)
                put("fixtureVersion", 1)
                put("certificatePublicKeyAlgorithm", certificate.certificate.publicKey.algorithm)
                put("certificateSignatureAlgorithm", certificate.certificate.sigAlgName)
                put("certificateFile", "certificate.pem"); put("privateKeyPersisted", false)
                put("upstreamRequestsSupported", false); put("uniqueImages", resources.size)
                put("productionParserValidated", true)
                put("datasets", buildJsonArray { datasets.forEach { dataset -> add(buildJsonObject {
                    put("provider", dataset.provider.name); put("size", dataset.size)
                    put("subjectCount", dataset.subjectCount); put("pageSize", SUBJECTS_PER_PAGE); put("pageCount", dataset.pages.size)
                    put("indexBytes", dataset.index.size); put("pageBytes", dataset.pages.sumOf { it.size.toLong() })
                    put("datasetSha256", dataset.sha256); put("indexSha256", sha256(dataset.index))
                }) } })
                put("images", buildJsonArray { images.entries.forEachIndexed { index, (_, image) -> add(buildJsonObject {
                    put("variantIndex", index); put("width", image.width); put("height", image.height)
                    put("bytes", image.bytes.size); put("sha256", sha256(image.bytes))
                }) } })
                put("dataDelayMillis", 750); put("imageThrottleBytes", 16_384); put("imageThrottlePeriodMillis", 150)
            }.toString())
            println("Controlled discovery measurement fixture ready")
            val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(3)
            while (!File(directory, "stop").isFile && System.nanoTime() < deadline) Thread.sleep(250)
        }
        File(directory, "request-summary.json").writeText(buildJsonObject {
            put("schema", 1); put("rawRequestDataSaved", false)
            put("byteDefinition", "Scheduled response body bytes; not a measurement of bytes actually consumed")
            put("requests", buildJsonArray {
                counts.entries.sortedBy { (key, _) -> "${key.provider}:${key.size}:${key.profile}:${key.endpoint}:${key.status}" }
                    .forEach { (key, count) -> add(buildJsonObject {
                        put("provider", key.provider); put("size", key.size); put("profile", key.profile)
                        put("endpoint", key.endpoint.name); put("status", key.status)
                        put("count", count.requests.get()); put("scheduledBodyBytes", count.scheduledBytes.get())
                    }) }
            })
        }.toString())
        println("Controlled discovery measurement fixture stopped")
    }

    private fun buildDataset(provider: Provider, size: Int): Dataset {
        val subjectCount = (size + POINTS_PER_SUBJECT - 1) / POINTS_PER_SUBJECT
        val modified = 1_700_000_000_000L + provider.ordinal * 1_000_000L + size
        val indexRows = buildJsonArray {
            repeat(subjectCount) { subjectIndex ->
                val first = subjectIndex * POINTS_PER_SUBJECT
                val count = minOf(POINTS_PER_SUBJECT, size - first)
                add(JsonArray(listOf(
                    JsonPrimitive(provider.subjectBase + subjectIndex), JsonPrimitive("Synthetic subject $subjectIndex"),
                    JsonPrimitive("Synthetic alias $subjectIndex"), JsonPrimitive("Synthetic subject $subjectIndex"),
                    JsonPrimitive("SYNTHETIC_AREA"), JsonPrimitive("#426b62"),
                    JsonPrimitive(imageReference(if (subjectIndex % 2 == 0) 2 else 5)),
                    JsonPrimitive(0), JsonPrimitive("TV"), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(count),
                    buildJsonArray { repeat(count) { offset ->
                        val point = first + offset
                        val coordinate = coordinate(provider, point)
                        add(JsonPrimitive(pointId(point))); add(JsonPrimitive(coordinate.first)); add(JsonPrimitive(coordinate.second))
                        add(JsonPrimitive(if (point < 6) 0 else 10))
                    } }, JsonPrimitive(0), JsonArray(emptyList()), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0),
                )))
            }
        }
        val index = JsonArray(listOf(indexRows, JsonPrimitive(SUBJECTS_PER_PAGE), JsonPrimitive(modified))).bytes()
        val subjects = LinkedHashMap<Long, ByteArray>()
        val pages = (0 until subjectCount).chunked(SUBJECTS_PER_PAGE).map { shard ->
            buildJsonArray {
                shard.forEach { subjectIndex ->
                    val first = subjectIndex * POINTS_PER_SUBJECT
                    val count = minOf(POINTS_PER_SUBJECT, size - first)
                    val subjectId = provider.subjectBase + subjectIndex
                    val points = buildJsonArray { repeat(count) { offset ->
                        val point = first + offset
                        add(JsonArray(listOf(JsonPrimitive(pointId(point)), JsonPrimitive("Synthetic point $point"), JsonNull,
                            JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(imageReference(point % 6)),
                            JsonNull, JsonPrimitive(point % 12 + 1), JsonPrimitive(0), JsonPrimitive("Synthetic fixture detail"),
                            JsonNull, JsonNull, JsonNull, JsonPrimitive(0))))
                    } }
                    subjects[subjectId] = buildJsonArray { repeat(count) { offset ->
                        val point = first + offset
                        add(buildJsonObject {
                            put("id", pointId(point)); put("name", "Synthetic point $point")
                            put("image", imageReference(point % 6)); put("ep", point % 12 + 1)
                            put("mark", "Synthetic fixture detail")
                        })
                    } }.bytes()
                    add(JsonArray(listOf(JsonPrimitive(subjectId), JsonArray(emptyList()), points, JsonPrimitive(modified))))
                }
            }.bytes()
        }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(index); pages.forEach(::update) }.digest().hex()
        validateDataset(index, pages, size)
        val layout = buildJsonObject {
            put("schema", 1); put("provider", provider.name); put("size", size)
            put("centerLatitude", provider.latitude); put("centerLongitude", provider.longitude); put("zoom", 15.0)
            put("subjectCount", subjectCount); put("pageSize", SUBJECTS_PER_PAGE); put("pageCount", pages.size)
            put("uniqueImages", resources.size); put("datasetSha256", digest); put("indexSha256", sha256(index))
            put("singletonPhotoPointIds", buildJsonArray { repeat(6) { add(JsonPrimitive("${provider.subjectBase}::${pointId(it)}")) } })
        }.bytes()
        return Dataset(provider, size, subjectCount, index, pages, subjects, layout, digest)
    }

    private fun coordinate(provider: Provider, point: Int): Pair<Double, Double> {
        val offsets = if (point < 6) arrayOf(
            0.0 to .012, 0.0 to -.012, .009 to .006, .009 to -.006, -.009 to .006, -.009 to -.006,
        )[point] else (((point - 6) % 9 / 3 - 1) * .0008) to (((point - 6) % 3 - 1) * .0008)
        return provider.latitude + offsets.first to provider.longitude + offsets.second
    }

    private fun pointId(point: Int) = "synthetic-point-$point"
    private fun imageReference(slot: Int) = "/images${resources[slot]}?v=measurement-v1"
    private fun kotlinx.serialization.json.JsonElement.bytes() = toString().toByteArray(Charsets.UTF_8)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun validateDataset(indexBytes: ByteArray, pages: List<ByteArray>, expectedSize: Int) {
        val index = DiscoveryParser.index(Json.parseToJsonElement(indexBytes.toString(Charsets.UTF_8)))
        check(index.points.size == expectedSize && index.pageSize == SUBJECTS_PER_PAGE && index.pageCount == pages.size)
        check(index.points.map { it.coordinate }.distinct().size == 15)
        pages.forEachIndexed { page, bytes ->
            val expected = index.subjects.drop(page * index.pageSize).take(index.pageSize).associateBy { it.id }
            val parsed = DiscoveryParser.page(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
            check(parsed.map { it.subjectId }.toSet() == expected.keys)
            parsed.forEach { subject ->
                check(subject.points.map { "${subject.subjectId}::${it.rawId}" }.toSet() == expected.getValue(subject.subjectId).pointIds.toSet())
                check(subject.points.all { !it.isFolder && it.imageUrl != null })
            }
        }
    }

    /** Deterministic authored RGB noise keeps both variants large enough to exercise throttling. */
    private fun png(width: Int, height: Int, slot: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, payload: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            output.writeInt(payload.size); output.write(type); output.write(payload)
            output.writeInt(CRC32().apply { update(type); update(payload) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use {
            it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0))
        } }.toByteArray()
        chunk("IHDR", header)
        var random = 0x13579bdf xor (slot * 0x1f123bb5)
        val data = ByteArrayOutputStream().also { raw -> DeflaterOutputStream(raw).use { deflater ->
            repeat(height) {
                deflater.write(0)
                repeat(width * 3) {
                    random = random xor (random shl 13)
                    random = random xor (random ushr 17)
                    random = random xor (random shl 5)
                    deflater.write(random and 255)
                }
            }
        } }.toByteArray()
        chunk("IDAT", data); chunk("IEND", byteArrayOf())
        bytes.toByteArray()
    }
}
