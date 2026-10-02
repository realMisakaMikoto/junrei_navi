package cn.anitabi.navigator.data.images

import cn.anitabi.navigator.data.discovery.DiscoveryLoadCache
import cn.anitabi.navigator.data.discovery.DiscoveryLoadEndpoint
import cn.anitabi.navigator.data.discovery.DiscoveryLoadError
import cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.GzipSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppImageTransportTraceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun disabledReturnsExactClientWithNoNewListenersInterceptorsOrClockCalls() {
        val trace = DiscoveryLoadTrace(nanoTime = { error("Disabled clock called") })
        val client = OkHttpClient()
        assertSame(client, client.withImageTrace(trace))
        assertTrue(trace.snapshot().events.isEmpty())
    }

    @Test fun realGzipResponseCountsConsumedDecompressedBytesAndKeepsExistingListener() {
        MockWebServer().use { server ->
            server.start()
            val plain = "synthetic-image-body".repeat(20)
            val compressed = Buffer().also { target -> GzipSink(target).buffer().use { it.writeUtf8(plain) } }
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
            var calls = 0
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { calls++ }
            }).build()
            val trace = enabled()
            client.withImageTrace(trace).newCall(Request.Builder().url(server.url("/fixture")).build()).execute().use {
                assertEquals(plain, it.body.string())
            }
            val result = trace.snapshot()
            val request = result.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
            assertEquals(1, calls)
            assertEquals(1L, request.completedCount)
            assertEquals(plain.toByteArray().size.toLong(), request.receivedBytes)
            assertEquals(mapOf(200 to 1L), request.statusCodes)
            assertEquals(mapOf(DiscoveryLoadOutcome.OBSERVED to 1L), request.outcomes)
            assertEquals(DiscoveryLoadPhase.IMAGE_FETCH, result.events.single().phase)
            assertTrue(result.pendingSpans.isEmpty())
            assertFalse(trace.toJson().contains("fixture"))
        }
    }

    @Test fun followedRedirectIsOneHttpCallWithFinalStatusAndExistingBehavior() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final")))
            server.enqueue(MockResponse().setResponseCode(404).setBody("synthetic-missing"))
            val trace = enabled()
            OkHttpClient().withImageTrace(trace).newCall(Request.Builder().url(server.url("/initial")).build()).execute().use {
                assertEquals(404, it.code)
                assertEquals("synthetic-missing", it.body.string())
            }
            val request = trace.snapshot().requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
            assertEquals(2, server.requestCount)
            assertEquals(1L, request.completedCount)
            assertEquals(mapOf(404 to 1L), request.statusCodes)
            assertEquals(mapOf(DiscoveryLoadError.NOT_FOUND to 1L), request.errors)
        }
    }

    @Test fun actualHttpCacheReadIsSeparateFromNetworkWhileBytesRemainBodyConsumption() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("cached-fixture").setHeader("Cache-Control", "max-age=3600"))
            Cache(temporary.newFolder("http-cache"), 1024 * 1024).use { cache ->
                val trace = enabled()
                val client = OkHttpClient.Builder().cache(cache).build().withImageTrace(trace)
                repeat(2) {
                    client.newCall(Request.Builder().url(server.url("/fixture")).build()).execute().use { response ->
                        assertEquals("cached-fixture", response.body.string())
                    }
                }
                val summary = trace.snapshot().requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
                assertEquals(1, server.requestCount)
                assertEquals(mapOf(DiscoveryLoadCache.NETWORK to 1L, DiscoveryLoadCache.DISK to 1L), summary.caches)
                assertEquals(28L, summary.receivedBytes)
            }
        }
    }

    @Test fun partialBodyCancellationIsCountedOnceWithOnlyBytesActuallyRead() {
        val trace = enabled()
        val delivered = AtomicLong()
        val client = fixtureClient(delivered).withImageTrace(trace)
        val call = client.newCall(Request.Builder().url("https://example.com/fixture").build())
        call.execute().use { response ->
            response.body.source().readByte()
            assertTrue(delivered.get() in 1 until 65_536)
            call.cancel()
        }
        val result = trace.snapshot()
        val summary = result.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
        assertEquals(1L, summary.completedCount)
        assertEquals(delivered.get(), summary.receivedBytes)
        assertEquals(mapOf(DiscoveryLoadOutcome.CANCELLED to 1L), summary.outcomes)
        assertTrue(summary.errors.isEmpty())
        assertTrue(result.pendingSpans.isEmpty())
    }

    @Test fun failureDuringBodyReadPreservesPartialBytesAndTimeoutCategory() {
        val trace = enabled()
        val delivered = AtomicLong()
        val call = fixtureClient(delivered, failAfterFirstRead = true).withImageTrace(trace)
            .newCall(Request.Builder().url("https://example.com/fixture").build())
        assertThrows(SocketTimeoutException::class.java) { call.execute().use { it.body.bytes() } }
        val summary = trace.snapshot().requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
        assertEquals(delivered.get(), summary.receivedBytes)
        assertTrue(delivered.get() > 0)
        assertEquals(1L, summary.completedCount)
        assertEquals(mapOf(DiscoveryLoadError.TIMEOUT to 1L), summary.errors)
        assertTrue(trace.snapshot().pendingSpans.isEmpty())
    }

    @Test fun failureBeforeHeadersKeepsByteAndStatusMeasurementsUnknown() {
        val trace = enabled()
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic failure") }
            .build().withImageTrace(trace)
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://example.com/fixture").build()).execute()
        }
        val summary = trace.snapshot().requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
        assertNull(summary.receivedBytes)
        assertTrue(summary.statusCodes.isEmpty())
        assertEquals(mapOf(DiscoveryLoadError.NETWORK to 1L), summary.errors)
    }

    private fun enabled() = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true))

    private fun fixtureClient(delivered: AtomicLong, failAfterFirstRead: Boolean = false) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val source = object : Source {
                var remaining = 65_536L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (failAfterFirstRead && delivered.get() > 0) throw SocketTimeoutException("Synthetic body timeout")
                    if (remaining == 0L) return -1
                    val count = minOf(byteCount, remaining, 512).toInt()
                    sink.write(ByteArray(count))
                    remaining -= count
                    delivered.addAndGet(count.toLong())
                    return count.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 65_536L
                override fun source(): BufferedSource = source
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic").body(body).build()
        }.build()
}
