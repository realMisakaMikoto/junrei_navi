package cn.anitabi.navigator.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.anitabi.navigator.data.images.AnitabiImageVariant

@Composable
internal fun DiscoveryImageViewer(url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        var original by remember(url) { mutableStateOf(false) }
        var scale by remember(url, original) { mutableFloatStateOf(1f) }
        var offset by remember(url, original) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            DiscoveryImage(url, if (original) AnitabiImageVariant.ORIGINAL else AnitabiImageVariant.DISPLAY,
                description = "地点截图", contentScale = ContentScale.Fit,
                foreground = Color.White, background = Color.Black,
                modifier = Modifier.fillMaxSize().transformable(transform),
                onRetry = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero },
                imageModifier = Modifier.graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "关闭图片", tint = Color.White)
            }
            if (!original) TextButton(onClick = { original = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                Text("\u52a0\u8f7d\u539f\u56fe", color = Color.White)
            }
        }
    }
}
