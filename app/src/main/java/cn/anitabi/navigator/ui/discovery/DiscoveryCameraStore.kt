package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition

interface DiscoveryCameraStore {
    fun lastCamera(): DiscoveryCameraPosition?
    fun saveCamera(camera: DiscoveryCameraPosition)
}
