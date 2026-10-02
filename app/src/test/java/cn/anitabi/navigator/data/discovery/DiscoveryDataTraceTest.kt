package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryDataTraceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeProductionDataPathRecordsSeparateTransportJsonModelCacheAndVerifiedCompletion() = runTest {
        val trace = enabled()
        val documents = listOf(indexFixture(), pageFixture(listOf(1, 2)), pageFixture(listOf(3)), indexFixture())
        var request = 0
        val source = HttpDiscoverySource(userAgent(), OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), documents[request++].toString())
        }, trace)
        val cache = FileDiscoveryCache(temporary.root, trace)
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0, trace = trace)

        repository.refresh()!!.join()

        assertTrue(repository.state.value.detailsCurrent)
        val recorded = trace.snapshot()
        assertEquals(4, request)
        assertEquals(2L, recorded.requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX).completedCount)
        assertEquals(2L, recorded.requests.endpoint(DiscoveryLoadEndpoint.STATIC_PAGE).completedCount)
        assertEquals(documents.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }.toLong(),
            recorded.requests.sumOf { it.receivedBytes ?: 0 })
        assertEquals(4, recorded.events.count { it.phase == DiscoveryLoadPhase.JSON_PARSE })
        assertEquals(2, recorded.events.count { it.phase == DiscoveryLoadPhase.INDEX_PARSE })
        assertEquals(2, recorded.events.count { it.phase == DiscoveryLoadPhase.PAGE_PARSE })
        assertEquals(4L, recorded.counters[DiscoveryLoadCounter.CACHE_WRITE_COUNT])
        assertEquals(DiscoveryLoadOutcome.EMPTY, recorded.events.first { it.phase == DiscoveryLoadPhase.CACHE_READ }.outcome)
        val completion = recorded.events.single { it.phase == DiscoveryLoadPhase.DETAILS_SYNC_COMPLETE }
        assertEquals(3L, completion.itemCount)
        assertTrue(completion.sequence > recorded.events.last { it.phase == DiscoveryLoadPhase.INDEX_PARSE }.sequence)
        assertTrue(completion.sequence > recorded.events.last { it.phase == DiscoveryLoadPhase.CACHE_WRITE }.sequence)
        assertTrue(recorded.pendingSpans.isEmpty())
        val encoded = trace.toJson()
        assertFalse(encoded.contains("Synthetic"))
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("shared"))
    }

    @Test fun partialPagesAndChangedEndIndexNeverClaimDetailsSyncComplete() = runTest {
        for (changedEnd in listOf(false, true)) {
            val trace = enabled()
            var indexCalls = 0
            val source = object : DiscoverySource {
                override suspend fun index(cacheToken: String) = indexFixture(
                    modified = if (++indexCalls > 1 && changedEnd) 200 else 100,
                )
                override suspend fun page(page: Int, cacheToken: String) = when {
                    page == 1 && !changedEnd -> Json.parseToJsonElement("[]")
                    else -> pageFixture(if (page == 0) listOf(1, 2) else listOf(3))
                }
                override suspend fun subject(subjectId: Long): JsonElement = error("No subject request expected")
            }
            val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0, trace = trace)
            repository.refresh()!!.join()
            assertFalse(repository.state.value.detailsCurrent)
            assertEquals(2, indexCalls)
            assertTrue(trace.snapshot().events.none { it.phase == DiscoveryLoadPhase.DETAILS_SYNC_COMPLETE })
            assertTrue(trace.snapshot().pendingSpans.isEmpty())
        }
    }

    @Test fun serialQueueTraceRetainsTheProductionOneSecondSpacing() = runTest {
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true), nanoTime = { testScheduler.currentTime * 1_000_000 })
        val starts = mutableListOf<Long>()
        val source = object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement {
                starts += testScheduler.currentTime
                return indexFixture()
            }
            override suspend fun page(page: Int, cacheToken: String): JsonElement {
                starts += testScheduler.currentTime
                return pageFixture(if (page == 0) listOf(1, 2) else listOf(3))
            }
            override suspend fun subject(subjectId: Long): JsonElement = error("No subject request expected")
        }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this,
            now = { testScheduler.currentTime }, trace = trace)
        repository.refresh()!!.join()
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), starts)
        assertEquals(listOf(0L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L),
            trace.snapshot().events.filter { it.phase == DiscoveryLoadPhase.REQUEST_QUEUE_WAIT }.map { it.durationNanos })
    }

    @Test fun cancelledQueuedRequestIsNotACompletedTransportOrParse() = runTest {
        val trace = enabled()
        val pageEntered = CompletableDeferred<Unit>()
        val releasePage = CompletableDeferred<Unit>()
        var subjectCalls = 0
        val source = object : DiscoverySource {
            override suspend fun index(cacheToken: String) = indexFixture()
            override suspend fun page(page: Int, cacheToken: String): JsonElement {
                pageEntered.complete(Unit)
                releasePage.await()
                return pageFixture(listOf(1, 2))
            }
            override suspend fun subject(subjectId: Long): JsonElement {
                subjectCalls++
                return Json.parseToJsonElement("[]")
            }
        }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0, trace = trace)
        val update = repository.refresh()!!
        pageEntered.await()
        val subject = async { repository.ensureSubjectDetails(1) }
        runCurrent()
        repository.setForeground(false)
        update.join()
        subject.join()
        assertEquals(0, subjectCalls)
        val state = trace.snapshot()
        assertTrue(state.events.any { it.phase == DiscoveryLoadPhase.REQUEST_QUEUE_WAIT && it.outcome == DiscoveryLoadOutcome.CANCELLED })
        assertTrue(state.events.none { it.phase == DiscoveryLoadPhase.SUBJECT_PARSE })
        assertTrue(state.pendingSpans.isEmpty())
    }

    @Test fun actualUtf8BodyBytesAndBomDecodeAreMeasuredWithoutRecordingContent() = runTest {
        val trace = enabled()
        val document = "\uFEFF{\"value\":\"\u4E2D\"}"
        val source = source(trace) { response(it, document) }
        assertEquals(Json.parseToJsonElement(document.removePrefix("\uFEFF")), source.index("abc"))
        val result = trace.snapshot()
        val request = result.requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX)
        assertEquals(document.toByteArray(Charsets.UTF_8).size.toLong(), request.receivedBytes)
        assertEquals(mapOf(200 to 1L), request.statusCodes)
        assertEquals(listOf(DiscoveryLoadPhase.INDEX_FETCH, DiscoveryLoadPhase.JSON_PARSE), result.events.map { it.phase })
        assertTrue(result.events.all { it.outcome == DiscoveryLoadOutcome.OBSERVED })
    }

    @Test fun malformedJsonSeparatesSuccessfulBodyFetchFromFailedJsonParse() = runTest {
        val trace = enabled()
        // JsonElement accepts a bare primitive; an unfinished object actually fails JSON decoding.
        val source = source(trace) { response(it, "{\"x\":") }
        assertTrue(runCatching { source.index("abc") }.exceptionOrNull() is ApiException.InvalidResponse)
        val state = trace.snapshot()
        assertEquals(DiscoveryLoadOutcome.OBSERVED, state.events.single { it.phase == DiscoveryLoadPhase.INDEX_FETCH }.outcome)
        assertEquals(DiscoveryLoadOutcome.FAILED, state.events.single { it.phase == DiscoveryLoadPhase.JSON_PARSE }.outcome)
        val summary = state.requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX)
        assertEquals(mapOf(200 to 1L), summary.statusCodes)
        assertEquals(mapOf(DiscoveryLoadError.INVALID_DATA to 1L), summary.errors)
        assertEquals(5L, summary.receivedBytes)
    }

    @Test fun acceptedJsonPrimitiveStillFailsTheActualDiscoveryModelParser() = runTest {
        val trace = enabled()
        val source = source(trace) { response(it, "not-json") }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0, trace = trace)
        repository.refresh()!!.join()
        assertNull(repository.state.value.snapshot)
        assertEquals(DiscoveryError.INVALID_DATA, repository.state.value.error)
        val state = trace.snapshot()
        assertEquals(DiscoveryLoadOutcome.OBSERVED, state.events.single { it.phase == DiscoveryLoadPhase.INDEX_FETCH }.outcome)
        assertEquals(DiscoveryLoadOutcome.OBSERVED, state.events.single { it.phase == DiscoveryLoadPhase.JSON_PARSE }.outcome)
        val parsing = state.events.single { it.phase == DiscoveryLoadPhase.INDEX_PARSE }
        assertEquals(DiscoveryLoadOutcome.FAILED, parsing.outcome)
        assertEquals(DiscoveryLoadError.INVALID_DATA, parsing.error)
        assertTrue(state.events.none { it.phase == DiscoveryLoadPhase.DETAILS_SYNC_COMPLETE })
        assertTrue(state.pendingSpans.isEmpty())
        val request = state.requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX)
        assertEquals(mapOf(DiscoveryLoadOutcome.OBSERVED to 1L), request.outcomes)
        assertEquals(8L, request.receivedBytes)
    }

    @Test fun exactHttpStatusesAreCountedOnceAndErrorBodiesDoNotStartJsonParsing() = runTest {
        val trace = enabled()
        val statuses = listOf(403, 404, 429, 503)
        var count = 0
        val source = source(trace) { response(it, "synthetic-error", statuses[count++]) }
        repeat(statuses.size) { assertTrue(runCatching { source.page(0, "abc") }.isFailure) }
        val state = trace.snapshot()
        val summary = state.requests.endpoint(DiscoveryLoadEndpoint.STATIC_PAGE)
        assertEquals(4L, summary.completedCount)
        assertEquals(statuses.associateWith { 1L }, summary.statusCodes)
        assertEquals(setOf(DiscoveryLoadError.DENIED, DiscoveryLoadError.NOT_FOUND, DiscoveryLoadError.RATE_LIMITED,
            DiscoveryLoadError.SERVER), summary.errors.keys)
        assertTrue(state.events.none { it.phase == DiscoveryLoadPhase.JSON_PARSE })
        assertTrue(state.events.all { it.outcome == DiscoveryLoadOutcome.FAILED })
    }

    @Test fun transportErrorsRemainDistinctAndDoNotInventStatusOrBytes() = runTest {
        val failures = listOf(UnknownHostException(), SSLException("synthetic"), SocketTimeoutException(), IOException())
        val trace = enabled()
        var next = 0
        val source = source(trace) { throw failures[next++] }
        repeat(failures.size) { assertTrue(runCatching { source.subject(1) }.exceptionOrNull() is ApiException.Network) }
        val summary = trace.snapshot().requests.endpoint(DiscoveryLoadEndpoint.SUBJECT_DETAILS)
        assertEquals(setOf(DiscoveryLoadError.DNS, DiscoveryLoadError.TLS, DiscoveryLoadError.TIMEOUT,
            DiscoveryLoadError.NETWORK), summary.errors.keys)
        assertTrue(summary.statusCodes.isEmpty())
        assertNull(summary.receivedBytes)
        assertEquals(4L, summary.unknownByteCount)
    }

    @Test fun cancelledSourceAttemptRecordsCancellationAndIgnoresLateCallback() = runTest {
        val trace = enabled()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val source = source(trace) {
            entered.complete(Unit)
            release.await()
            throw IOException("Synthetic cancelled transport")
        }
        try {
            val pending = async { source.index("abc") }
            entered.await()
            pending.cancelAndJoin()
            val summary = trace.snapshot().requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX)
            assertEquals(mapOf(DiscoveryLoadOutcome.CANCELLED to 1L), summary.outcomes)
            assertTrue(summary.errors.isEmpty())
            assertTrue(trace.snapshot().pendingSpans.isEmpty())
        } finally { release.countDown() }
    }

    @Test fun realHttpDiskCacheHitIsNotReportedAsAnotherNetworkResponse() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("[]").setHeader("Cache-Control", "max-age=3600"))
            Cache(temporary.newFolder("http-cache"), 1024 * 1024).use { httpCache ->
                val trace = enabled()
                val source = HttpDiscoverySource(userAgent(), OkHttpClient.Builder().cache(httpCache).addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/synthetic-index")).build())
                }, trace)
                source.index("abc")
                source.index("abc")
                val summary = trace.snapshot().requests.endpoint(DiscoveryLoadEndpoint.STATIC_INDEX)
                assertEquals(1, server.requestCount)
                assertEquals(mapOf(DiscoveryLoadCache.NETWORK to 1L, DiscoveryLoadCache.DISK to 1L), summary.caches)
                assertEquals(4L, summary.receivedBytes)
            }
        }
    }

    @Test fun fileCacheReportsRealReadWriteAndBackupBytesWhilePreservingFallback() {
        val trace = enabled()
        val cache = FileDiscoveryCache(temporary.root, trace)
        assertNull(cache.read())
        assertEquals(DiscoveryLoadOutcome.EMPTY, trace.snapshot().events.last().outcome)
        val first = DiscoveryParser.index(indexFixture())
        cache.write(first)
        val firstBytes = File(temporary.root, "discovery/current.json").length()
        assertEquals(first, cache.read())
        assertEquals(firstBytes, trace.snapshot().counters[DiscoveryLoadCounter.CACHE_READ_BYTES])
        val second = DiscoveryParser.index(indexFixture(modified = 200))
        cache.write(second)
        val secondBytes = File(temporary.root, "discovery/current.json").length()
        assertEquals(firstBytes * 2 + secondBytes, trace.snapshot().counters[DiscoveryLoadCounter.CACHE_WRITE_BYTES])
        assertEquals(2L, trace.snapshot().counters[DiscoveryLoadCounter.CACHE_WRITE_COUNT])
        File(temporary.root, "discovery/current.json").writeText("broken")
        assertEquals(first, cache.read())
        assertEquals(firstBytes * 3 + 6, trace.snapshot().counters[DiscoveryLoadCounter.CACHE_READ_BYTES])
        assertEquals(2L, trace.snapshot().counters[DiscoveryLoadCounter.CACHE_READ_HIT_COUNT])
        File(temporary.root, "discovery/previous.json").writeText("broken")
        assertNull(cache.read())
        val failure = trace.snapshot().events.last()
        assertEquals(DiscoveryLoadOutcome.FAILED, failure.outcome)
        assertEquals(DiscoveryLoadError.INVALID_DATA, failure.error)
    }

    @Test fun disabledTraceInAllProductionLayersDoesNotReadItsClock() = runTest {
        val trace = DiscoveryLoadTrace(nanoTime = { error("Disabled trace clock called") })
        var request = 0
        val documents = listOf(indexFixture(), pageFixture(listOf(1, 2)), pageFixture(listOf(3)), indexFixture())
        val source = source(trace) { response(it, documents[request++].toString()) }
        val repository = DiscoveryRepository(source, FileDiscoveryCache(temporary.root, trace), this,
            requestIntervalMillis = 0, trace = trace)
        repository.refresh()!!.join()
        assertTrue(repository.state.value.detailsCurrent)
        assertTrue(trace.snapshot().events.isEmpty())
        assertTrue(trace.snapshot().counters.isEmpty())
    }

    private fun enabled() = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true))
    private fun userAgent() = UserAgentInterceptor("SyntheticApp", "1", "https://example.com")
    private fun source(trace: DiscoveryLoadTrace, responder: (Request) -> Response) =
        HttpDiscoverySource(userAgent(), OkHttpClient.Builder().addInterceptor { responder(it.request()) }, trace)
    private fun response(request: Request, body: String, status: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic response")
        .body(body.toResponseBody()).build()
    private fun List<DiscoveryLoadRequestSummary>.endpoint(endpoint: DiscoveryLoadEndpoint) = single { it.endpoint == endpoint }
}
