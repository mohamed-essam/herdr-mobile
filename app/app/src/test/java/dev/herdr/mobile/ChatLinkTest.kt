package dev.herdr.mobile

import dev.herdr.mobile.ui.isOpenableLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinkTest {
    @Test fun webAndMailLinksOpen() {
        assertTrue(isOpenableLink("https://example.com/a?b=c"))
        assertTrue(isOpenableLink("http://example.com"))
        assertTrue(isOpenableLink("HTTPS://EXAMPLE.COM"))
        assertTrue(isOpenableLink("mailto:a@b.c"))
        assertTrue(isOpenableLink("  https://example.com  "))
    }

    @Test fun otherSchemesAndRelativeLinksDont() {
        for (u in listOf(
            "file:///etc/passwd", "content://x/y", "tel:123", "sms:123", "market://details?id=x",
            "intent://x#Intent;end", "javascript:alert(1)", "myapp://open", "foo/bar.md", "", "https",
            "//example.com", "https:", "http:relative",
        )) assertFalse(u, isOpenableLink(u))
    }
}
