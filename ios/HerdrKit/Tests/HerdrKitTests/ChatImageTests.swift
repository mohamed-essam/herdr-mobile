import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import HerdrKit

/// A solid PNG of `width`×`height` px.
private func png(_ width: Int, _ height: Int) throws -> Data {
    let ctx = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                     space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    ctx.setFillColor(CGColor(red: 0.8, green: 0.6, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let out = NSMutableData()
    let dest = try #require(CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(dest, try #require(ctx.makeImage()), nil)
    #expect(CGImageDestinationFinalize(dest))
    return out as Data
}

@Test func anInlineImageIsFittedNeverUpscaled() {
    #expect(inlineImageSize(PixelSize(width: 1280, height: 2856), maxWidth: 1000, maxHeight: 720) == PixelSize(width: 323, height: 720))
    #expect(inlineImageSize(PixelSize(width: 4000, height: 1000), maxWidth: 1000, maxHeight: 720) == PixelSize(width: 1000, height: 250))
    #expect(inlineImageSize(PixelSize(width: 100, height: 50), maxWidth: 1000, maxHeight: 720) == PixelSize(width: 100, height: 50))
    #expect(inlineImageSize(PixelSize(width: 10_000, height: 1), maxWidth: 100, maxHeight: 100) == PixelSize(width: 100, height: 1))
}

@Test func anImageIsDecodedNoBiggerThanItsBoxAndPixelCap() {
    #expect(decodeSize(PixelSize(width: 4000, height: 3000), box: PixelSize(width: 1000, height: 1000), maxPixels: 4_000_000)
            == PixelSize(width: 1000, height: 750))
    #expect(decodeSize(PixelSize(width: 400, height: 300), box: PixelSize(width: 1000, height: 1000), maxPixels: 4_000_000)
            == PixelSize(width: 400, height: 300), "never upscaled")
    let capped = decodeSize(PixelSize(width: 8000, height: 8000), box: PixelSize(width: 8000, height: 8000), maxPixels: 1_000_000)
    #expect(capped.width * capped.height <= 1_000_000 && capped.width >= 990)
}

@Test func decodesAPngSampledDown() throws {
    let data = try png(1200, 600)
    #expect(pixelSize(of: data) == PixelSize(width: 1200, height: 600))
    let image = try #require(decodeImage(data, box: PixelSize(width: 300, height: 300), maxPixels: 4_000_000))
    #expect(image.width == 300 && image.height == 150)
    #expect(decodeImage(Data("not an image".utf8), box: PixelSize(width: 300, height: 300), maxPixels: 4_000_000) == nil)
    #expect(pixelSize(of: Data()) == nil)
}

@Test func panningStopsAtTheZoomedImagesEdges() {
    #expect(maxPan(content: 100, box: 300, scale: 2) == 0)
    #expect(maxPan(content: 300, box: 300, scale: 2) == 150)
}
