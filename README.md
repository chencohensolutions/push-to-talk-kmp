# Talk (Android, later iOS)

Push-to-talk, messages and calls between the people who share one self-hosted server,
[ptt-server](../push-to-talk-server-go) (`push-to-talk-server-go`). Works like Signal, looks like
[Journal](https://github.com/chencohensolutions/journal-client-kmp). "Talk" is a placeholder name.

Built with **Kotlin Multiplatform**: the logic is shared and tested once in `sharedLogic`, and each platform has its
own UI (Jetpack Compose on Android now; SwiftUI on iOS after phase 6).

## Phase 1: enroll and find the server

- **Enrollment.** Scan the server's QR code (`ptt-server enroll-code`), paste its link, or type the code and address.
  The phone makes an EC P-256 key in StrongBox or the TEE and trades a certificate request for an mTLS client
  certificate. A link pins the server's key from the first connection.
- **Finding the server.** When the address in use stops answering, the app tries the other addresses it keeps (the
  one it enrolled at and the ones the server listed), then looks the server up in the BitTorrent DHT (BEP 44, a
  record only who knows the server can open).
- **Chats home** with its empty state, a Calls tab, and Settings (this phone, the server, Register again).

What comes next is in the plan: https://claude.ai/artifact/CPewKaHcVw4FeLyKthu2Cm, and [docs/design.md](docs/design.md).

## Build

```sh
./gradlew :sharedLogic:testAndroidHostTest   # shared tests on the JVM
./gradlew :androidApp:assembleDebug          # the debug APK
```

JDK 21 and the Android SDK (compileSdk 37). GitHub Actions runs both on every push
([.github/workflows/build.yml](.github/workflows/build.yml)).

## License

[AGPL-3.0](LICENSE), as libsignal (used from phase 2) requires.
