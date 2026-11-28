// swift-tools-version:5.9

// The Rust half of libsignal (the Signal protocol), prebuilt by Signal for iOS. The Swift half is built from source
// (project.yml's libsignal package); both must be the same release, or linking fails on signal_ffi_version_X_Y_Z.
// To upgrade: change the version here and in project.yml, and set the checksum Signal publishes with the release
// (https://github.com/signalapp/libsignal/releases); the iOS job in .github/workflows/build.yml checks it too.

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
            checksum: "CHECKSUM-NOT-PINNED-YET"
        ),
    ]
)
