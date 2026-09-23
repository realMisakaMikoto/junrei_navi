package cn.anitabi.navigator.data.images

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AnitabiImageReferenceTest {
    @Test fun allKnownFamiliesRetainFullIdentityAndAreIdempotent() {
        for (family in listOf("points", "user", "bangumi")) {
            for (prefix in listOf("", "https://image.anitabi.cn")) {
                val expected = "https://image.anitabi.cn/$family/owner/work/file%20name-123.png?v=fixture"
                val actual = AnitabiImageReference.normalize("$prefix/images/$family/owner/work/file%20name-123.png?v=fixture")
                assertEquals(expected, actual)
                assertEquals(actual, AnitabiImageReference.normalize(actual))
            }
        }
    }

    @Test fun variantsReplaceEveryPlanAndRetainVersion() {
        val source = "/images/points/owner/image.jpg?plan=h160&v=fixture&plan=h360"
        for (variant in AnitabiImageVariant.entries) {
            val url = requireNotNull(AnitabiImageReference.request(source, variant)).toHttpUrl()
            assertEquals("fixture", url.queryParameter("v"))
            assertEquals(listOfNotNull(variant.plan), url.queryParameterValues("plan"))
            assertEquals(url.toString(), AnitabiImageReference.request(url.toString(), variant))
        }
    }

    @Test fun unknownQuerySemanticsAreNotRewritten() {
        val source = "https://image.anitabi.cn/points/owner/image.jpg?plan=h160&signature=fixture"
        assertEquals(source, AnitabiImageReference.request(source, AnitabiImageVariant.DISPLAY))
        assertEquals(source, AnitabiImageReference.request(source, AnitabiImageVariant.ORIGINAL))
    }

    @Test fun unrelatedSegmentsArePreserved() {
        for (path in listOf("/images2/points/image.png", "/points/images/image.png", "/images/unknown/image.png")) {
            assertEquals("https://image.anitabi.cn$path", AnitabiImageReference.normalize(path))
        }
    }

    @Test fun unsafeSyntaxCannotBeCanonicalizedIntoAnAllowedRequest() {
        val invalid = listOf(null, "", "points/image.png", "//image.anitabi.cn/points/image.png",
            "http://image.anitabi.cn/points/image.png", "file:///points/image.png",
            "https://image.anitabi.cn:444/points/image.png", "https://a@image.anitabi.cn/points/image.png",
            "https://image.anitabi.cn.evil.invalid/points/image.png", "/points/a/../image.png",
            "/points/%2e%2e/image.png", "/points/%252e%252e/image.png", "/points/a%2f..%2fimage.png",
            "/points/a%5cimage.png", "/points/a%00image.png", "/points/a\\image.png",
            "\nhttps://image.anitabi.cn/points/image.png", "/points/image.png#fragment")
        invalid.forEachIndexed { i, value -> assertNull("Unsafe fixture $i", AnitabiImageReference.normalize(value)) }
    }

    @Test fun existingNonAnitabiSearchCoverIsUnaffected() {
        val source = "https://lain.bgm.tv/pic/cover/l/synthetic.jpg"
        assertEquals(source, AnitabiImageReference.displayModel(source, AnitabiImageVariant.THUMBNAIL))
    }
}
