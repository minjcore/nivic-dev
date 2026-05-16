// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Saving",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "Saving", targets: ["Saving"]),
    ],
    targets: [
        .target(
            name: "Saving",
            path: "Sources/Saving"
        ),
    ]
)
