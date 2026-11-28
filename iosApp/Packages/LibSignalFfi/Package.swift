// swift-tools-version:5.9

// The Rust half of libsignal (the Signal protocol), prebuilt by Signal for iOS. The Swift half is built from source
// (project.yml's libsignal package); both must be the same release, or linking fails on signal_ffi_version_X_Y_Z.
// To upgrade: change the version here and in project.yml, and pin the new download's checksum
// (`swift package compute-checksum` on the zip; .github/workflows/ios.yml prints it and fails until it is pinned).
// The pin is what guards the download: a file that changes later doesn't build.

import PackageDescription

let package = Package(
    name: "LibSignalFfi",
    products: [
        .library(name: "LibSignalFfi", targets: ["LibSignalFfi"]),
    ],
    targets: [
        .binaryTarget(
            name: "LibSignalFfi",
            url: "https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.108.5.xcframework.zip",
            checksum: "0ee12a1946559ba46f5aea8903b2cf5ff7f06f753f860e481031450dc9aa3aad"
        ),
    ]
)
