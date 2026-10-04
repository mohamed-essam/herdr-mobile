package dev.herdr.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType

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
 * Assistant text as markdown: Geist body, JetBrains Mono code, in the app's
 * Catppuccin colors. Headings are scaled down to chat size. Link taps go
 * through [ChatLinkHandler], which opens only http(s) and mailto links (see
 * [isOpenableLink]).
 */
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val body = HerdrType.body.copy(color = c.text)
    val heading = HerdrType.title.copy(color = c.text)
    val code = HerdrType.code.copy(color = c.text)
    val context = LocalContext.current
    val links = remember(context) { ChatLinkHandler(context) }
    // Parse in composition: an async parse first lays the item out empty, and
    // the list's scroll-to-newest would land short of the real height.
    val state = rememberMarkdownState(text, immediate = true)
    CompositionLocalProvider(LocalUriHandler provides links) {
        Markdown(
            state,
            colors = markdownColor(
                text = c.text,
                codeBackground = c.mantle,
                dividerColor = c.surface0,
                tableBackground = c.mantle,
            ),
            typography = markdownTypography(
                h1 = heading.copy(fontSize = 19.sp, lineHeight = 25.sp), h2 = heading.copy(fontSize = 17.sp, lineHeight = 23.sp),
                h3 = heading, h4 = heading, h5 = heading, h6 = heading,
                text = body, paragraph = body, ordered = body, bullet = body, list = body, table = body,
                code = code,
                inlineCode = code.copy(fontSize = 13.sp),
                quote = body.copy(color = c.overlay2),
                textLink = TextLinkStyles(SpanStyle(color = c.blue, textDecoration = TextDecoration.Underline)),
            ),
            modifier = modifier.fillMaxWidth(),
        )
    }
}
