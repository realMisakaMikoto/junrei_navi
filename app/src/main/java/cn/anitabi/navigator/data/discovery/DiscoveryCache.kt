package cn.anitabi.navigator.data.discovery

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

interface DiscoveryCache {
    fun read(): DiscoverySnapshot?
    fun write(snapshot: DiscoverySnapshot)
}

/** Lives under cacheDir, independent of Room and all saved journeys. */
class FileDiscoveryCache(
    directory: File,
    private val trace: DiscoveryLoadTrace = DiscoveryLoadTrace(),
) : DiscoveryCache {
    private val directory = File(directory, "discovery")
    private val current = File(this.directory, "current.json")
    private val previous = File(this.directory, "previous.json")
    private val pending = File(this.directory, "pending.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var readFailure: DiscoveryLoadError? = null

    @Synchronized
    override fun read(): DiscoverySnapshot? {
        val reading = trace.begin(DiscoveryLoadPhase.CACHE_READ)
        trace.increment(DiscoveryLoadCounter.CACHE_READ_BYTES, 0)
        readFailure = null
        val snapshot = readFile(current) ?: readFile(previous)?.copy(endVersionVerified = false)
        val outcome = when {
            snapshot != null -> DiscoveryLoadOutcome.OBSERVED
            readFailure != null -> DiscoveryLoadOutcome.FAILED
            else -> DiscoveryLoadOutcome.EMPTY
        }
        trace.end(reading, outcome, error = if (outcome == DiscoveryLoadOutcome.FAILED) readFailure else null,
            itemCount = snapshot?.points?.size?.toLong() ?: 0)
        if (snapshot != null) trace.increment(DiscoveryLoadCounter.CACHE_READ_HIT_COUNT)
        return snapshot
    }

    @Synchronized
    override fun write(snapshot: DiscoverySnapshot) {
        val writing = trace.begin(DiscoveryLoadPhase.CACHE_WRITE)
        trace.increment(DiscoveryLoadCounter.CACHE_WRITE_COUNT)
        trace.increment(DiscoveryLoadCounter.CACHE_WRITE_BYTES, 0)
        try {
            require(valid(snapshot)) { "Invalid discovery snapshot" }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Discovery cache directory unavailable")
            val payload = json.encodeToString(DiscoverySnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
            FileOutputStream(pending).use { stream ->
                stream.write(payload)
                trace.increment(DiscoveryLoadCounter.CACHE_WRITE_BYTES, payload.size.toLong())
                stream.fd.sync()
            }
            // Never promote a corrupted current file over the last usable generation.
            val old = readFile(current)
            if (old != null && old.version != snapshot.version) {
                Files.copy(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (trace.enabled) trace.increment(DiscoveryLoadCounter.CACHE_WRITE_BYTES, previous.length())
            }
            Files.move(
                pending.toPath(), current.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            trace.end(writing, itemCount = snapshot.points.size.toLong())
        } catch (failure: Exception) {
            trace.end(writing, DiscoveryLoadOutcome.FAILED, when (failure) {
                is IOException, is SecurityException -> DiscoveryLoadError.STORAGE
                else -> DiscoveryLoadError.INVALID_DATA
            })
            throw failure
        }
    }

    private fun readFile(file: File): DiscoverySnapshot? = runCatching {
        if (!file.isFile) return null
        if (file.length() > 128L * 1024 * 1024) {
            readFailure = DiscoveryLoadError.INVALID_DATA
            return null
        }
        val text = if (trace.enabled) readObservedText(file) else file.readText()
        json.decodeFromString(DiscoverySnapshot.serializer(), text).takeIf(::valid)?.reconcileCompletion()
            .also { if (it == null) readFailure = DiscoveryLoadError.INVALID_DATA }
    }.getOrElse { failure ->
        readFailure = when (failure) {
            is IOException, is SecurityException -> DiscoveryLoadError.STORAGE
            else -> DiscoveryLoadError.INVALID_DATA
        }
        null
    }

    private fun readObservedText(file: File): String = object : FilterInputStream(file.inputStream()) {
        override fun read(): Int = `in`.read().also {
            if (it >= 0) trace.increment(DiscoveryLoadCounter.CACHE_READ_BYTES)
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
            if (it > 0) trace.increment(DiscoveryLoadCounter.CACHE_READ_BYTES, it.toLong())
        }
    }.reader(Charsets.UTF_8).use { it.readText() }

    private fun valid(snapshot: DiscoverySnapshot): Boolean {
        if (snapshot.pageSize <= 0 || snapshot.subjects.isEmpty() || snapshot.modified <= 0) return false
        if (!snapshot.version.startsWith("${snapshot.modified}:")) return false
        val subjectIds = snapshot.subjects.map { it.id }.toSet()
        if (subjectIds.size != snapshot.subjects.size) return false
        val pointIds = snapshot.points.map { it.id }.toSet()
        if (pointIds.size != snapshot.points.size) return false
        if (snapshot.points.any { it.subjectId !in subjectIds }) return false
        if (snapshot.subjects.any { subject -> subject.pointIds.any { it !in pointIds || !it.startsWith("${subject.id}::") } }) return false
        if (snapshot.subjects.flatMap { it.pointIds }.toSet() != pointIds) return false
        if (!subjectIds.containsAll(snapshot.currentSubjectIds)) return false
        return snapshot.loadedPages.all { it in 0 until snapshot.pageCount }
    }
}
