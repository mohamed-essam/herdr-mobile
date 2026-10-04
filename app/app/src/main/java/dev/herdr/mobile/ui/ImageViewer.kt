package dev.herdr.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdr.mobile.data.ImageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The largest power-of-two inSampleSize that still leaves the image at least
 * [targetWidth] px wide (1 when either size is unknown).
 */
fun sampleSizeFor(width: Int, targetWidth: Int): Int {
    if (width <= 0 || targetWidth <= 0) return 1
    var n = 1
    while (width / (n * 2) >= targetWidth) n *= 2
    return n
}

// Full-size bitmaps of the 30 cached images could take ~110 MB; decode
// roughly at the width they are shown.
private fun decodeSampled(bytes: ByteArray, targetWidth: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/** Decodes off the main thread; null while decoding or when the bytes aren't an image. */
@Composable
private fun rememberDecoded(bytes: ByteArray, targetWidth: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, bytes, targetWidth) {
        value = withContext(Dispatchers.Default) { runCatching { decodeSampled(bytes, targetWidth) }.getOrNull() }
    }

/** An image of a chat event: fetched on first show, tap opens [ImageViewer]. */
@Composable
fun ChatImage(vm: DashboardViewModel, paneId: String, id: String, modifier: Modifier = Modifier) {
    val state by remember(paneId, id) { vm.chatImage(paneId, id) }.collectAsState()
    val screenWidth = LocalWindowInfo.current.containerSize.width
    var viewing by remember(id) { mutableStateOf(false) }
    when (val s = state) {
        ImageState.Loading -> Placeholder(modifier)
        ImageState.Missing -> Unavailable(modifier)
        is ImageState.Ready -> {
            val bitmap by rememberDecoded(s.bytes, screenWidth)
            val b = bitmap
            if (b == null) {
                Placeholder(modifier)
            } else {
                Image(
                    b, contentDescription = "image",
                    contentScale = ContentScale.Fit,
                    modifier = modifier.heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).clickable { viewing = true },
                )
            }
            if (viewing) ImageViewer(s.bytes) { viewing = false }
        }
    }
}

@Composable
private fun Placeholder(modifier: Modifier) {
    Box(
        modifier.size(width = 160.dp, height = 120.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun Unavailable(modifier: Modifier) {
    Text(
        "image unavailable", modifier,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Full-screen image with pinch-zoom and pan; back or a tap closes it. */
@Composable
fun ImageViewer(bytes: ByteArray, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Twice the screen width leaves zoomed-in detail without a full-size decode.
        val bitmap by rememberDecoded(bytes, LocalWindowInfo.current.containerSize.width * 2)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it, contentDescription = "image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                )
            }
        }
    }
}
