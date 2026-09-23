package cn.anitabi.navigator.data.images

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
        val certificate = HeldCertificate.Builder().commonName("Synthetic image transport").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val resources = setOf("/points/synthetic-owner/upload.png", "/user/synthetic-owner/upload.png", "/bangumi/synthetic-work/cover.png")
        val body = png()
        val observations = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        val retryRequests = java.util.concurrent.atomic.AtomicInteger()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val retry = path == "/points/synthetic-owner/retry.png"
                    val status = if (path in resources || retry && retryRequests.incrementAndGet() > 1) 200 else 404
                    observations += path.startsWith("/images/") to status
                    return if (status == 200) MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(body))
                    else MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Synthetic missing resource")
                }
            }
            server.start()
            File(directory, "certificate.pem").writeText(certificate.certificatePem())
            File(directory, "ready.json").writeText(buildJsonObject {
                put("port", server.port); put("controlledHttps", true); put("pngBytes", body.size)
                put("privateKeyPersisted", false)
            }.toString())
            println("Controlled HTTPS image fixture ready")
            val deadline = System.nanoTime() + 15L * 60 * 1_000_000_000
            while (!File(directory, "stop").exists() && System.nanoTime() < deadline) Thread.sleep(100)
        }
        File(directory, "requests.json").writeText(buildJsonArray {
            observations.forEach { (prefix, status) -> add(buildJsonObject {
                put("extraImagesPrefix", prefix); put("controlledHttpStatus", status)
            }) }
        }.toString())
        println("Controlled HTTPS image fixture stopped")
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
