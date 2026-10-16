package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.data.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DiscoveryRepository(
    private val source: DiscoverySource,
    private val cache: DiscoveryCache,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestIntervalMillis: Long = 1_000,
    private val trace: DiscoveryLoadTrace = DiscoveryLoadTrace(),
) {
    private val mutableState = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = mutableState.asStateFlow()
    private val initializeMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val networkMutex = Mutex()
    private val workLock = Any()
    private val subjectRequests = mutableMapOf<Long, Deferred<Unit>>()
    @Volatile private var foreground = true
    private var updateJob: Job? = null
    private var pendingRefresh = false
    private var pendingForce = false
    private var lastRequestAt = Long.MIN_VALUE

    /** Includes lazy jobs and cancellation cleanup, not just the published loading flags. */
    internal fun isWorkQuiescent(): Boolean = synchronized(workLock) {
        updateJob == null && subjectRequests.values.all { it.isCompleted } && !(foreground && pendingRefresh)
    }

    suspend fun initialize() = initializeMutex.withLock {
        if (!state.value.initialized) {
            val snapshot = withContext(Dispatchers.IO) { cache.read() }
            mutableState.update { it.copy(snapshot = snapshot, initialized = true) }
        }
    }

    fun refresh(force: Boolean = false): Job? = synchronized(workLock) {
        pendingForce = pendingForce || force
        pendingRefresh = pendingRefresh || force || updateJob == null
        if (foreground && updateJob == null) startRefreshLocked() else updateJob
    }

    private fun startRefreshLocked(): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { refreshPendingRounds() }
        updateJob = job
        job.invokeOnCompletion {
            synchronized(workLock) {
                if (updateJob === job) {
                    updateJob = null
                    if (foreground && pendingRefresh && scope.isActive) startRefreshLocked()
                }
            }
        }
        job.start()
        return job
    }

    private suspend fun refreshPendingRounds() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val forced = synchronized(workLock) {
                if (!foreground || !pendingRefresh) return
                pendingRefresh = false
                pendingForce.also { pendingForce = false }
            }
            try {
                initialize()
                val cached = state.value.snapshot
                if (!forced && cached != null && cached.detailsCurrent &&
                    now() - cached.checkedAtMillis in 0 until UPDATE_INTERVAL_MILLIS
                ) continue
                mutableState.update { it.copy(refreshing = true, error = null) }
                updateRound()
            } catch (cancelled: CancellationException) {
                synchronized(workLock) {
                    pendingForce = pendingForce || forced
                }
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.toDiscoveryError()) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Call with the host's foreground lifecycle, independent of the selected destination. */
    fun setForeground(active: Boolean) = synchronized(workLock) {
        foreground = active
        mutableState.update { it.copy(paused = !active) }
        if (active) {
            pendingRefresh = true
            if (updateJob == null) startRefreshLocked()
        } else {
            updateJob?.cancel()
            subjectRequests.values.toList().forEach { it.cancel() }
        }
    }

    suspend fun ensureSubjectDetails(subjectId: Long) {
        initialize()
        val request = synchronized(workLock) {
            if (!foreground) return
            val snapshot = state.value.snapshot ?: return
            if (snapshot.subjects.none { it.id == subjectId } || !snapshot.needsSubjectDetails(subjectId)) return
            subjectRequests[subjectId]?.takeUnless { it.isCancelled || it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                val version = snapshot.version
                mutableState.update { it.copy(loadingSubjectIds = it.loadingSubjectIds + subjectId) }
                try {
                    val detail = request {
                        val document = source.subject(subjectId)
                        parse(DiscoveryLoadPhase.SUBJECT_PARSE) { DiscoveryParser.apiDetails(subjectId, document) }
                    }
                    withSnapshotLock {
                        val current = state.value.snapshot ?: return@withSnapshotLock
                        // The API carries no index generation; page validation must still confirm freshness.
                        if (current.version == version) publish(DiscoveryParser.merge(current, listOf(detail)))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableState.update { it.copy(error = failure.toDiscoveryError()) }
                }
            }.also { deferred ->
                subjectRequests[subjectId] = deferred
                deferred.invokeOnCompletion {
                    synchronized(workLock) {
                        if (subjectRequests[subjectId] === deferred) {
                            subjectRequests.remove(subjectId)
                            mutableState.update { it.copy(loadingSubjectIds = it.loadingSubjectIds - subjectId) }
                        }
                    }
                }
                deferred.start()
            }
        }
        request.await()
    }

    private suspend fun updateRound() {
        val token = (now() / 60_000 / 24 + 6).toString(36)
        val index = request {
            val document = source.index(token)
            parse(DiscoveryLoadPhase.INDEX_PARSE) { DiscoveryParser.index(document) }
        }
        withSnapshotLock {
            val old = state.value.snapshot
            // The fresh index restores members removed by legacy API folder responses as well.
            val next = DiscoveryParser.carryDetails(index, old)
            publish(next.copy(checkedAtMillis = now(), endVersionVerified = false))
        }
        var pageFailure = false
        for (page in 0 until index.pageCount) {
            if (page in state.value.snapshot.orEmptyPages()) continue
            try {
                val details = request {
                    val document = source.page(page, token)
                    parse(DiscoveryLoadPhase.PAGE_PARSE) { DiscoveryParser.page(document) }
                }
                // A response for the wrong shard cannot satisfy this generation's completeness.
                val expectedIds = index.subjects.drop(page * index.pageSize).take(index.pageSize).map { it.id }.toSet()
                if (details.map { it.subjectId }.toSet() != expectedIds) throw DiscoveryFormatException()
                withSnapshotLock {
                    val current = state.value.snapshot ?: return@withSnapshotLock
                    if (current.version != index.version) return@withSnapshotLock
                    val merged = DiscoveryParser.merge(current, details)
                    val complete = merged.points.none { it.subjectId in expectedIds && it.detailsVersion != merged.version }
                    publish(merged.copy(loadedPages = if (complete) merged.loadedPages + page else merged.loadedPages))
                    if (!complete) {
                        pageFailure = true
                        mutableState.update { it.copy(error = DiscoveryError.INVALID_DATA) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pageFailure = true
                mutableState.update { it.copy(error = failure.toDiscoveryError()) }
            }
        }
        val rechecked = request {
            val document = source.index(token)
            parse(DiscoveryLoadPhase.INDEX_PARSE) { DiscoveryParser.index(document) }
        }
        withSnapshotLock {
            val current = state.value.snapshot ?: return@withSnapshotLock
            val consistent = rechecked.version == index.version
            val completed = current.copy(endVersionVerified = consistent && !pageFailure)
            publish(completed)
            if (completed.detailsCurrent) trace.mark(
                DiscoveryLoadPhase.DETAILS_SYNC_COMPLETE,
                itemCount = completed.points.size.toLong(),
            )
            if (!consistent) mutableState.update { it.copy(error = DiscoveryError.VERSION_CHANGED) }
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T {
        val queued = trace.begin(DiscoveryLoadPhase.REQUEST_QUEUE_WAIT)
        try {
            return networkMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!foreground) throw CancellationException("Discovery is paused")
                if (lastRequestAt != Long.MIN_VALUE) delay((requestIntervalMillis - (now() - lastRequestAt)).coerceAtLeast(0))
                currentCoroutineContext().ensureActive()
                if (!foreground) throw CancellationException("Discovery is paused")
                lastRequestAt = now()
                trace.end(queued)
                block().also { currentCoroutineContext().ensureActive() }
            }
        } catch (cancelled: CancellationException) {
            trace.end(queued, DiscoveryLoadOutcome.CANCELLED)
            trace.increment(DiscoveryLoadCounter.CANCELLED_WORK_COUNT)
            throw cancelled
        }
    }

    private suspend fun <T> withSnapshotLock(block: suspend () -> T): T {
        val waiting = trace.begin(DiscoveryLoadPhase.SNAPSHOT_LOCK_WAIT)
        try {
            return snapshotMutex.withLock {
                trace.end(waiting)
                block()
            }
        } catch (cancelled: CancellationException) {
            trace.end(waiting, DiscoveryLoadOutcome.CANCELLED)
            throw cancelled
        }
    }

    private inline fun <T> parse(phase: DiscoveryLoadPhase, block: () -> T): T {
        val parsing = trace.begin(phase)
        try {
            return block().also { trace.end(parsing) }
        } catch (cancelled: CancellationException) {
            trace.end(parsing, DiscoveryLoadOutcome.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            trace.end(parsing, DiscoveryLoadOutcome.FAILED, DiscoveryLoadError.INVALID_DATA)
            throw failure
        }
    }

    private suspend fun publish(snapshot: DiscoverySnapshot) {
        // State becomes visible as soon as the index is valid, without waiting for page downloads.
        mutableState.update { it.copy(snapshot = snapshot) }
        try {
            withContext(Dispatchers.IO) { cache.write(snapshot) }
        } catch (_: java.io.IOException) {
            mutableState.update { it.copy(error = DiscoveryError.CACHE) }
        } catch (_: SecurityException) {
            mutableState.update { it.copy(error = DiscoveryError.CACHE) }
        }
    }

    companion object {
        const val UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
    }
}

private fun DiscoverySnapshot?.orEmptyPages(): Set<Int> = this?.loadedPages.orEmpty()

private fun Exception.toDiscoveryError(): DiscoveryError = when (this) {
    is DiscoveryFormatException, is ApiException.InvalidResponse -> DiscoveryError.INVALID_DATA
    else -> DiscoveryError.NETWORK
}
