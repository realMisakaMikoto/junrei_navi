package cn.anitabi.navigator.data.images

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates

/** Test-only routing preserves logical HTTPS hosts, so the production redirect guard is exercised. */
internal class ControlledImageTransport(context: Context) {
    private val port = requireNotNull(InstrumentationRegistry.getArguments().getString("imageFixturePort")?.toIntOrNull())
    private val certificate = File(context.getExternalFilesDir(null), "frontend-fix-v2/fixture-cert.pem").inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    val dnsHosts = CopyOnWriteArrayList<String>()
    val responses = CopyOnWriteArrayList<Response>()
    val client: OkHttpClient

    init {
        require(port in 1..65535)
        require(certificate.subjectAlternativeNames.any { it.getOrNull(1) == "image.anitabi.cn" }) {
            "Controlled image fixture protocol 2 is required"
        }
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        client = createAppImageHttpClient().newBuilder()
            .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .dns { host ->
                dnsHosts += host
                check(host == "image.anitabi.cn" || host == "lain.bgm.tv") { "Unexpected fixture DNS lookup" }
                listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        val address = endpoint as? InetSocketAddress ?: error("Unexpected fixture socket address")
                        check(address.port == 443 && address.address.isLoopbackAddress)
                        super.connect(InetSocketAddress(address.address, this@ControlledImageTransport.port), timeout)
                    }
                    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
                }
                override fun createSocket(host: String, port: Int): Socket = unsupported()
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
                override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
                private fun unsupported(): Nothing = error("Fixture requires an unconnected socket")
            })
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                responses += Response(
                    response.code, request.url.host == "image.anitabi.cn",
                    request.url.encodedPath.startsWith("/_fixture/"),
                    request.url.encodedPath.startsWith("/images/"),
                    request.url.queryParameter("plan"),
                    listOf("Authorization", "Cookie", "X-Goog-Api-Key").all { request.header(it) == null },
                )
                response
            }.build()
    }

    fun control(action: String) {
        require(action in setOf("await-cancel", "release-cancel", "await-late-a", "release-late-a", "await-late-a-released"))
        client.newCall(Request.Builder().url("https://image.anitabi.cn/_fixture/$action").build()).execute().use {
            check(it.code == 204) { "Controlled response barrier was not satisfied" }
        }
    }

    data class Response(val status: Int, val anitabi: Boolean, val control: Boolean, val legacyPrefix: Boolean,
        val plan: String?, val sensitiveHeadersAbsent: Boolean)
}
