package cn.anitabi.navigator.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.BuildConfig

/** Disabled in ordinary artifacts and protected by DUMP even in an explicit profiling build. */
class DiscoveryDiagnosticsProvider : ContentProvider() {
    private val exportSlot = java.util.concurrent.Semaphore(1)
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        require(arg == null && (extras == null || extras.isEmpty))
        val diagnostics = (requireNotNull(context).applicationContext as AnitabiApplication).discoveryDiagnostics
        return when (method) {
            "reset" -> { diagnostics.reset(); Bundle().apply { putBoolean("reset", true) } }
            else -> throw IllegalArgumentException("Unsupported diagnostic operation")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        require(mode == "r" && uri.path == "/snapshot" && uri.query == null)
        check(exportSlot.tryAcquire()) { "A diagnostic export is already active" }
        val pipes = try { ParcelFileDescriptor.createPipe() } catch (failure: Throwable) {
            exportSlot.release(); throw failure
        }
        val diagnostics = (requireNotNull(context).applicationContext as AnitabiApplication).discoveryDiagnostics
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    it.write(diagnostics.snapshotJson().toByteArray(Charsets.UTF_8))
                }
            } catch (_: java.io.IOException) {
                // A cancelled local reader must not crash the application or log trace contents.
            } finally { exportSlot.release() }
        }, "Discovery profile export").start()
        return pipes[0]
    }

    private fun checkCaller() {
        check(BuildConfig.DISCOVERY_PROFILING && Binder.getCallingUid() in setOf(0, 2000))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
