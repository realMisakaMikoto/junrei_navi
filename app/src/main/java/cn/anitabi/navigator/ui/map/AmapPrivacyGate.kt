package cn.anitabi.navigator.ui.map

import android.content.Context
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.core.model.MapProvider
import com.amap.api.maps.MapsInitializer
import dalvik.system.BaseDexClassLoader

interface AmapPrivacyBridge {
    fun prepare(context: Context)
    fun revoke(context: Context)
}

internal fun isAmapMapCreationReady(context: Context): Boolean =
    (context.applicationContext as? AnitabiApplication)
        ?.container
        ?.amapPrivacyGate
        ?.isReady == true

internal fun isAmapNativeMapLibraryAvailable(context: Context): Boolean =
    (context.applicationContext.classLoader as? BaseDexClassLoader)
        ?.findLibrary("AMapSDK_MAP_v11_2_000") != null

internal fun <T> createAmapMapIfReady(
    privacyReady: Boolean,
    nativeLibraryAvailable: () -> Boolean,
    create: () -> T,
): T {
    check(privacyReady) { "AMap privacy gate is not ready" }
    check(nativeLibraryAvailable()) { "AMap native map library is unavailable" }
    return create()
}

class AmapPrivacyGate(
    context: Context,
    private val apiKeyConfigured: Boolean,
    private val bridge: AmapPrivacyBridge = AndroidAmapPrivacyBridge,
) {
    private val appContext = context.applicationContext

    var isReady: Boolean = false
        private set

    fun prepareIfAllowed(hasCurrentPrivacyConsent: Boolean): Boolean {
        if (isReady) return true
        if (!apiKeyConfigured || !hasCurrentPrivacyConsent) return false
        return runCatching {
            // These two calls must complete before any AMap MapView is constructed.
            bridge.prepare(appContext)
        }.fold(
            onSuccess = {
                isReady = true
                true
            },
            onFailure = { false },
        )
    }

    fun revoke() {
        isReady = false
        processMapCoordinator.destroyIfProvider(MapProvider.AMAP)
        runCatching { bridge.revoke(appContext) }
    }
}

private object AndroidAmapPrivacyBridge : AmapPrivacyBridge {
    override fun prepare(context: Context) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
    }

    override fun revoke(context: Context) {
        MapsInitializer.updatePrivacyAgree(context, false)
    }
}
