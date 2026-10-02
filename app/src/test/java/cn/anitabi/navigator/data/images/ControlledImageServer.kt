package cn.anitabi.navigator.data.images

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.LogManager
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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

/** Standalone host fixture. No @Test: ordinary unit suites never start a waiting server. */
object ControlledImageServer {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = File(args.single()).apply { require(!exists()); check(mkdirs()) }
        LogManager.getLogManager().reset()
        val certificate = HeldCertificate.Builder().commonName("Synthetic image transport")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("image.anitabi.cn")
            .addSubjectAlternativeName("lain.bgm.tv").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val resources = setOf("/points/synthetic-owner/upload.png", "/user/synthetic-owner/upload.png", "/bangumi/synthetic-work/cover.png")
        val body = png()
        val observations = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        val retryRequests = AtomicInteger()
        val cancelRequests = AtomicInteger()
        val cancellation = ResponseGate()
        val lateFailure = ResponseGate()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    fun observed(status: Int, response: MockResponse): MockResponse {
                        observations += path.startsWith("/images/") to status
                        return response.setResponseCode(status)
                    }
                    val gateControl = when (path) {
                        "/_fixture/await-cancel" -> cancellation.awaitStarted()
                        "/_fixture/release-cancel" -> cancellation.release()
                        "/_fixture/await-late-a" -> lateFailure.awaitStarted()
                        "/_fixture/release-late-a" -> lateFailure.release()
                        "/_fixture/await-late-a-released" -> lateFailure.awaitReleased()
                        else -> null
                    }
                    if (gateControl != null) return observed(if (gateControl) 204 else 408, MockResponse())
                    val redirect = when (path) {
                        "/points/synthetic-owner/redirect-allowed.png" -> "https://image.anitabi.cn/points/synthetic-owner/upload.png?plan=h160&v=redirect-fixture"
                        "/points/synthetic-owner/redirect-chain.png" -> "https://image.anitabi.cn/points/synthetic-owner/redirect-blocked.png?plan=h160"
                        "/points/synthetic-owner/redirect-blocked.png" -> "https://denied-image.invalid/never.png"
                        "/points/synthetic-owner/redirect-http.png" -> "http://image.anitabi.cn/points/synthetic-owner/upload.png"
                        "/points/synthetic-owner/redirect-credentials.png" -> "https://synthetic:fixture@image.anitabi.cn/points/synthetic-owner/upload.png"
                        else -> null
                    }
                    if (redirect != null) return observed(302, MockResponse().setHeader("Location", redirect))
                    if (path == "/points/synthetic-owner/late-a.png") {
                        val released = lateFailure.block()
                        return observed(if (released) 404 else 408, MockResponse().setHeader("Content-Type", "text/plain").setBody("Synthetic late missing resource"))
                    }
                    if (path == "/points/synthetic-owner/cancel.png" && cancelRequests.incrementAndGet() == 1 && !cancellation.block()) {
                        return observed(408, MockResponse())
                    }
                    val retry = path == "/points/synthetic-owner/retry.png"
                    val status = if (path in resources || path in setOf("/points/synthetic-owner/cache.png", "/points/synthetic-owner/cancel.png", "/pic/cover/l/synthetic.png") ||
                        retry && retryRequests.incrementAndGet() > 1) 200 else 404
                    observations += path.startsWith("/images/") to status
                    return if (status == 200) MockResponse().setHeader("Content-Type", "image/png")
                        .setHeader("Cache-Control", if (path == "/points/synthetic-owner/cache.png") "public, max-age=86400" else "no-store")
                        .setBody(Buffer().write(body))
                    else MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Synthetic missing resource")
                }
            }
            server.start()
            File(directory, "certificate.pem").writeText(certificate.certificatePem())
            File(directory, "ready.json").writeText(buildJsonObject {
                put("port", server.port); put("controlledHttps", true); put("pngBytes", body.size)
                put("privateKeyPersisted", false)
                put("fixtureProtocolVersion", 2)
            }.toString())
            println("Controlled HTTPS image fixture ready")
            val deadline = System.nanoTime() + 15L * 60 * 1_000_000_000
            while (!File(directory, "stop").exists() && System.nanoTime() < deadline) Thread.sleep(100)
            cancellation.release()
            lateFailure.release()
        }
        File(directory, "requests.json").writeText(buildJsonArray {
            observations.forEach { (prefix, status) -> add(buildJsonObject {
                put("extraImagesPrefix", prefix); put("controlledHttpStatus", status)
            }) }
        }.toString())
        println("Controlled HTTPS image fixture stopped")
    }

    private class ResponseGate {
        private val started = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun awaitStarted(): Boolean = started.await(15, TimeUnit.SECONDS)
        fun release(): Boolean { proceed.countDown(); return true }
        fun awaitReleased(): Boolean = released.await(15, TimeUnit.SECONDS)
        fun block(): Boolean {
            started.countDown()
            return proceed.await(30, TimeUnit.SECONDS).also { released.countDown() }
        }
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { bytes ->
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, payload: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            output.writeInt(payload.size); output.write(type); output.write(payload)
            output.writeInt(CRC32().apply { update(type); update(payload) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use {
            it.writeInt(8); it.writeInt(6); it.write(byteArrayOf(8, 6, 0, 0, 0))
        } }.toByteArray()
        chunk("IHDR", header)
        val compressed = ByteArrayOutputStream().also { raw -> DeflaterOutputStream(raw).use { deflater ->
            repeat(6) { deflater.write(0); repeat(8) { deflater.write(byteArrayOf(31, 113, -77, -1)) } }
        } }.toByteArray()
        chunk("IDAT", compressed); chunk("IEND", byteArrayOf())
        bytes.toByteArray()
    }
}
