package cn.anitabi.navigator.data.images

import org.junit.Assert.*
import org.junit.Test

class ImageFailureBackoffTest {
    @Test fun transientFailureExpiresAndRepeatedFailuresBackOff() {
        val policy = ImageFailureBackoff()
        policy.record("a-h160", ImageFailure.NETWORK, 100)
        assertEquals(5_000, policy.remainingMillis("a-h160", 100))
        assertEquals(0, policy.remainingMillis("a-h160", 5_100))
        policy.record("a-h160", ImageFailure.NETWORK, 5_100)
        assertEquals(10_000, policy.remainingMillis("a-h160", 5_100))
        policy.clear("a-h160")
        assertEquals(0, policy.remainingMillis("a-h160", 5_100))
    }

    @Test fun missingResourceDoesNotRapidlyRetryButManualRetryIsScoped() {
        val policy = ImageFailureBackoff()
        policy.record("a", ImageFailure.RESOURCE, 0)
        policy.record("b", ImageFailure.ACCESS, 0)
        assertEquals(600_000, policy.remainingMillis("a", 0))
        policy.retry(setOf("a"))
        assertEquals(0, policy.remainingMillis("a", 0))
        assertEquals(600_000, policy.remainingMillis("b", 0))
    }

    @Test fun capacityAndMetadataChangesDoNotBecomePermanentBlacklists() {
        val policy = ImageFailureBackoff(capacity = 2)
        listOf("a", "b", "c").forEach { policy.record(it, ImageFailure.NETWORK, 0) }
        assertEquals(2, policy.size)
        assertEquals(0, policy.remainingMillis("a", 0))
        policy.retain(setOf("b-new-version", "c"))
        assertEquals(1, policy.size)
        assertEquals(0, policy.remainingMillis("b-new-version", 0))
    }
}
