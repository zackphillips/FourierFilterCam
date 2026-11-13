// swift-tools-version:5.9
import PackageDescription

// Platform-neutral DSP for Fourier Filter Cam. Builds and tests on Linux
// with the reference DFT; on Apple platforms the engine uses Accelerate.
let package = Package(
    name: "FourierDSP",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "FourierDSP", targets: ["FourierDSP"]),
    ],
    targets: [
        .target(name: "FourierDSP"),
        .testTarget(name: "FourierDSPTests", dependencies: ["FourierDSP"]),
    ]
)
