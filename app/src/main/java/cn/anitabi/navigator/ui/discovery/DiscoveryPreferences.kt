package cn.anitabi.navigator.ui.discovery

import android.content.Context
import androidx.core.content.edit
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition

/** Stores the user's view, independently of public data and itinerary coordinates. */
class DiscoveryPreferences(context: Context) : DiscoveryCameraStore {
    private val preferences = context.getSharedPreferences("discovery_view", Context.MODE_PRIVATE)

    override fun lastCamera(): DiscoveryCameraPosition? = runCatching {
        val provider = preferences.getString("provider", null)?.let(MapProvider::valueOf) ?: return null
        DiscoveryCameraPosition(
            center = GeoPoint(
                Double.fromBits(preferences.getLong("latitude", 0)),
                Double.fromBits(preferences.getLong("longitude", 0)),
            ),
            zoom = preferences.getFloat("zoom", 5f),
            bearing = preferences.getFloat("bearing", 0f),
            tilt = preferences.getFloat("tilt", 0f),
            provider = provider,
        ).takeIf { it.zoom.isFinite() && it.bearing.isFinite() && it.tilt.isFinite() }
    }.getOrNull()

    override fun saveCamera(camera: DiscoveryCameraPosition) {
        preferences.edit {
            putString("provider", camera.provider.name)
            putLong("latitude", camera.center.latitude.toBits())
            putLong("longitude", camera.center.longitude.toBits())
            putFloat("zoom", camera.zoom)
            putFloat("bearing", camera.bearing)
            putFloat("tilt", camera.tilt)
        }
    }
}
