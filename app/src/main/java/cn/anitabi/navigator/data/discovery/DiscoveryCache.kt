package cn.anitabi.navigator.data.discovery

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

interface DiscoveryCache {
    fun read(): DiscoverySnapshot?
    fun write(snapshot: DiscoverySnapshot)
}

/** Lives under cacheDir, independent of Room and all saved journeys. */
class FileDiscoveryCache(directory: File) : DiscoveryCache {
    private val directory = File(directory, "discovery")
    private val current = File(this.directory, "current.json")
    private val previous = File(this.directory, "previous.json")
    private val pending = File(this.directory, "pending.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(): DiscoverySnapshot? = readFile(current)
        ?: readFile(previous)?.copy(endVersionVerified = false)

    @Synchronized
    override fun write(snapshot: DiscoverySnapshot) {
        require(valid(snapshot)) { "Invalid discovery snapshot" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Discovery cache directory unavailable")
        val payload = json.encodeToString(DiscoverySnapshot.serializer(), snapshot)
        FileOutputStream(pending).use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // Never promote a corrupted current file over the last usable generation.
        val old = readFile(current)
        if (old != null && old.version != snapshot.version) {
            Files.copy(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(
            pending.toPath(), current.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun readFile(file: File): DiscoverySnapshot? = runCatching {
        if (!file.isFile || file.length() > 128L * 1024 * 1024) return null
        json.decodeFromString(DiscoverySnapshot.serializer(), file.readText()).takeIf(::valid)
    }.getOrNull()

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
