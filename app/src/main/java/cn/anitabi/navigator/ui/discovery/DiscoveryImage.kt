package cn.anitabi.navigator.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.data.images.AnitabiImageReference
import cn.anitabi.navigator.data.images.AnitabiImageVariant
import cn.anitabi.navigator.data.images.ImageFailure
import cn.anitabi.navigator.data.images.imageFailure
import coil3.compose.AsyncImage

private sealed interface ImageDisplayState {
    data object Loading : ImageDisplayState
    data object Success : ImageDisplayState
    data class Error(val kind: ImageFailure) : ImageDisplayState
}

/** A composition key binds callbacks and retries to exactly one resource/size/request. */
@Composable
internal fun DiscoveryImage(
    url: String?,
    variant: AnitabiImageVariant,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    imageModifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val model = remember(url, variant) { AnitabiImageReference.request(url, variant) }
    var retry by remember(model) { mutableIntStateOf(0) }
    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        if (model == null) {
            val label = if (url == null) "\u6682\u65e0\u56fe\u7247" else imageFailureLabel(ImageFailure.RESOURCE)
            if (url != null && !compact) Text(label, color = foreground, modifier = Modifier.padding(16.dp))
            else Icon(Icons.Rounded.Place, label, Modifier.size(22.dp), tint = foreground)
        } else key(model, retry) {
            var state by remember { mutableStateOf<ImageDisplayState>(ImageDisplayState.Loading) }
            AsyncImage(model = model, contentDescription = description, contentScale = contentScale,
                onLoading = { state = ImageDisplayState.Loading },
                onSuccess = { state = ImageDisplayState.Success },
                onError = { state = ImageDisplayState.Error(imageFailure(it.result.throwable)) },
                modifier = Modifier.fillMaxSize().then(imageModifier))
            when (val current = state) {
                ImageDisplayState.Loading -> CircularProgressIndicator(Modifier.size(24.dp).semantics {
                    contentDescription = "\u6b63\u5728\u52a0\u8f7d\u56fe\u7247"
                }, color = foreground, strokeWidth = 2.dp)
                ImageDisplayState.Success -> Unit
                is ImageDisplayState.Error -> if (compact) {
                    IconButton(onClick = { onRetry(); retry++ }) {
                        Icon(Icons.Rounded.Refresh, "\u56fe\u7247\u52a0\u8f7d\u5931\u8d25\uff0c\u91cd\u8bd5", tint = foreground)
                    }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(imageFailureLabel(current.kind), color = foreground)
                    TextButton(onClick = { onRetry(); retry++ }) { Text("\u91cd\u8bd5\u56fe\u7247", color = foreground) }
                }
            }
        }
    }
}

private fun imageFailureLabel(failure: ImageFailure): String = when (failure) {
    ImageFailure.RESOURCE -> "\u8fd9\u5f20\u56fe\u7247\u6682\u65f6\u4e0d\u53ef\u7528"
    ImageFailure.ACCESS -> "\u56fe\u7247\u670d\u52a1\u6682\u65f6\u62d2\u7edd\u8bf7\u6c42\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5"
    ImageFailure.NETWORK -> "\u56fe\u7247\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5"
    ImageFailure.DECODE -> "\u65e0\u6cd5\u8bfb\u53d6\u8fd9\u5f20\u56fe\u7247"
    ImageFailure.UNKNOWN -> "\u56fe\u7247\u6682\u65f6\u65e0\u6cd5\u52a0\u8f7d"
}
