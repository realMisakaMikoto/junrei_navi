package cn.anitabi.navigator.data.images

import android.content.Context
import cn.anitabi.navigator.createAppUserAgentInterceptor
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.IOException
import okhttp3.OkHttpClient

internal fun createAppImageHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(createAppUserAgentInterceptor())
    .addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != "image.anitabi.cn") return@addInterceptor chain.proceed(request)
        val canonical = AnitabiImageReference.normalize(request.url.toString())
            ?: throw IOException("Image resource is unavailable")
        chain.proceed(request.newBuilder().url(canonical)
            .removeHeader("Authorization").removeHeader("Cookie")
            .removeHeader("X-Goog-Api-Key").build())
    }
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        // Inspect each redirect before OkHttp can follow it, including redirects after a redirect.
        if (chain.call().request().url.host == "image.anitabi.cn" && response.isRedirect) {
            val target = response.header("Location")?.let { response.request.url.resolve(it) }
            if (target == null || AnitabiImageReference.normalize(target.toString()) != target.toString()) {
                response.close()
                throw IOException("Image redirect is unavailable")
            }
        }
        response
    }
    .build()

/** Shared production network/decoder setup; tests may replace only the HTTP transport. */
internal fun createAppImageLoader(
    context: Context,
    httpClient: OkHttpClient = createAppImageHttpClient(),
): ImageLoader = ImageLoader.Builder(context)
    .components { add(OkHttpNetworkFetcherFactory(httpClient)) }
    .build()
