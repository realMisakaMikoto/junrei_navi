package cn.anitabi.navigator

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

open class AnitabiApplication : Application(), SingletonImageLoader.Factory {
    val container by lazy { createContainer() }

    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container.telemetryConsentController.applyStoredConsent()
        container.amapPrivacyGate.prepareIfAllowed(
            container.appSettingsStore.hasCurrentAmapPrivacyConsent(),
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val imageHttpClient = OkHttpClient.Builder()
            .addInterceptor(createAppUserAgentInterceptor())
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(imageHttpClient))
            }
            .build()
    }
}
