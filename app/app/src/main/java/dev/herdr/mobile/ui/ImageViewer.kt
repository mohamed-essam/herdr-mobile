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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdr.mobile.data.ImageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Decoded-pixel caps: an inline image, and the full-screen viewer. */
const val INLINE_MAX_PIXELS = 4_000_000L
const val VIEWER_MAX_PIXELS = 12_000_000L

private val INLINE_MAX_HEIGHT = 240.dp

/**
 * The power-of-two inSampleSize for a [width]×[height] image shown fitted in a
 * [boxWidth]×[boxHeight] px box: the largest that still covers the shown size,
 * raised further until the decode is at most [maxPixels]. Null when the size
 * is unknown (not an image, or a header BitmapFactory can't read).
 */
fun sampleSizeFor(width: Int, height: Int, boxWidth: Int, boxHeight: Int, maxPixels: Long): Int? {
    if (width <= 0 || height <= 0) return null
    // The fitted scale; both dimensions count, so a very tall image is bounded by the box height.
    val fit = min(1.0, min(boxWidth.coerceAtLeast(1).toDouble() / width, boxHeight.coerceAtLeast(1).toDouble() / height))
    var n = 1
    while (n * 2 <= 1 / fit) n *= 2
    while ((width / n).toLong() * (height / n) > maxPixels) n *= 2
    return n
}

/** How far an image of [content] px, zoomed by [scale], may pan either way inside [box] px. */
fun maxPan(content: Float, box: Float, scale: Float): Float = max(0f, (content * scale - box) / 2)

private fun decodeSampled(bytes: ByteArray, boxWidth: Int, boxHeight: Int, maxPixels: Long): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val n = sampleSizeFor(bounds.outWidth, bounds.outHeight, boxWidth, boxHeight, maxPixels) ?: return null
    val opts = BitmapFactory.Options().apply { inSampleSize = n }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

private sealed interface Decoded {
    data object Pending : Decoded
    data object Failed : Decoded
    class Done(val bitmap: ImageBitmap) : Decoded
}

/**
 * Decodes off the main thread, sampled down to the box it is shown in: full-size
 * bitmaps of the 30 cached images (or one huge one) could take hundreds of MB.
 */
@Composable
private fun rememberDecoded(bytes: ByteArray, boxWidth: Int, boxHeight: Int, maxPixels: Long): State<Decoded> =
    produceState<Decoded>(Decoded.Pending, bytes, boxWidth, boxHeight, maxPixels) {
        value = withContext(Dispatchers.Default) {
            runCatching { decodeSampled(bytes, boxWidth, boxHeight, maxPixels) }.getOrNull()
                ?.let { Decoded.Done(it) } ?: Decoded.Failed
        }
    }

/** An image of a chat event: fetched on first show, tap opens [ImageViewer]. */
@Composable
fun ChatImage(vm: DashboardViewModel, paneId: String, id: String, modifier: Modifier = Modifier) {
    val state by remember(paneId, id) { vm.chatImage(paneId, id) }.collectAsState()
    // The window width bounds the list item; the height is the inline cap.
    val boxWidth = LocalWindowInfo.current.containerSize.width
    val boxHeight = with(LocalDensity.current) { INLINE_MAX_HEIGHT.roundToPx() }
    var viewing by remember(id) { mutableStateOf(false) }
    when (val s = state) {
        ImageState.Loading -> Placeholder(modifier)
        ImageState.Missing -> Unavailable(modifier)
        is ImageState.Ready -> {
            val decoded by rememberDecoded(s.bytes, boxWidth, boxHeight, INLINE_MAX_PIXELS)
            when (val d = decoded) {
                Decoded.Pending -> Placeholder(modifier)
                Decoded.Failed -> Unavailable(modifier)
                is Decoded.Done -> Image(
                    d.bitmap, contentDescription = "image",
                    contentScale = ContentScale.Fit,
                    modifier = modifier.heightIn(max = INLINE_MAX_HEIGHT).clip(RoundedCornerShape(8.dp))
                        .clickable { viewing = true },
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
        // Twice the screen each way leaves zoomed-in detail without a full-size decode.
        val screen = LocalWindowInfo.current.containerSize
        val decoded by rememberDecoded(bytes, screen.width * 2, screen.height * 2, VIEWER_MAX_PIXELS)
        val bitmap = (decoded as? Decoded.Done)?.bitmap
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val b = bitmap ?: return@detectTransformGestures
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        // The fitted image's size on screen; panning stops at its edges.
                        val fit = min(size.width.toFloat() / b.width, size.height.toFloat() / b.height)
                        val mx = maxPan(b.width * fit, size.width.toFloat(), scale)
                        val my = maxPan(b.height * fit, size.height.toFloat(), scale)
                        val next = offset + pan
                        offset = Offset(next.x.coerceIn(-mx, mx), next.y.coerceIn(-my, my))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (decoded) {
                Decoded.Failed -> Text("image unavailable", color = Color.White, style = MaterialTheme.typography.labelSmall)
                else -> bitmap?.let {
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
}
