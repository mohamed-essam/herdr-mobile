package dev.herdr.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Assistant text as markdown, in the app's Catppuccin colours. Headings are
 * scaled down to chat size; links open in the browser (the library's default
 * LocalUriHandler).
 */
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val body = type.bodyMedium
    val code = type.bodySmall.copy(fontFamily = FontFamily.Monospace)
    // Parse in composition: an async parse first lays the item out empty, and
    // the list's scroll-to-newest would land short of the real height.
    val state = rememberMarkdownState(text, immediate = true)
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
