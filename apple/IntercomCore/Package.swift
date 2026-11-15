// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "IntercomCore",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "IntercomCore", targets: ["IntercomCore"])
    ],
    targets: [
        .target(name: "IntercomCore"),
        .testTarget(name: "IntercomCoreTests", dependencies: ["IntercomCore"]),
    ]
)
