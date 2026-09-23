package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class DiscoveryFormatException : Exception("Discovery data format is invalid")

/** Compressed source arrays never escape this adapter. */
internal object DiscoveryParser {
    fun index(document: JsonElement): DiscoverySnapshot {
        val root = document.array()
        val rows = root.getOrNull(0).array()
        val pageSize = root.getOrNull(1).integer()?.takeIf { it > 0 } ?: invalid()
        val modified = (root.getOrNull(2) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid()
        val subjects = linkedMapOf<Long, DiscoverySubject>()
        val points = linkedMapOf<String, DiscoveryPoint>()
        rows.forEach { value ->
            val row = value.array()
            if (row.size < 18) invalid()
            val id = (row[0] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid()
            val subjectPoints = row[12].array()
            if (subjectPoints.size % 4 != 0) invalid()
            val ids = linkedSetOf<String>()
            subjectPoints.chunked(4).forEach { tuple ->
                val rawId = tuple[0].text() ?: invalid()
                val latitude = tuple[1].number() ?: invalid()
                val longitude = tuple[2].number() ?: invalid()
                val coordinate = try { GeoPoint(latitude, longitude) } catch (_: IllegalArgumentException) { invalid() }
                val point = DiscoveryPoint(id, rawId, coordinate, tuple[3].integer() ?: 999)
                val previous = points.putIfAbsent(point.id, point)
                if (previous != null && previous != point) invalid()
                ids.add(point.id)
            }
            val subject = DiscoverySubject(
                anime = Anime(
                    subjectId = id,
                    name = row[3].text() ?: row[1].text() ?: "Bangumi $id",
                    nameCn = row[1].text(),
                    imageUrl = allowedImage(row[6].text()),
                ),
                city = row[4].text(),
                color = row[5].text()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
                pointIds = ids.toList(),
                englishName = row[2].text(),
            )
            if (subjects.putIfAbsent(id, subject)?.let { it != subject } == true) invalid()
        }
        if (subjects.size != rows.size || subjects.isEmpty()) invalid()
        val digest = MessageDigest.getInstance("SHA-256").digest(document.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DiscoverySnapshot("$modified:$digest", modified, pageSize, subjects.values.toList(), points.values.toList())
    }

    fun page(document: JsonElement): List<DiscoverySubjectDetails> = document.array().map { value ->
        val row = value.array()
        val id = (row.getOrNull(0) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid()
        val points = row.getOrNull(2).array().map { item ->
            val point = item.array()
            if (point.size < 15) invalid()
            DiscoveryPointDetail(
                rawId = point[0].text() ?: invalid(),
                name = point[1].text(),
                nameCn = point[2].text(),
                isFolder = point[3].flag(),
                imageUrl = allowedImage(point[6].text()),
                groupId = point[7].text(),
                episode = point[8].scalar(),
                timecodeSeconds = point[9].number()?.takeIf { it >= 0 },
                description = point[10].text(),
                source = point[11].text(),
                sourceUrl = allowedSource(point[12].text()),
                groupName = point[13].text(),
            )
        }
        if (points.map { it.rawId }.distinct().size != points.size) invalid()
        DiscoverySubjectDetails(id, points, fromStaticPage = true)
    }.also { if (it.map { cell -> cell.subjectId }.distinct().size != it.size) invalid() }

    fun apiDetails(subjectId: Long, document: JsonElement): DiscoverySubjectDetails = DiscoverySubjectDetails(
        subjectId,
        document.array().map { value ->
            val point = value as? JsonObject ?: invalid()
            DiscoveryPointDetail(
                rawId = point["id"].text() ?: invalid(),
                name = point["name"].text(),
                nameCn = point["cn"].text(),
                isFolder = point["isFolder"].flag(),
                imageUrl = allowedImage(point["image"].text()),
                groupId = point["fid"].text(),
                groupName = point["folder"].text(),
                episode = point["ep"].scalar(),
                timecodeSeconds = point["s"].number()?.takeIf { it >= 0 },
                description = point["mark"].text(),
                source = point["origin"].text(),
                sourceUrl = allowedSource(point["originLink"].text() ?: point["originURL"].text()),
            )
        }.also { if (it.map { point -> point.rawId }.distinct().size != it.size) invalid() },
    )

    fun merge(
        snapshot: DiscoverySnapshot,
        details: List<DiscoverySubjectDetails>,
    ): DiscoverySnapshot {
        val bySubject = details.associateBy { it.subjectId }
        val byId = details.flatMap { subject -> subject.points.map { "${subject.subjectId}::${it.rawId}" to it } }.toMap()
        val points = snapshot.points.mapNotNull { point ->
            val detail = byId[point.id] ?: return@mapNotNull point
            val authoritative = bySubject[point.subjectId]?.fromStaticPage == true
            if (detail.isFolder) return@mapNotNull if (authoritative) null else point
            if (!authoritative && point.detailsVersion == snapshot.version) {
                // A legacy missing image can be checked independently of verified text/details.
                return@mapNotNull if (point.imageMetadataVersion < DISCOVERY_IMAGE_METADATA_VERSION) point.copy(
                    imageUrl = point.imageUrl ?: detail.imageUrl,
                    imageMetadataVersion = detail.imageMetadataVersion,
                ) else point
            }
            val groupName = detail.groupName ?: detail.groupId?.let { folderId ->
                bySubject[point.subjectId]?.points?.find { it.rawId == folderId && it.isFolder }?.let { it.nameCn ?: it.name }
            }
            point.copy(
                name = if (authoritative) detail.name else detail.name ?: point.name,
                nameCn = if (authoritative) detail.nameCn else detail.nameCn ?: point.nameCn,
                imageUrl = if (authoritative) detail.imageUrl else point.imageUrl ?: detail.imageUrl,
                episode = if (authoritative) detail.episode else detail.episode ?: point.episode,
                timecodeSeconds = if (authoritative) detail.timecodeSeconds else detail.timecodeSeconds ?: point.timecodeSeconds,
                groupId = if (authoritative) detail.groupId else detail.groupId ?: point.groupId,
                groupName = if (authoritative) groupName else groupName ?: point.groupName,
                description = if (authoritative) detail.description else detail.description ?: point.description,
                source = if (authoritative) detail.source else detail.source ?: point.source,
                sourceUrl = if (authoritative) detail.sourceUrl else detail.sourceUrl ?: point.sourceUrl,
                detailsVersion = if (authoritative) snapshot.version else "api:${snapshot.version}",
                imageMetadataVersion = detail.imageMetadataVersion,
            )
        }
        val ids = points.map { it.id }.toHashSet()
        return snapshot.copy(
            points = points,
            subjects = snapshot.subjects.map { it.copy(pointIds = it.pointIds.filter(ids::contains)) },
        ).reconcileCompletion()
    }

    fun carryDetails(index: DiscoverySnapshot, old: DiscoverySnapshot?): DiscoverySnapshot {
        if (old == null) return index
        val oldPoints = old.points.associateBy { it.id }
        return index.copy(
            points = index.points.map { point ->
                oldPoints[point.id]?.copy(coordinate = point.coordinate, priority = point.priority) ?: point
            },
            loadedPages = if (old.version == index.version) old.loadedPages else emptySet(),
        ).reconcileCompletion()
    }

    internal fun allowedImage(value: String?): String? =
        cn.anitabi.navigator.data.images.AnitabiImageReference.normalize(value)

    private fun allowedSource(value: String?): String? = value?.takeIf {
        val url = it.toHttpUrlOrNull()
        url != null && url.username.isEmpty() && url.password.isEmpty()
    }

    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: invalid()
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement?.integer(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.scalar(): String? = (this as? JsonPrimitive)?.let {
        if (it.isString) it.contentOrNull?.takeIf(String::isNotBlank) else it.doubleOrNull?.let { _ -> it.content }
    }
    private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.let {
        it.booleanOrNull == true || it.intOrNull == 1
    } == true
    private fun invalid(): Nothing = throw DiscoveryFormatException()
}
