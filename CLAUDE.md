# Talk client (Kotlin Multiplatform)

Android (and, after phase 6, iOS) app for push-to-talk, messages and calls through one self-hosted server,
`ptt-server` in `../push-to-talk-server-go`. "Talk" is a placeholder name. **Read [docs/design.md](docs/design.md)
first**: what is built and what is next. The whole plan (architecture, decisions, phases) is
https://claude.ai/artifact/CPewKaHcVw4FeLyKthu2Cm; follow it as written. Each screen is described in
[docs/screens/](docs/screens/); update those docs whenever a screen changes.

This project starts from `journal-client-kmp` (enrollment, mTLS, finding the server, the Journal look). When a piece
exists there, copy it and adapt it rather than invent a new one, and keep journal's versions.

## Layout

- `sharedLogic/` — all logic, shared. `commonMain`: controllers with a `StateFlow` state:
  `ConnectionController` (the connection check on `me`, `serverInfo` kept after it, checking again while offline;
  journal's `JournalController`), `EnrollmentController` (the wizard; `ptt://enroll` links in
  `parseEnrollmentLink`), `CertificateRenewal`, `SettingsController`. `discovery/`: `ServerFinder` (the order the
  server is looked for in), `DhtClient` and `DhtRecord` (BEP 44, salt `ptt-server/v1`), `KeptDiscovery` (the pinned
  key and the address list), `ServerId`, `Seal` (XChaCha20-Poly1305, HKDF). `tls/`: `ServerPin`, PEM.
  `enrollment/CertificateRequest.kt`: the PKCS#10 request. `server/ServerHttp.kt`: the one mTLS client, with the
  finder behind it. `settings/PttSettings.kt`: what the phone keeps. Phase 2: `crypto/CryptoEngine.kt` (the one
  door to the Signal protocol), `store/SecureStore.kt` (the encrypted store's spaces), `relay/` (`Frames.kt`, the
  /ws frames of `commonMain/proto/ptt.proto`; `RelaySocket`, the WebSocket with acks and backoff), `messaging/`
  (`Messaging`, the hub; `KeyUploader`; `Sender` with the STALE_DEVICES retry; `Content`, what is inside the
  ciphertext; `PairCode`, the Add contact QR code; `InviteLink`, the Invite a friend link; `LinkCode`, the Link a phone code and the sealed PROVISIONING messages; `InviteMessage`, the text it and its code go out in (phone to phone: the server sends no mail); `Groups`, phase 4's groups (the owner's signed changes, sender keys, the group Send); `MessagingStore`; `MessagingController` and `PairingController`
  for the screens; `ApolloMessagingApi`). GraphQL schema and operations in `commonMain/graphql/` (the schema and the
  .proto are the server's: `./gradlew :sharedLogic:syncServerSchema`).
  `androidMain`: Keystore keys (`AndroidDeviceKeyStore`), the OkHttp mTLS client with the pinned key and the
  server's CA (`AndroidMtlsHttpClientFactory`, with Ktor's WebSockets), libsignal (`crypto/LibsignalCryptoEngine.kt`,
  `SignalStore.kt`), SQLCipher (`store/SqlCipherStore.kt`, its key wrapped in the Keystore), and the factories
  (`AndroidPtt.kt`). `androidHostTest`: libsignal itself on the JVM. `iosMain`: only the actuals common code
  expects, until iOS is built.
- `androidApp/` — Jetpack Compose UI. `ui/style/`: Journal's style (`LibraryStyle.kt` colors, type, shapes, icons;
  `LibraryButtons.kt`; `LibraryPages.kt` with `SettingsScaffold`, `EmptyState`, `TitleBar`; `LibraryMessages.kt`;
  `LibraryChrome.kt` with the Offline pill, the tabs, `HomeCard`, settings cards and rows). The names stay Journal's
  so its style.md applies. `ui/EnrollmentWizard.kt`, `ui/home/HomeApp.kt` (Chats and Calls, the stack of pages over home),
  `ui/settings/SettingsScreen.kt`, `ui/contacts/` (New chat, Add contact, a contact's page, the safety number;
  `ContactParts.kt` with the letter circle and `ContactRow`; `InviteScreen.kt`, Invite a friend), `ui/settings/LinkPhoneScreen.kt` (Link a phone), `ui/contacts/ConversationScreen.kt` (a conversation, a group's too), `ui/groups/` (New group, a group's page), `Push.kt` (TalkApp, FCM registration and `TalkMessagingService`), `Notifications.kt` (notifications with Reply), `ui/Qr.kt` (ZXing QR codes, Google's code scanner).
  `PttViewModel.kt` keeps the controllers alive. Strings in
  `res/values/strings.xml`.

## Conventions

- Logic goes in `sharedLogic` (with tests); the UIs only render state and call controller methods.
- Every screen follows the system's light or dark theme with the library palette; colors, buttons, type, shapes,
  messages, empty states and words follow [docs/screens/style.md](docs/screens/style.md). Ink is the one main action
  (later the talk button), teal is "live", red is leave, remove, revoke.
- Screens, flows and gestures follow Signal's messenger pattern; no Signal name, icon or artwork.
- Anything the server and the app must agree on (the schema, ptt.proto, the QR link, the DHT salt and info strings,
  the sealed record's format) changes in both repos together; `DhtRecordTest` opens a record sealed by the server's
  own test, and `FramesTest` decodes frames its Go code encoded.
- libsignal is reached only through `CryptoEngine`; its version is pinned and upgraded on purpose (its API changes
  without notice).
- Kotlin → Swift (later): `currentState` beside each `StateFlow`, `observe` for callbacks, no default arguments.
- Commits and docs: the docs change in the same commit as the code they describe.

## Commands

```sh
./gradlew :sharedLogic:testAndroidHostTest   # shared tests on the JVM
./gradlew :androidApp:assembleDebug
./gradlew :sharedLogic:syncServerSchema      # copy the GraphQL schema and ptt.proto from ../push-to-talk-server-go
```

Test results land in `sharedLogic/build/test-results/<task>/*.xml`. CI: `.github/workflows/build.yml` runs both on
every push.

## Gotchas

- Cloud sessions can't download the Android SDK (dl.google.com is blocked) or libsignal (build-artifacts.signal.org),
  so Android builds and the libsignal tests run only in CI there. The common code and its tests can still be checked
  on the JVM with a scratch Gradle project that adds a `jvm()` target over `sharedLogic/src/commonMain` and
  `commonTest` (an actual for `resolveIpv4` is all it needs).
- On a phone, an address typed by hand (no QR link) must be in a certificate the system trusts; enroll by the QR code
  or its link, which pins the server's key, for a home server.
- Android 17 needs `ACCESS_LOCAL_NETWORK` to reach a server on the local network; `MainActivity` asks for it.
