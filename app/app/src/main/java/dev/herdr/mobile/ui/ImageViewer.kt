package dev.herdr.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType
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
import dev.herdr.mobile.net.PixelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/**
 * An inline image's shown size for an original of [size] px in a [maxWidth]×[maxHeight]
 * px box: fitted, never upscaled. It depends on the original pixels only (not on
 * how far the decode was sampled down), so a placeholder sized from the event's
 * imageSizes takes exactly the space the decoded image will.
 */
fun inlineImageSize(size: PixelSize, maxWidth: Int, maxHeight: Int): PixelSize {
    val fit = min(1.0, min(maxWidth.toDouble() / size.width, maxHeight.toDouble() / size.height))
    return PixelSize((size.width * fit).roundToInt().coerceAtLeast(1), (size.height * fit).roundToInt().coerceAtLeast(1))
}

/** How far an image of [content] px, zoomed by [scale], may pan either way inside [box] px. */
fun maxPan(content: Float, box: Float, scale: Float): Float = max(0f, (content * scale - box) / 2)

private fun decodeSampled(bytes: ByteArray, boxWidth: Int, boxHeight: Int, maxPixels: Long): Decoded.Done? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val n = sampleSizeFor(bounds.outWidth, bounds.outHeight, boxWidth, boxHeight, maxPixels) ?: return null
    val opts = BitmapFactory.Options().apply { inSampleSize = n }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() ?: return null
    return Decoded.Done(bitmap, PixelSize(bounds.outWidth, bounds.outHeight))
}

private sealed interface Decoded {
    data object Pending : Decoded
    data object Failed : Decoded
    /** [original]: the undecoded image's size, which the shown size is computed from. */
    class Done(val bitmap: ImageBitmap, val original: PixelSize) : Decoded
}

/**
 * Decodes off the main thread, sampled down to the box it is shown in: full-size
 * bitmaps of the 30 cached images (or one huge one) could take hundreds of MB.
 */
@Composable
private fun rememberDecoded(bytes: ByteArray, boxWidth: Int, boxHeight: Int, maxPixels: Long): State<Decoded> =
    produceState<Decoded>(Decoded.Pending, bytes, boxWidth, boxHeight, maxPixels) {
        value = withContext(Dispatchers.Default) {
            runCatching { decodeSampled(bytes, boxWidth, boxHeight, maxPixels) }.getOrNull() ?: Decoded.Failed
        }
    }

/**
 * An image of a chat event: fetched on first show, tap opens [ImageViewer].
 * With its [size] (the event's imageSizes) the placeholder already takes the
 * image's final space, so the list doesn't jump when it loads.
 */
@Composable
fun ChatImage(vm: DashboardViewModel, paneId: String, id: String, size: PixelSize? = null, modifier: Modifier = Modifier) {
    val state by remember(paneId, id) { vm.chatImage(paneId, id) }.collectAsState()
    val density = LocalDensity.current
    // The window width bounds the decode; the height is the inline cap.
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val boxHeight = with(density) { INLINE_MAX_HEIGHT.roundToPx() }
    var viewing by remember(id) { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val boxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else windowWidth
        val shown = { px: PixelSize ->
            val s = inlineImageSize(px, boxWidth, boxHeight)
            with(density) { Modifier.size(s.width.toDp(), s.height.toDp()) }
        }
        when (val s = state) {
            ImageState.Loading -> Placeholder(size?.let(shown))
            ImageState.Missing -> Unavailable()
            is ImageState.Ready -> {
                val decoded by rememberDecoded(s.bytes, windowWidth, boxHeight, INLINE_MAX_PIXELS)
                when (val d = decoded) {
                    Decoded.Pending -> Placeholder(size?.let(shown))
                    Decoded.Failed -> Unavailable()
                    is Decoded.Done -> Image(
                        d.bitmap, contentDescription = "image",
                        contentScale = ContentScale.Fit,
                        // The outline shows a dark screenshot's bounds on the dark background.
                        modifier = shown(d.original).clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Herdr.colors.surface0, RoundedCornerShape(8.dp))
                            .clickable { viewing = true },
                    )
                }
                if (viewing) ImageViewer(s.bytes) { viewing = false }
            }
        }
    }
}

/** The image's final space when its size is known, else a generic box. */
@Composable
private fun Placeholder(sized: Modifier?) {
    Box(
        (sized ?: Modifier.size(width = 160.dp, height = 120.dp)).clip(RoundedCornerShape(8.dp))
            .background(Herdr.colors.base),
    )
}

@Composable
private fun Unavailable() {
    Text(
        "image unavailable",
        style = HerdrType.meta, color = Herdr.colors.overlay2,
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
