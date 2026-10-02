package cn.anitabi.navigator.measurement

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.mapProvider
import cn.anitabi.navigator.core.region.FailClosedTerritoryClassifier
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.ui.discovery.DiscoveryViewModel
import cn.anitabi.navigator.ui.discovery.viewportIsCurrent
import cn.anitabi.navigator.ui.map.AmapPrivacyGate
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.map.isAmapNativeMapLibraryAvailable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Shell-only control plane; unlike run-as this also works with the non-debuggable target. */
class DiscoveryMeasurementProvider : ContentProvider() {
    private val application get() = requireNotNull(context).applicationContext as DiscoveryMeasurementApplication
    private val backupFile get() = File(application.fixtureFiles, "settings-backup.json")

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() in setOf(0, 2000))
        application.checkFixture()
        require(arg == null)
        require(method in setOf("prepare", "status", "teardown"))
        if (method != "prepare") require(extras == null || extras.isEmpty)
        val report = runBlocking {
            withTimeout(30_000) {
                when (method) {
                    "prepare" -> prepare(requireNotNull(extras))
                    "teardown" -> teardown()
                }
                status()
            }
        }
        return Bundle().apply { putString("report", report) }
    }

    private suspend fun prepare(extras: Bundle) = withContext(Dispatchers.IO) {
        require(extras.keySet() == setOf("provider", "size", "data", "images", "discoveryCache", "imageCache"))
        checkControlState()
        application.checkNoActivePointerOrTelemetry()
        check(application.passiveRouteAttempts() == 0) { "A previous measurement attempted routing" }
        val provider = MapProvider.valueOf(requireNotNull(extras.getString("provider")))
        val size = extras.getInt("size")
        require(size in sizes)
        val data = DataProfile.valueOf(requireNotNull(extras.getString("data")))
        val images = ImageProfile.valueOf(requireNotNull(extras.getString("images")))
        val discoveryCache = FixtureCache.valueOf(requireNotNull(extras.getString("discoveryCache")))
        val imageCache = FixtureCache.valueOf(requireNotNull(extras.getString("imageCache")))
        val transport = MeasurementTransport(application)
        val layout = transport.layout(provider, size, application.json)
        val center = GeoPoint(layout.centerLatitude, layout.centerLongitude)
        val classifier = FailClosedTerritoryClassifier.load { application.assets.open(it) }
        check(classifier.classify(center)?.mapProvider == provider) { "Fixture anchor does not belong to the requested provider" }
        val camera = if (provider == MapProvider.AMAP) {
            val settings = application.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
            check(settings.getInt("amap_privacy_consent_version", 0) == AppSettingsStore.AMAP_PRIVACY_CONSENT_VERSION)
            check(BuildConfig.AMAP_API_KEY_CONFIGURED && isAmapNativeMapLibraryAvailable(application))
            withContext(Dispatchers.Main) {
                val gate = AmapPrivacyGate(application, BuildConfig.AMAP_API_KEY_CONFIGURED)
                check(gate.prepareIfAllowed(true)) { "Existing AMap privacy authorization could not be prepared" }
                OfficialAmapCoordinateConverter(application).convert(center).let { GeoPoint(it.latitude, it.longitude) }
            }
        } else center
        val config = MeasurementConfig(provider = provider, size = size, data = data, images = images,
            layout = layout, certificateSha256 = transport.certificateSha256,
            cameraLatitude = camera.latitude, cameraLongitude = camera.longitude)
        backupSettings()
        if (discoveryCache == FixtureCache.EMPTY) application.deleteFixtureCache(File(application.cacheGroup(config), "discovery"))
        if (imageCache == FixtureCache.EMPTY) application.deleteFixtureCache(File(application.cacheGroup(config), "images"))
        prepareSettings(config)
        application.saveConfiguration(config)
        application.settingsRestored = false
    }

    private fun checkControlState() {
        check(application.bootstrapFile.isFile) { "Prepare the fixed bootstrap sentinel first" }
        check(application.activity.get() == null) { "Kill the measured process before control-plane preparation" }
    }

    private fun backupSettings() {
        if (backupFile.exists()) { readBackup(); return }
        val value = buildJsonObject {
            put("schema", 1)
            put("settings", preferenceSnapshot(settingsName, settingsKeys))
            put("camera", preferenceSnapshot(cameraName, cameraKeys))
        }
        writeAtomic(backupFile, value.toString())
    }

    @SuppressLint("UseKtx") // The fixture must check each durable commit result.
    private fun prepareSettings(config: MeasurementConfig) {
        check(application.getSharedPreferences(settingsName, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_complete", true).putString("appearance", "LIGHT").putBoolean("image_markers", true).commit())
        check(application.getSharedPreferences(cameraName, Context.MODE_PRIVATE).edit()
            .putString("provider", config.provider.name)
            .putLong("latitude", config.cameraLatitude.toBits()).putLong("longitude", config.cameraLongitude.toBits())
            .putFloat("zoom", config.layout.zoom).putFloat("bearing", 0f).putFloat("tilt", 0f).commit())
    }

    private suspend fun teardown() = withContext(Dispatchers.IO) {
        checkControlState()
        if (!backupFile.exists() && !application.configFile.exists()) {
            application.settingsRestored = true
            return@withContext
        }
        val backup = readBackup()
        restorePreferences(settingsName, settingsKeys, backup.getValue("settings").jsonObject)
        restorePreferences(cameraName, cameraKeys, backup.getValue("camera").jsonObject)
        check(preferenceSnapshot(settingsName, settingsKeys) == backup.getValue("settings"))
        check(preferenceSnapshot(cameraName, cameraKeys) == backup.getValue("camera"))
        application.settingsRestored = true
        application.passiveRouteAttempts() // Retain the scalar count after deleting fixture-only files.
        application.removeConfiguration()
        MapProvider.entries.forEach { provider -> sizes.forEach { size ->
            application.deleteFixtureCache(File(application.fixtureCaches, "${provider.name}-$size"))
        } }
        val fixtureRoot = application.fixtureFiles.canonicalFile
        check(fixtureRoot.parentFile == application.filesDir.canonicalFile && fixtureRoot.name == "discovery-measurement")
        check(!fixtureRoot.exists() || fixtureRoot.deleteRecursively())
    }

    private fun readBackup(): JsonObject {
        val backup = application.json.parseToJsonElement(backupFile.readText()).jsonObject
        require(backup.keys == setOf("schema", "settings", "camera") && backup.getValue("schema").jsonPrimitive.int == 1)
        validatePreferences(settingsKeys, backup.getValue("settings").jsonObject)
        validatePreferences(cameraKeys, backup.getValue("camera").jsonObject)
        return backup
    }

    private fun preferenceSnapshot(name: String, keys: Map<String, PreferenceType>): JsonObject {
        val preferences = application.getSharedPreferences(name, Context.MODE_PRIVATE)
        return buildJsonObject { keys.forEach { (key, type) ->
            put(key, if (!preferences.contains(key)) JsonNull else when (type) {
                PreferenceType.BOOLEAN -> JsonPrimitive(preferences.getBoolean(key, false))
                PreferenceType.STRING -> preferences.getString(key, null)?.let(::JsonPrimitive) ?: JsonNull
                PreferenceType.LONG -> JsonPrimitive(preferences.getLong(key, 0))
                PreferenceType.FLOAT -> JsonPrimitive(preferences.getFloat(key, 0f))
            })
        } }
    }

    private fun validatePreferences(keys: Map<String, PreferenceType>, values: JsonObject) {
        require(values.keys == keys.keys)
        keys.forEach { (key, type) ->
            val value = values.getValue(key)
            if (value != JsonNull) when (type) {
                PreferenceType.BOOLEAN -> value.jsonPrimitive.boolean
                PreferenceType.STRING -> require(value.jsonPrimitive.isString)
                PreferenceType.LONG -> value.jsonPrimitive.long
                PreferenceType.FLOAT -> require(value.jsonPrimitive.float.isFinite())
            }
        }
    }

    @SuppressLint("UseKtx") // Failed restoration must retain its backup and fail the harness.
    private fun restorePreferences(name: String, keys: Map<String, PreferenceType>, values: JsonObject) {
        val editor = application.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        keys.forEach { (key, type) ->
            val value = values.getValue(key)
            if (value == JsonNull) editor.remove(key) else when (type) {
                PreferenceType.BOOLEAN -> editor.putBoolean(key, value.jsonPrimitive.boolean)
                PreferenceType.STRING -> editor.putString(key, value.jsonPrimitive.content)
                PreferenceType.LONG -> editor.putLong(key, value.jsonPrimitive.long)
                PreferenceType.FLOAT -> editor.putFloat(key, value.jsonPrimitive.float)
            }
        }
        check(editor.commit())
    }

    private suspend fun status(): String {
        val attempts = withContext(Dispatchers.IO) { application.passiveRouteAttempts() }
        return withContext(Dispatchers.Main) {
            val config = application.config
            val activity = application.activity.get()
            // Public API with a factory that cannot instantiate a missing owner. No lazy VM creation.
            val model = activity?.let { runCatching { ViewModelProvider(it, readOnlyFactory)[DiscoveryViewModel::class.java] }.getOrNull() }
            val state = model?.state?.value
            val snapshot = state?.data?.snapshot
            val observedTrace = application.discoveryDiagnostics.trace.snapshot()
            val viewport = state?.let { current -> current.viewportSnapshot?.takeIf { current.viewportIsCurrent(it.token) } }
            val actualProviderMatches = state != null && config != null && state.provider == config.provider &&
                state.providerChoices == setOf(config.provider) && state.unresolvedCount == 0
            buildJsonObject {
                put("schema", 1)
                put("configured", config != null)
                put("fineLocationDenied", application.fineLocationDenied())
                put("coarseLocationDenied", application.coarseLocationDenied())
                put("locationMode", if (application.locationPermissionsDenied()) "denied" else "invalid_granted")
                put("settingsRestored", application.settingsRestored)
                put("bootstrap", application.bootstrapFile.exists())
                put("pid", Process.myPid())
                put("activityReady", activity != null && model != null)
                put("activityResumed", application.activityResumed)
                put("windowFocused", activity?.hasWindowFocus() == true)
                put("nativeVisual", application.visualProbe.snapshot())
                put("windowWidth", activity?.window?.decorView?.width ?: 0)
                put("windowHeight", activity?.window?.decorView?.height ?: 0)
                put("expectedPointCount", config?.size ?: 0)
                put("loadedPointCount", snapshot?.points?.size ?: 0)
                put("preparedPointCount", state?.mapPoints?.size ?: 0)
                put("loadedPageCount", snapshot?.loadedPages?.size ?: 0)
                put("expectedPageCount", config?.layout?.pageCount ?: 0)
                put("dataComplete", snapshot?.detailsCurrent == true)
                put("dataError", state?.data?.error?.name ?: "NONE")
                put("dataPreparing", state?.dataPreparing == true)
                put("repositoryStateQuiescent", state != null && state.data.initialized && !state.data.refreshing &&
                    state.data.loadingSubjectIds.isEmpty() && !state.dataPreparing)
                put("repositoryQuiescent", model != null && application.container.discoveryRepository.isWorkQuiescent())
                put("traceQuiescent", observedTrace.enabled && observedTrace.pendingSpans.isEmpty() && observedTrace.droppedSpans == 0L)
                put("activeProvider", state?.provider?.name ?: "NONE")
                put("providerMatches", actualProviderMatches)
                put("listMode", state?.listMode == true)
                put("viewportSnapshotValid", viewport != null)
                put("viewportEligible", state?.canSelectViewport == true)
                put("viewportMemberCount", viewport?.visibleIds?.size ?: 0)
                put("visiblePhotoCount", config?.layout?.singletonPhotoPointIds?.count { it in viewport?.visibleIds.orEmpty() } ?: 0)
                put("expectedPhotoCount", config?.layout?.uniqueImages ?: 0)
                put("passiveRouteAttempts", attempts)
                put("datasetSha256", config?.layout?.datasetSha256 ?: "")
                put("dataProfile", config?.data?.name ?: "NONE")
                put("imageProfile", config?.images?.name ?: "NONE")
            }.toString()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private enum class PreferenceType { BOOLEAN, STRING, LONG, FLOAT }
    private companion object {
        val sizes = setOf(1_000, 10_000, 100_000, 51_828)
        const val settingsName = "anitabi_settings_v2"
        const val cameraName = "discovery_view"
        val settingsKeys = mapOf("onboarding_complete" to PreferenceType.BOOLEAN, "appearance" to PreferenceType.STRING, "image_markers" to PreferenceType.BOOLEAN)
        val cameraKeys = mapOf("provider" to PreferenceType.STRING, "latitude" to PreferenceType.LONG, "longitude" to PreferenceType.LONG,
            "zoom" to PreferenceType.FLOAT, "bearing" to PreferenceType.FLOAT, "tilt" to PreferenceType.FLOAT)
        val readOnlyFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Measurement status does not create ViewModels")
        }
    }
}
