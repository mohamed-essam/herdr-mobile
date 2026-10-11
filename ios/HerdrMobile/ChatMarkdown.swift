import HerdrKit
import MarkdownUI
import SwiftUI

/// Assistant text as Markdown in herdr's colors, headings scaled to chat size. Long-press
/// copies a block. Links go through the screen's `openURL` (see `chatLinks`).
///
/// It never loads an image (brief override 5): the text is model output, so `![](url)` could
/// make the phone request any URL. Only images the companion serves render, and those come
/// from an event's image ids, not from Markdown.
struct ChatMarkdown: View {
    let text: String
    @Environment(\.herdr) private var c

    var body: some View {
        Markdown(text)
            .markdownTheme(theme)
            .markdownImageProvider(NoImageProvider())
            .markdownInlineImageProvider(NoInlineImageProvider())
            .textSelection(.enabled)
    }

    private var theme: Theme {
        Theme()
            .text {
                ForegroundColor(c.text)
                FontSize(15)
            }
            .code {
                FontFamilyVariant(.monospaced)
                FontSize(13)
                BackgroundColor(c.mantle)
            }
            .strong { FontWeight(.semibold) }
            .link {
                ForegroundColor(c.blue)
                UnderlineStyle(.single)
            }
            .heading1 { $0.label.markdownMargin(top: 12, bottom: 8).markdownTextStyle { FontWeight(.semibold); FontSize(19) } }
            .heading2 { $0.label.markdownMargin(top: 12, bottom: 8).markdownTextStyle { FontWeight(.semibold); FontSize(17) } }
            .heading3 { $0.label.markdownMargin(top: 12, bottom: 8).markdownTextStyle { FontWeight(.semibold); FontSize(15) } }
            .paragraph { $0.label.relativeLineSpacing(.em(0.2)).markdownMargin(top: 0, bottom: 8) }
            .blockquote { $0.label.markdownTextStyle { ForegroundColor(c.overlay2) }.padding(.leading, 12) }
            // Wrapped, not a horizontal ScrollView: that one never settles its height
            // inside the timeline's lazy stack (a 100% CPU layout loop).
            .codeBlock { config in
                config.label
                    .markdownTextStyle {
                        FontFamilyVariant(.monospaced)
                        FontSize(12)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(c.mantle, in: .rect(cornerRadius: HerdrRadius.badge))
                    .markdownMargin(top: 0, bottom: 8)
            }
            .table { $0.label.markdownTableBorderStyle(.init(color: c.surface0)).markdownMargin(top: 0, bottom: 8) }
    }
}

/// A Markdown block image: nothing, and no request.
private struct NoImageProvider: ImageProvider {
    func makeImage(url: URL?) -> some View { EmptyView() }
}

/// A Markdown inline image: left out of the line, and no request.
private struct NoInlineImageProvider: InlineImageProvider {
    struct NotLoaded: Error {}
    func image(with url: URL, label: String) async throws -> Image { throw NotLoaded() }
}

extension View {
    /// Opens only web and mail links (`isOpenableLink`); any other says "link not opened".
    func chatLinks(refused: @escaping () -> Void) -> some View {
        environment(\.openURL, OpenURLAction { url in
            if isOpenableLink(url.absoluteString) { return .systemAction }
            refused()
            return .handled
        })
    }
}
