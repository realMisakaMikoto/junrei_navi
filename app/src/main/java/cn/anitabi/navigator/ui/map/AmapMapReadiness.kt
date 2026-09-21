package cn.anitabi.navigator.ui.map

/** Retains the early native load event until the attached/resumed view can deliver its map. */
internal class AmapMapReadiness {
    var loaded = false
        private set
    private var closed = false
    private var onReady: (() -> Unit)? = null

    fun onMapLoaded() {
        if (closed) return
        loaded = true
        onReady?.invoke()
    }

    fun listen(callback: () -> Unit) {
        if (closed) return
        onReady = callback
        if (loaded) callback()
    }

    fun close() { closed = true; onReady = null }
}
