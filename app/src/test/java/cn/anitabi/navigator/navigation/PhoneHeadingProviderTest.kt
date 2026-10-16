package cn.anitabi.navigator.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneHeadingProviderTest {
    @Test fun missingSamplesExpireAndNewSamplesReplaceTheOldDeadline() = runTest {
        val samples = MutableSharedFlow<PhoneHeadingSample?>()
        val output = mutableListOf<Float?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            samples.freshPhoneHeadings { testScheduler.currentTime * 1_000_000L }.collect(output::add)
        }
        samples.emit(PhoneHeadingSample(90f, 0))
        runCurrent()
        assertEquals(listOf(90f), output)
        advanceTimeBy(1_900)
        samples.emit(PhoneHeadingSample(180f, testScheduler.currentTime * 1_000_000L))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(90f, 180f), output)
        advanceTimeBy(1_900)
        runCurrent()
        assertEquals(listOf(90f, 180f, null), output)
    }

    @Test fun unreliableAndInvalidSamplesCannotLeaveAnOldArrowVisible() = runTest {
        val samples = MutableSharedFlow<PhoneHeadingSample?>()
        val output = mutableListOf<Float?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            samples.freshPhoneHeadings { testScheduler.currentTime * 1_000_000L }.collect(output::add)
        }
        samples.emit(PhoneHeadingSample(45f, 0))
        runCurrent()
        samples.emit(null)
        runCurrent()
        assertEquals(listOf(45f, null), output)
        samples.emit(PhoneHeadingSample(90f, 1))
        samples.emit(PhoneHeadingSample(Float.NaN, 0))
        samples.emit(PhoneHeadingSample(90f, -2_000_000_000L))
        runCurrent()
        assertEquals(listOf(45f, null), output)
    }

    @Test fun repeatedHeadingRefreshesFreshnessWithoutRepeatingOutputAndCancellationStopsTimeout() = runTest {
        val samples = MutableSharedFlow<PhoneHeadingSample?>()
        val output = mutableListOf<Float?>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            samples.freshPhoneHeadings { testScheduler.currentTime * 1_000_000L }.collect(output::add)
        }
        samples.emit(PhoneHeadingSample(90f, 0))
        runCurrent()
        advanceTimeBy(1_900)
        samples.emit(PhoneHeadingSample(90f, testScheduler.currentTime * 1_000_000L))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(90f), output)
        collection.cancel()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(90f), output)
    }

    @Test fun headingsWrapAcrossNorthAndRejectNonFiniteValues() {
        assertEquals(359f, normalizePhoneHeading(-1f)!!, 0f)
        assertEquals(1f, normalizePhoneHeading(361f)!!, 0f)
        assertEquals(0f, normalizePhoneHeading(720f)!!, 0f)
        assertNull(normalizePhoneHeading(Float.NaN))
        assertNull(normalizePhoneHeading(Float.POSITIVE_INFINITY))
    }
}
