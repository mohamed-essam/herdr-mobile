import CoreGraphics
import Foundation
import ImageIO

// Sizing and decoding chat images. Ported from Android's ImageViewer.kt; ImageIO's
// thumbnailing replaces BitmapFactory's power-of-two sampling.

/// Decoded-pixel caps: an inline image, and the full-screen viewer.
public let inlineMaxPixels = 4_000_000
public let viewerMaxPixels = 12_000_000

/// An inline image's shown size for an original of `size` px in a box: fitted, never
/// upscaled. It depends on the original only, so a placeholder sized from the event's
/// `imageSizes` takes exactly the space the decoded image will.
public func inlineImageSize(_ size: PixelSize, maxWidth: Int, maxHeight: Int) -> PixelSize {
    let fit = min(1, Double(maxWidth) / Double(size.width), Double(maxHeight) / Double(size.height))
    return PixelSize(width: max(1, Int((Double(size.width) * fit).rounded())), height: max(1, Int((Double(size.height) * fit).rounded())))
}

/// The size to decode an image of `original` px shown in `box` px: fitted (never
/// upscaled), then shrunk until it is at most `maxPixels`.
public func decodeSize(_ original: PixelSize, box: PixelSize, maxPixels: Int) -> PixelSize {
    let fitted = inlineImageSize(original, maxWidth: max(1, box.width), maxHeight: max(1, box.height))
    let area = Double(fitted.width) * Double(fitted.height)
    guard area > Double(maxPixels) else { return fitted }
    let k = (Double(maxPixels) / area).squareRoot()
    return PixelSize(width: max(1, Int(Double(fitted.width) * k)), height: max(1, Int(Double(fitted.height) * k)))
}

/// The pixel size of encoded image `data`; nil when it isn't an image ImageIO can read.
public func pixelSize(of data: Data) -> PixelSize? {
    guard let src = CGImageSourceCreateWithData(data as CFData, nil),
          let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
          let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
          w > 0, h > 0 else { return nil }
    return PixelSize(width: w, height: h)
}

/// `data` decoded no bigger than `box` px and `maxPixels`, so 30 cached screenshots (or
/// one huge one) never take hundreds of MB. Nil when it doesn't decode.
public func decodeImage(_ data: Data, box: PixelSize, maxPixels: Int) -> CGImage? {
    guard let size = pixelSize(of: data), let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let target = decodeSize(size, box: box, maxPixels: maxPixels)
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: max(target.width, target.height),
    ]
    return CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary)
}

/// How far an image of `content` pt, zoomed by `scale`, may pan either way inside `box` pt.
public func maxPan(content: Double, box: Double, scale: Double) -> Double { max(0, (content * scale - box) / 2) }
