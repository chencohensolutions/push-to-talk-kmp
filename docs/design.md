# Design

What is built and what comes next. The whole plan (architecture, decisions, phases) is
https://claude.ai/artifact/CPewKaHcVw4FeLyKthu2Cm; this page only keeps what the code relies on. The server is
`ptt-server` in `../push-to-talk-server-go`.

## Placeholders

- **"Talk" is a placeholder name** (`app_name`, `Theme.Talk`, `TalkTheme`, the launcher icon). The package and
  applicationId, `com.chencohen.ptt`, stay when the app gets its own name.
- **iOS is stubbed.** `sharedLogic` keeps its iOS targets, so common code stays Swift-friendly, but `iosMain` has only
  the actuals common code expects (`resolveIpv4`). There is no `iosApp` until phase 6.
- **Strings are English only** for now (`values/strings.xml`), with curly apostrophes.
- **The GraphQL schema** in `sharedLogic/src/commonMain/graphql/schema.graphqls` was written by hand to match
  ptt-server's phase 1. Replace it with the server's own: `./gradlew :sharedLogic:syncServerSchema`
  (`-PpttServerDir=…` when the server isn't in `../push-to-talk-server-go`).

## Phase 1: enroll and find the server

Copied from journal-client-kmp and adapted: the build setup and versions (minSdk 35, targetSdk 37), enrollment, mTLS,
renewal, finding the server and the Journal look.

- **Enrollment** ([screens/enrollment.md](screens/enrollment.md)). An 8-character code made on the server with
  `ptt-server enroll-code` and shared as a QR link `ptt://enroll?server=…&code=…&fingerprint=…&key=…&address=…`
  (`parseEnrollmentLink`). The phone creates an EC P-256 key in StrongBox (or the TEE, or software on an emulator)
  and sends a PKCS#10 request with the code (`enrollDevice`). It gets back a certificate signed by the server's own
  CA, its device number, and the CA itself. Nothing is built into the app: a link pins the server's key
  (`fingerprint`, the sha256 of its SubjectPublicKeyInfo), and the CA is kept after enrolling.
- **The connection.** One mTLS client per identity and address (`ServerHttp`), so the phone keeps one HTTP/2
  connection. `ConnectionController` checks it with `me` when the app comes to the front. While the server is
  offline it checks again after 5, 15, 30 and then every 60 seconds, and stops when the app goes to the back. After
  each check that works it asks `serverInfo` (`discovery { fingerprint id key addresses }`, `caCertificate`,
  `version`, `deviceNumber`) and keeps it.
- **Renewal.** In a certificate's last 30 days the app makes a new key and renews with `renewCertificate`
  (`CertificateRenewal`).
- **Home** ([screens/chats.md](screens/chats.md)) and **Settings** ([screens/settings.md](screens/settings.md)).

## Finding the server

A home server's address changes: a new IP from the provider, the phone leaving the home network. The phone looks
for the server again by itself (`ServerFinder`, behind `ServerHttp` on network errors) in this order:

1. **The last address that worked** (`serverUrl`). The request that failed went there.
2. **The addresses the phone keeps** (`KeptDiscovery`): the one it enrolled at, the link's `address=`s, and the ones
   `serverInfo.discovery.addresses` listed, newest first. Each starts 400 ms after the one before, and the first to
   answer wins.
3. **The DHT.** The server publishes a BEP 44 mutable item under its DHT key (`key`) with salt `ptt-server/v1`. The
   value is a record sealed with XChaCha20-Poly1305: its key is HKDF-SHA256 over the fingerprint with info
   `ptt dht seal v1`, and the salt is the AAD, so only who knows the server's fingerprint can read its addresses
   (`DhtRecord`, `Seal`). The DHT is asked at most every 2 minutes, and the addresses in the record are tried as in 2.

The address that answers becomes `serverUrl` and goes first in the kept list. The finder runs only once a fingerprint
is kept: then the mTLS client pins the server's key, so any address that answers is the server.
`DhtRecordTest.opensTheRecordSealedByPttServersOwnTest` opens a record sealed by the server's own test, so the two
repos can't drift apart.

## Next

Phase 2 is pairing (adding a contact by QR code, which is why the pencil is disabled) and libsignal sessions; then
messages, push-to-talk, calls and iOS, as the plan says.
