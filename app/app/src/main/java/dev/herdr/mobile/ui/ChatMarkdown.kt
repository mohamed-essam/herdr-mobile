package dev.herdr.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

private val WEB_LINK = Regex("""https?://[^/?#\s]+\S*""", RegexOption.IGNORE_CASE)
private val MAIL_LINK = Regex("""mailto:\S+""", RegexOption.IGNORE_CASE)

/**
 * Whether a link in assistant text may be opened: web and mail links only.
 * The text is model output, so tel:, intent:, content:, file: (which would
 * crash with FileUriExposedException) and app deep links are refused.
 */
fun isOpenableLink(uri: String): Boolean = uri.trim().let { WEB_LINK.matches(it) || MAIL_LINK.matches(it) }

/** Opens allowed links in a browsable app; everything else is dropped with a toast. */
private class ChatLinkHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        if (!isOpenableLink(uri)) {
            Toast.makeText(context, "link not opened", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri.trim())).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "no app to open the link", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "link not opened", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Assistant text as markdown, in the app's Catppuccin colours. Headings are
 * scaled down to chat size. Link taps go through [ChatLinkHandler], which opens
 * only http(s) and mailto links (see [isOpenableLink]).
 */
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val body = type.bodyMedium
    val code = type.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val context = LocalContext.current
    val links = remember(context) { ChatLinkHandler(context) }
    // Parse in composition: an async parse first lays the item out empty, and
    // the list's scroll-to-newest would land short of the real height.
    val state = rememberMarkdownState(text, immediate = true)
    CompositionLocalProvider(LocalUriHandler provides links) {
        Markdown(
            state,
            colors = markdownColor(
                text = colors.onBackground,
                codeBackground = colors.surfaceContainer,
                dividerColor = colors.outlineVariant,
                tableBackground = colors.surfaceContainerLow,
            ),
            typography = markdownTypography(
                h1 = type.titleLarge, h2 = type.titleMedium, h3 = type.titleSmall,
                h4 = type.titleSmall, h5 = type.titleSmall, h6 = type.titleSmall,
                text = body, paragraph = body, ordered = body, bullet = body, list = body, table = body,
                code = code,
                inlineCode = body.copy(fontFamily = FontFamily.Monospace),
                quote = body.copy(color = colors.onSurfaceVariant),
                textLink = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
            ),
            modifier = modifier.fillMaxWidth(),
        )
    }
}
