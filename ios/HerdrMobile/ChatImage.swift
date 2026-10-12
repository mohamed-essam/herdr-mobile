import HerdrKit
import SwiftUI

/// The tallest an inline image is shown, in points.
private let inlineMaxHeight: CGFloat = 240

/// An image of a chat event, fetched from the companion on first show. With its size (the
/// event's `imageSizes`) the placeholder already takes the image's final space, so the list
/// doesn't jump when it loads. A tap opens it full screen. Ported from Android's ImageViewer.kt.
struct ChatImageView: View {
    let chat: ChatStore
    let paneId: String
    let id: String
    let size: PixelSize?
    /// The widest it may be shown, in points.
    let maxWidth: CGFloat
    @State private var decoded: CGImage?
    /// The bytes `decoded` came from: the viewer shows them even after the cache evicts the image.
    @State private var bytes: Data?
    @State private var failed = false
    @State private var viewing = false
    @Environment(\.displayScale) private var scale
    @Environment(\.herdr) private var c

    var body: some View {
        let state = chat.image(paneId: paneId, id: id)
        Group {
            if state == .missing || failed {
                Text("image unavailable").font(HerdrFont.meta).foregroundStyle(c.overlay2)
                    .accessibilityIdentifier("imageUnavailable-\(id)")
            } else if let decoded {
                Button { viewing = true } label: {
                    Image(decorative: decoded, scale: 1).resizable()
                        .frame(size: shown(size ?? PixelSize(width: decoded.width, height: decoded.height)))
                        .clipShape(.rect(cornerRadius: HerdrRadius.badge))
                        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.badge).stroke(c.surface0))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("image")
                .accessibilityIdentifier("image-\(id)")
            } else {
                RoundedRectangle(cornerRadius: HerdrRadius.badge).fill(c.base)
                    .frame(size: size.map(shown) ?? CGSize(width: 160, height: 120))
                    .accessibilityIdentifier("imageLoading-\(id)")
            }
        }
        .onAppear { chat.loadImage(paneId: paneId, id: id) }
        .task(id: state) {
            guard case .ready(let data) = state else { return }
            let box = PixelSize(width: Int(maxWidth * scale), height: Int(inlineMaxHeight * scale))
            decoded = await Task.detached { decodeImage(data, box: box, maxPixels: inlineMaxPixels) }.value
            failed = decoded == nil
            bytes = decoded == nil ? nil : data
        }
        .fullScreenCover(isPresented: $viewing) {
            if let bytes { ImageViewer(data: bytes) { viewing = false } }
        }
    }

    /// The shown size in points of a `px` image. Callers pass the original pixels when known,
    /// so it doesn't depend on how far the decode was sampled down.
    private func shown(_ px: PixelSize) -> CGSize {
        let s = inlineImageSize(px, maxWidth: Int(maxWidth * scale), maxHeight: Int(inlineMaxHeight * scale))
        return CGSize(width: CGFloat(s.width) / scale, height: CGFloat(s.height) / scale)
    }
}

/// A full-screen image with pinch-zoom (1–8×) and pan to its edges; a tap closes it.
struct ImageViewer: View {
    let data: Data
    let onDismiss: () -> Void
    @State private var image: CGImage?
    @State private var zoom = 1.0
    @State private var zoomBase = 1.0
    @State private var pan = CGSize.zero
    @State private var panBase = CGSize.zero
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let image {
                    Image(decorative: image, scale: 1).resizable().scaledToFit()
                        .scaleEffect(zoom)
                        .offset(pan)
                } else {
                    ProgressView().tint(.white)
                }
            }
            .contentShape(.rect)
            .onTapGesture(perform: onDismiss)
            .gesture(MagnifyGesture()
                .onChanged { zoom = min(8, max(1, zoomBase * $0.magnification)); pan = clamp(pan, geo.size) }
                .onEnded { _ in zoomBase = zoom; panBase = pan }
                .simultaneously(with: DragGesture()
                    .onChanged { pan = clamp(CGSize(width: panBase.width + $0.translation.width, height: panBase.height + $0.translation.height), geo.size) }
                    .onEnded { _ in panBase = pan }))
            .task {
                // Twice the screen each way leaves zoomed-in detail without a full-size decode.
                let box = PixelSize(width: Int(geo.size.width * scale * 2), height: Int(geo.size.height * scale * 2))
                image = await Task.detached { [data] in decodeImage(data, box: box, maxPixels: viewerMaxPixels) }.value
            }
        }
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("image")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("imageViewer")
        .maskedInReports()
    }

    /// Panning stops at the zoomed image's edges.
    private func clamp(_ p: CGSize, _ box: CGSize) -> CGSize {
        guard let image else { return .zero }
        let fit = min(box.width / CGFloat(image.width), box.height / CGFloat(image.height))
        let mx = maxPan(content: Double(CGFloat(image.width) * fit), box: Double(box.width), scale: zoom)
        let my = maxPan(content: Double(CGFloat(image.height) * fit), box: Double(box.height), scale: zoom)
        return CGSize(width: min(mx, max(-mx, p.width)), height: min(my, max(-my, p.height)))
    }
}

extension View {
    fileprivate func frame(size: CGSize) -> some View { frame(width: size.width, height: size.height) }
}
