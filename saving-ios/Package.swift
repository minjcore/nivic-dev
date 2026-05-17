// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Saving",
    platforms: [.iOS(.v17), .macOS(.v14)],
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
