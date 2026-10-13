// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "HerdrKit",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "HerdrKit", targets: ["HerdrKit"])],
    targets: [
        .target(name: "HerdrKit"),
        .testTarget(name: "HerdrKitTests", dependencies: ["HerdrKit"]),
    ]
)
