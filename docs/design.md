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
- **The GraphQL schema** (`sharedLogic/src/commonMain/graphql/*.graphqls`) and **the /ws frames**
  (`sharedLogic/src/commonMain/proto/ptt.proto`) are the server's, copied by `./gradlew :sharedLogic:syncServerSchema`
  (`-PpttServerDir=…` when the server isn't in `../push-to-talk-server-go`). Commit what it copies.

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

## Phase 2: keys, the encrypted transport and pairing

The server side is `docs/messaging.md` in push-to-talk-server-go; the plan's
"End-to-end encryption" section is the design. The server only routes and stores ciphertext.

### Keys and sessions

- **`CryptoEngine`** (`crypto/CryptoEngine.kt`) is the one door to the Signal protocol: identity key, prekeys
  (signed EC, Kyber last resort, one-time EC and Kyber), PQXDH session start from a bundle, Double Ratchet
  encrypt/decrypt, safety numbers. Android implements it on **libsignal** (`LibsignalCryptoEngine`,
  `org.signal:libsignal-android` 0.108.5 from Signal's Maven repository, content-filtered to `org.signal` in
  `settings.gradle.kts`; its stores are `SignalStore`). The shared tests use `FakeCryptoEngine`, and
  `LibsignalCryptoEngineTest` (androidHostTest) runs libsignal itself on the JVM (the plan's risk check R2): a session
  from a checked bundle, both ways, replay refused, matching safety numbers, forged and untrusted keys refused.
- **One identity key per user**, made by the user's first phone. A phone that finds another key on the server
  (`me.identityKey`) uploads nothing (`KeysState.OtherIdentity`): linking it from the phone that has the key comes
  later; until then it can start over with a new key (`replaceIdentityKey`) once the other phones are revoked.
- **Upload** (`KeyUploader`): on a device's first connection the identity key, registration id, a signed prekey, the
  last-resort Kyber prekey and 100 one-time prekeys of each kind; a new signed prekey every 7 days (old ones kept 30
  days); 100 more of a kind whenever `prekeyCount` is under `refillBelow`, checked at each start and before each /ws
  connection. `MISSING_KEYS` (the server dropped the device's keys) uploads the full set again.
- **Trust.** A user's identity key is trusted from their QR code (the scanning side), from a pair request that carried
  this phone's secret (the showing side), or, for a contact this phone didn't pair itself, from the server's
  `contacts` until the safety number is compared. A bundle whose identity key isn't the trusted one is refused, and so
  is a message from another key; libsignal checks every bundle's signatures against the key. An
  `IDENTITY_KEY_CHANGED` envelope (or another key in `contacts`) marks the contact "security key changed" until the
  user accepts it.
- **Safety numbers** are libsignal's numeric fingerprints, version 2, 5200 iterations, the user names as the stable
  identifiers: 60 digits in 12 groups of 5, and a QR code `ptt://safety?v=1&n=<scannable, base64url>` to compare.

### The encrypted store

Keys, sessions, contacts, pair requests and messages live in one SQLCipher database, `ptt.db`
(`store/SqlCipherStore.kt`, `net.zetetic:sqlcipher-android`), opened with a random 32-byte raw key that an AES-256-GCM
key in the Android Keystore wraps (`DatabaseKey`). It is a set of named spaces of keys and values (`SecureStore`,
`Spaces`; `MemoryStore` in tests), one table, so no code generator is needed; the plan named SQLDelight, and phase 3's
history can move to tables of its own in the same database. Nothing of it is backed up. The store belongs to one user
on one server: enrolled as another, it is wiped. A database whose key is lost is replaced by an empty one.

### /ws

`RelaySocket` (`relay/`) holds the device's WebSocket, `wss://<server>/ws`, over Ktor on the same OkHttp mTLS client
(which speaks HTTP/1.1 for a WebSocket and pins the server's key). Each binary message is one `ptt.v1.Frame`,
encoded by `relay/Frames.kt` with kotlinx-serialization-protobuf (hand-written classes, one runtime library, no code
generator; `FramesTest` decodes frames the server's Go code encoded and encodes as it does). An envelope is
acknowledged once it is handled; a Send waits for its `SendResult`. A dropped connection opens again after 1, 2, 5,
10, then every 30 seconds (with jitter), and a GraphQL call (`prekeyCount`) runs first so the finder follows the
server to a new address. Close code 4001 (this device connected elsewhere) and 4003 (revoked; the connection is
checked again so home shows Register again) stop it. **Foreground only**: `MessagingController.start` and `stop` run
with the app's onStart and onStop; background delivery is phase 3 (FCM).

`Sender` sends one message as the server's rules want: one envelope for each device of the recipient and each other
device of this user (with `recipient_registration_id`), and on `STALE_DEVICES` it drops the sessions with gone
devices, opens sessions with new ones from their bundles and sends again (3 tries).

### Inside an envelope

A `PREKEY_MESSAGE` or `MESSAGE` envelope's ciphertext opens to one protobuf `Content` (`messaging/Content.kt`, only
the phones read it), padded to a multiple of 160 bytes (a 0x80 byte, then zeros): `pair_request` (1: `secret`,
`user`, `identity_key`), `text` (2: `body`, `sent_at`), and on the copies for the sender's own devices `destination`
(15). Phase 2 sends a text only as a test of the transport; the conversation is phase 3.

### Pairing

QR only, as the plan says:

1. **The Add contact code.** The showing phone asks `pairingToken` and shows
   `ptt://pair?v=1&u=<user>&k=<identity key>&t=<token>&s=<secret>&f=<server>` (`PairCode`): the user name, the
   33-byte identity key, the token, 16 random bytes kept on the phone for a week (`Invite`) and never sent to the
   server, and the first 16 hex digits of the server key's fingerprint (a code from another server is told apart).
   Bytes are base64url without padding. A phone that reads a higher `v` says it needs a newer app. A new code is made
   30 seconds before the old one expires while the screen is open.
2. **Scan and send.** The scanning phone trusts the code's identity key, fetches `prekeyBundles(user, token)`, refuses
   them when their identity key isn't the code's, opens a session with each device and sends a `pair_request` with
   the secret, its user name and its identity key, every envelope carrying the token. Then it calls `confirmPair`
   (`WAITING`).
3. **Check and confirm.** The showing phone keeps the request only when the secret is one of its codes', the user
   in it is the envelope's sender, and the identity key is the one the session came on; then it asks its user. On
   Pair it calls `confirmPair` (`PAIRED`), and both phones get a `PAIRED` envelope.

The screens: [Add contact](screens/add-contact.md) (from the pencil, New chat), and New chat, a contact's page and
the safety number in [screens/contacts.md](screens/contacts.md); pair requests are cards in [Chats](screens/chats.md).
A contact's page has one test line, sent end to end, to check the transport before phase 3's conversations.

A contact paired by QR is marked verified. `contacts` brings the list in line with the server (unpaired by the other
side, blocked, devices, another identity key). Unpair forgets the contact, its sessions and its messages; block and
unblock only change the server's block and the contact's flag.

### Invites

One link that enrolls a friend and pairs them with whoever invited them (the server's docs/authentication.md,
"Invites"), for a friend who isn't in front of you:

1. **Make it.** Add contact → **Invite a friend**: `createInvite(email)` (the email optional; with one, only that
   address can use it) answers a code that works once, for 7 days. `Messaging.newInvite` builds
   `ptt://invite?v=1&code=…&server=…&fingerprint=…&key=…&address=…&u=…&k=…&s=…&e=…` (`InviteLink`): an enrollment
   link's server part, the invite code, and the Add contact code's pairing part without a token, with a new secret
   kept as an `Invite` with `viaLink`. Shared by the share sheet, copied, or shown as a QR code.
2. **A phone that isn't enrolled** opens it (the app's `ptt://invite` intent filter, or Paste in the wizard):
   `EnrollmentController.openInvite` pins the server's key from the link, `invitePreview` says who it is from, the
   friend types their email, `inviteEmailCode` sends six digits there, and `enrollWithInvite` (the digits, their name,
   the certificate request) makes the user and the certificate in one step. Its pairing token goes into
   `PttSettings.pendingInvitePair` as the inviter's `PairCode`, and `Messaging` pairs with it once the keys are up
   and `/ws` is live, as after a scan.
3. **An enrolled phone** opens it: Add contact asks "Pair with <inviter>?" (`PairingController.openInvite`); Pair
   calls `acceptInvite` for a token, then pairs the same way.
4. **The inviter's phone** recognises the pair request by the invite's secret and confirms it without a tap: making
   the invite was the decision. Both sides mark the contact **not verified**, since a link can be forwarded; they
   compare safety numbers to verify.

The email is what makes an invited account unique; the server names the user from it (`dana.cohen`), and the
friend's own name is a display name (`Contact.displayName`, shown as "Dana Cohen · dana.cohen").

## Next

Linking a second phone (`PROVISIONING`) and contact sync between one's own phones; then messages and FCM (phase
3), groups, push-to-talk, calls and iOS, as the plan says.
