package cn.anitabi.navigator.data.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic shapes from the primary image API and pinned Swift path contract, reviewed 2026-09-21. */
class DiscoveryImagePathRegressionTest {
    @Test fun indexCoverUsesTheImageApiResourcePath() {
        val root = indexFixture(count = 1) as JsonArray
        val row = (root[0] as JsonArray).single() as JsonArray
        val updated = row.replacing(6, JsonPrimitive("/images/bangumi/synthetic-cover.jpg"))
        val index = DiscoveryParser.index(root.replacing(0, JsonArray(listOf(updated))))
        assertEquals("https://image.anitabi.cn/bangumi/synthetic-cover.jpg", index.subjects.single().anime.imageUrl)
    }

    @Test fun staticPageNormalizesAllKnownImageFamiliesWithoutRebuildingObjectNames() {
        val paths = listOf(
            "/images/points/synthetic-owner/upload-123.jpg",
            "/images/user/synthetic-owner/synthetic-work/upload-456.png",
            "/images/bangumi/synthetic-work/cover-789.jpg",
        )
        val mismatches = paths.indices.filter { index ->
            val path = paths[index]
            val page = pageWithImage(path)
            DiscoveryParser.page(page).single().points.single().imageUrl != "https://image.anitabi.cn${path.removePrefix("/images")}"
        }
        assertTrue("Static-page image families must use the image API path; failed fixture indices=$mismatches", mismatches.isEmpty())
    }

    @Test fun apiDetailsRepairsTheLegacyAbsolutePrefixAndPreservesVersionQuery() {
        val input = "https://image.anitabi.cn/images/user/synthetic-owner/upload.png?plan=h160&v=synthetic-version"
        val api = buildJsonArray { add(buildJsonObject { put("id", "shared"); put("image", input) }) }
        assertEquals(
            "https://image.anitabi.cn/user/synthetic-owner/upload.png?plan=h160&v=synthetic-version",
            DiscoveryParser.apiDetails(1, api).points.single().imageUrl,
        )
    }

    @Test fun alreadyCanonicalSizedResourceIsIdempotent() {
        val source = "https://image.anitabi.cn/points/synthetic-owner/upload%20name.jpg?v=synthetic-version&plan=h160"
        val once = DiscoveryParser.allowedImage(source)
        assertEquals(source, once)
        assertEquals(once, DiscoveryParser.allowedImage(once))
    }

    @Test fun similarlyNamedAndInteriorSegmentsAreNotRewritten() {
        for (path in listOf("/images2/synthetic.png", "/points/images/synthetic.png")) {
            assertEquals("https://image.anitabi.cn$path", DiscoveryParser.allowedImage(path))
        }
    }

    @Test fun unsafeSourceSyntaxIsRejectedBeforeUrlCanonicalization() {
        val unsafe = listOf(
            "//image.anitabi.cn/points/synthetic.png",
            "http://image.anitabi.cn/points/synthetic.png",
            "https://fixture:fixture@image.anitabi.cn/points/synthetic.png",
            "https://image.anitabi.cn:8443/points/synthetic.png",
            "https://image.anitabi.cn.evil.invalid/points/synthetic.png",
            "file:///points/synthetic.png",
            "https://image.anitabi.cn/points/a/../synthetic.png",
            "https://image.anitabi.cn/points/%2e%2e/synthetic.png",
            "https://image.anitabi.cn/points/%252e%252e/synthetic.png",
            "https://image.anitabi.cn\\points\\synthetic.png",
            "\nhttps://image.anitabi.cn/points/synthetic.png",
        )
        val accepted = unsafe.indices.filter { DiscoveryParser.allowedImage(unsafe[it]) != null }
        assertTrue("Unsafe source syntax must be rejected; accepted fixture indices=$accepted", accepted.isEmpty())
    }

    private fun pageWithImage(image: String): JsonArray {
        val page = pageFixture(listOf(1)) as JsonArray
        val subject = page.single() as JsonArray
        val point = (subject[2] as JsonArray).single() as JsonArray
        return JsonArray(listOf(subject.replacing(2, JsonArray(listOf(point.replacing(6, JsonPrimitive(image)))))))
    }

    private fun JsonArray.replacing(index: Int, value: JsonElement): JsonArray =
        JsonArray(toMutableList().apply { this[index] = value })
}
