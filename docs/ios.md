# The iOS app

Talk on iOS (hen asked for it 2026-11-28): the same shared logic as Android, with only the screens native, in
SwiftUI. This page says how it is put together, what is built, and what is next. The screens themselves are the
ones [screens/](screens/) describes; where iOS differs, it says so here.

## How it is put together

```
iosApp/Talk (Swift)                      sharedLogic (Kotlin)
  Screens/, Parts/, Style/   ── draw ──▶   controllers' state (currentState, observe)
  App/TalkModel              ── calls ─▶   IosPtt: the controllers, the one Messaging hub
  Host/TalkHost              ◀─ asks ──    IosHost, IosSignal, IosDb (interfaces)
```

- **`IosPtt`** (`sharedLogic/src/iosMain/.../IosPtt.kt`) is iOS's entry, as `AndroidPtt.kt` is Android's: it builds
  the settings, the mTLS client, the key store, the encrypted store, the crypto engine, the one `Messaging` hub and
  the controllers (`connection`, `enrollment`, `renewal`, `settingsController`, `messaging`, `pairing`).
- **The screens** hold no logic. `TalkModel` keeps a `Store` per controller (its `currentState`, followed with
  `observe`); a screen reads the store it draws and calls the controller's methods. `IosViews` (Kotlin) picks out of
  `MessagingState` what a screen draws (the chat rows in order, one chat's last messages), so the state's big maps
  aren't copied into Swift on every change.
- **`IosHost`** is what only Swift can reach, implemented by `Host/TalkHost.swift` and handed to `IosPtt`:
  - `DeviceKeys.swift`: the device's mTLS key, EC P-256 in the Secure Enclave (in the Keychain as software on a
    simulator), never exportable; its certificate beside it; the `URLCredential` the TLS client presents.
  - `ServerTrust.swift`: the pieces of the server check. The rule itself (the pinned key of the enrollment link,
    else the server's own CA) is Kotlin's, in `IosMtlsHttpClientFactory`.
  - `SealedDb.swift`: the encrypted store (`IosDb`): the system's SQLite with every value sealed by AES-256-GCM
    under a key in the Keychain, bound to its space and key. Android's is SQLCipher (`SqlCipherStore`).
  - `SignalEngine.swift`: libsignal (`IosSignal`), through Signal's Swift library. The rules around it (who is
    trusted, which sessions exist, what is wiped) are Kotlin's, in `IosCryptoEngine`, the same as Android's
    `LibsignalCryptoEngine`. A Swift method can't throw into Kotlin, so each call answers a `SignalReply` whose
    `error` Kotlin turns into the engine's exceptions.
  - CryptoKit's AES-GCM for clips, media and call frames (`Aes256Gcm`).
- **Words.** Both apps say the same words under the same keys: `tools/ios-strings.py` writes
  `Localizable.strings` (English, Hebrew) from the Android app's `strings.xml`. Run it after changing strings and
  commit what it writes; the iOS build checks it. The few words only iOS has are in `Ios.strings` (keys `ios_…`).
- **Style.** `Style/Style.swift` is `LibraryStyle.kt`'s palette, type and shapes; one `MessageBar`, no alerts. Hebrew
  is right to left; addresses, codes and safety numbers stay left to right.
- **The project** is `iosApp/project.yml`, written into `Talk.xcodeproj` by XcodeGen (not committed). A build phase
  runs `./gradlew :sharedLogic:embedAndSignAppleFrameworkForXcode`, which builds the static `SharedLogic` framework
  for the SDK and configuration Xcode is building. libsignal is two packages of one release: its Swift source
  (`signalapp/libsignal`) and the prebuilt Rust half (`Packages/LibSignalFfi`, checksum pinned). Its version is the
  Android app's (`libs.versions.toml`).

## What is built

Milestone 1, the app in the foreground:

- Enrollment: the wizard (code, QR scan, paste, an invite's code), the key in the Secure Enclave, the certificate,
  `ptt://` links. Certificate renewal.
- Finding the server again (the DHT, the kept addresses), the Offline pill, Register again.
- Chats: the list with its cards (pair requests, group invitations, linking), a conversation with texts end to
  end, typing, online and last seen, my messages' look by how far they got, the faces for received and read, delete.
- Contacts: Add contact (QR both ways), Invite a friend, a contact's page, the safety number, block and remove.
- Groups: new group, a group's page, invitations, members.
- Settings: this phone, Your devices, Link a phone, presence, Live typing, email, how the app refers to you.

## What is next

- **Live talk** (push-to-talk and push-to-video in LiveKit rooms): `TalkTransport` over LiveKit's Swift SDK, the
  microphone and speaker (`TalkCapture`, `TalkPlayer`), the Push button, the floating bar's iOS form.
- **Calls** in CallKit (`CallTransport`, `CallHost`), saved calls.
- **Media**: pictures and videos in chats (`MediaPreparer`, the viewer, the share extension).
- **Notifications.** The server pushes through FCM only. iOS needs APNs: a notification service extension that
  decrypts a message on the phone, PushKit for calls, and the PushToTalk framework for talk. This needs an APNs
  sender in `ptt-server` and a paid Apple Developer account.
- **TestFlight**: signing, the App Store Connect record, the release workflow.
- Voice messages and clips, the Talk tab, chat colours, pictures (faces), editing a message, the widget.

## Building

Xcode 26 or newer (libsignal's Swift source needs Swift 6.2).

```sh
brew install xcodegen
cd iosApp && xcodegen && open Talk.xcodeproj     # then Run on a simulator or a phone
```

The first build compiles the Kotlin framework and libsignal's Swift source, a few minutes; later ones are quick.
CI builds the app for the simulator on a Mac (`.github/workflows/ios.yml`) when the iOS app's parts change, and
compiles the shared logic for iOS on Linux on every push (`build.yml`).

Kotlin names in Swift: a sealed type's cases are `EnrollmentStepConfirm`; an object is `IosViews.shared`; an enum
value is `ServerHealth.offline`; `Int` is `Int32`, `Long` is `Int64`, and inside a callback or a nullable they are
`KotlinInt`, `KotlinLong`, `KotlinBoolean`. `Group` is `TalkGroup` in Swift (SwiftUI has its own `Group`).
