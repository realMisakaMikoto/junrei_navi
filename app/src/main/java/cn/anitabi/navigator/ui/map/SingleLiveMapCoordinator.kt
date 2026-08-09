package cn.anitabi.navigator.ui.map

import cn.anitabi.navigator.core.model.MapProvider

/**
 * Owns the single process-visible SDK map. Acquiring a new lease synchronously destroys the
 * previous lease before constructing the next SDK view, including provider switches.
 */
internal class SingleLiveMapCoordinator {
    private var activeLease: SingleLiveMapLease<*>? = null

    fun <T : Any> acquire(
        provider: MapProvider,
        create: () -> T,
    ): SingleLiveMapLease<T> {
        activeLease?.destroy()
        activeLease = null

        val lease = SingleLiveMapLease(
            provider = provider,
            value = create(),
            release = ::release,
        )
        activeLease = lease
        return lease
    }

    fun destroyIfProvider(provider: MapProvider): Boolean {
        val lease = activeLease?.takeIf { it.provider == provider } ?: return false
        activeLease = null
        lease.destroy()
        return true
    }

    private fun release(lease: SingleLiveMapLease<*>) {
        if (activeLease === lease) activeLease = null
        lease.destroy()
    }
}

internal class SingleLiveMapLease<T : Any>(
    val provider: MapProvider,
    val value: T,
    private val release: (SingleLiveMapLease<*>) -> Unit,
) {
    private var destroyAction: (() -> Unit)? = null
    private var destroyed = false

    internal val isDestroyed: Boolean
        get() = destroyed

    fun installDestroyAction(action: () -> Unit) {
        if (destroyed) {
            action()
        } else {
            destroyAction = action
        }
    }

    fun close() = release(this)

    internal fun destroy() {
        if (destroyed) return
        destroyed = true
        destroyAction?.invoke()
        destroyAction = null
    }
}

internal val processMapCoordinator = SingleLiveMapCoordinator()
