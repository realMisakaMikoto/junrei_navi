package cn.anitabi.navigator.data.images

import coil3.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ImageFailure { RESOURCE, ACCESS, NETWORK, DECODE, UNKNOWN }

fun imageFailure(error: Throwable): ImageFailure = when (error) {
    is HttpException -> when (error.response.code) {
        404, 410 -> ImageFailure.RESOURCE
        401, 403, 429 -> ImageFailure.ACCESS
        else -> ImageFailure.NETWORK
    }
    is UnknownHostException, is SocketTimeoutException, is SSLException, is IOException -> ImageFailure.NETWORK
    // The pinned Android Coil decoder uses this message when a response cannot be decoded.
    is IllegalStateException -> if (error.message?.startsWith("BitmapFactory returned a null bitmap") == true) ImageFailure.DECODE else ImageFailure.UNKNOWN
    else -> ImageFailure.UNKNOWN
}

/** In-memory, monotonic retry policy; the key includes canonical resource version and size. */
internal class ImageFailureBackoff(private val capacity: Int = 256) {
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = linkedMapOf<String, Failure>()
    fun remainingMillis(key: String, now: Long): Long = ((failures[key]?.retryAt ?: now) - now).coerceAtLeast(0)
    fun record(key: String, kind: ImageFailure, now: Long) {
        val attempts = ((failures[key]?.attempts ?: 0) + 1).coerceAtMost(6)
        val delay = when (kind) {
            ImageFailure.RESOURCE, ImageFailure.ACCESS, ImageFailure.DECODE -> 10 * 60_000L
            else -> (5_000L shl (attempts - 1)).coerceAtMost(60_000L)
        }
        failures.remove(key)
        failures[key] = Failure(attempts, now + delay)
        while (failures.size > capacity) failures.remove(failures.keys.first())
    }
    fun clear(key: String) { failures.remove(key) }
    fun retry(keys: Set<String>) { keys.forEach(failures::remove) }
    fun retain(keys: Set<String>) { failures.keys.retainAll(keys) }
    val size: Int get() = failures.size
}
