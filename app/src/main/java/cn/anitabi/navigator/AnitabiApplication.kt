package cn.anitabi.navigator

import android.app.Application
import android.content.Context
import cn.anitabi.navigator.data.images.createAppImageLoader
import coil3.ImageLoader
import coil3.SingletonImageLoader

open class AnitabiApplication : Application(), SingletonImageLoader.Factory {
    val discoveryDiagnostics = cn.anitabi.navigator.diagnostics.DiscoveryDiagnostics(BuildConfig.DISCOVERY_PROFILING)
    val container by lazy { createContainer() }

    protected open fun createContainer(): AppContainer = AppContainer(this)
    protected open fun initializeContainerOnCreate(): Boolean = true

    override fun onCreate() {
        super.onCreate()
        discoveryDiagnostics.trace.mark(cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase.LAUNCH)
        if (!initializeContainerOnCreate()) return
        container.telemetryConsentController.applyStoredConsent()
        container.amapPrivacyGate.prepareIfAllowed(
            container.appSettingsStore.hasCurrentAmapPrivacyConsent(),
        )
    }

    override fun newImageLoader(context: Context): ImageLoader = createAppImageLoader(context)
}
