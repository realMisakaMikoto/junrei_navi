package cn.anitabi.navigator.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Opt-in screenshots of synthetic UI fixtures; these do not verify provider map rendering. */
internal fun SemanticsNodeInteraction.captureFrontendReview(scene: String) {
    if (InstrumentationRegistry.getArguments().getString("captureFrontendReview") != "true") return
    require(scene.matches(Regex("[a-z0-9-]+")))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(requireNotNull(context.getExternalFilesDir(null)), "frontend-review")
    check(directory.isDirectory || directory.mkdirs())
    val bitmap = captureToImage().asAndroidBitmap()
    File(directory, "$scene-api${Build.VERSION.SDK_INT}.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
