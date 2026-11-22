# Design

What is built and what comes next. The whole plan (architecture, decisions, phases) is
https://claude.ai/artifact/CPewKaHcVw4FeLyKthu2Cm; this page only keeps what the code relies on. The server is
`ptt-server` in `../push-to-talk-server-go`.

## Placeholders

- **"Talk" is a placeholder name** (`app_name`, `Theme.Talk`, `TalkTheme`, the launcher icon). The package and
  applicationId, `com.chencohen.ptt`, stay when the app gets its own name.
- **iOS is stubbed.** `sharedLogic` keeps its iOS targets, so common code stays Swift-friendly, but `iosMain` has only
  the actuals common code expects (`resolveIpv4`). There is no `iosApp` until phase 6.
- **Strings are in English and Hebrew** (`values/strings.xml`, `values-iw/strings.xml`), screens right to left in
  Hebrew ([screens/style.md](screens/style.md), "Hebrew and right to left").
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
`user`, `identity_key`), `text` (2: `body`, `sent_at`, `id`), `contact` (3, to one's own phones only), `receipt` (4:
`read`, `ids`), `typing` (5: `typing`), and on the copies for the sender's own devices `destination` (15).

### Pairing

QR only, as the plan says:

1. **The Add contact code.** The showing phone asks `pairingToken` and shows
   `ptt://pair?v=1&u=<user>&k=<identity key>&t=<token>&s=<secret>&f=<server>` (`PairCode`): the user name, the
   33-byte identity key, the token, 16 random bytes kept on the phone for a week (`Invite`) and never sent to the
   server, and the first 16 hex digits of the server key's fingerprint (a code from another server is told apart).
   Bytes are base64url without padding. A phone that reads a higher `v` says it needs a newer app. A new code is made
   30 seconds before the old one expires while the screen is open, and once a pair request used it.
2. **Scan and send.** The scanning phone trusts the code's identity key, fetches `prekeyBundles(user, token)`, refuses
   them when their identity key isn't the code's, opens a session with each device and sends a `pair_request` with
   the secret, its user name and its identity key, every envelope carrying the token. Then it calls `confirmPair`
   (`WAITING`).
3. **Check and confirm.** The showing phone keeps the request only when the secret is one of its codes', the user
   in it is the envelope's sender, and the identity key is the one the session came on; then it asks its user. On
   Pair it calls `confirmPair` (`PAIRED`), and both phones get a `PAIRED` envelope.

The screens: [Add contact](screens/add-contact.md) (from the pencil, New chat), and New chat, a contact's page and
the safety number in [screens/contacts.md](screens/contacts.md); pair requests are cards in [Chats](screens/chats.md).
A contact's page has the safety number, block and remove; the conversation is its own page (phase 3).

A contact paired by QR is marked verified. `contacts` brings the list in line with the server (unpaired by the other
side, blocked, devices, another identity key). Unpair forgets the contact, its sessions and its messages; block and
unblock only change the server's block and the contact's flag.

### Invites

One link and a six-digit code that enroll a friend and pair them with whoever invited them (the server's
docs/authentication.md, "Invites"), for a friend who isn't in front of you. **The phones send the invite; the server
sends no mail** (owner's decision, 2026-11-22: enrollment and invitations go from client to client):

1. **Make it.** Add contact → **Invite a friend**: `createInvite(email)` (the email optional; with one, only that
   address can use it) answers a code for the link and a `passcode`, six digits, both working once, for 7 days.
   `Messaging.newInvite` builds `ptt://invite?v=1&code=…&server=…&fingerprint=…&key=…&address=…&u=…&k=…&s=…&e=…`
   (`InviteLink`): an enrollment link's server part, the invite code, and the Add contact code's pairing part
   without a token, with a new secret kept as an `Invite` with `viaLink`.
2. **Send it.** `InviteMessage` is the text it goes out in: "<user> invited you to Talk", the steps, the link and
   the code ("type this code: 042 917"). With an email typed, **Email the invite** opens the user's own mail app
   (`ACTION_SENDTO` with a `mailto:` URI carrying To, subject and body, so only mail apps answer), from their own
   address. **Share** (always) gives the same text to the share sheet (WhatsApp, SMS…); **Copy invite** copies it.
   The screen also shows a QR code for a friend in the room: the link **with** the code in it (`p`,
   `MadeInvite.faceToFace`), so scanning it is the whole invite; the code is shown beside it too. The text is built
   in `sharedLogic` rather than from Android strings because the friend's phone reads it back (step 3).
3. **A phone that isn't enrolled** opens it (the app's `ptt://invite` intent filter, a scan, or Paste in the
   wizard): `EnrollmentController.openInvite` pins the server's key from the link, `invitePreview` says who it is
   from and the email it is for, and the wizard goes straight to the code screen. The face-to-face QR code fills
   the code in (only the name is left); so does pasting the whole message (`InviteMessage.passcodeIn`: the first six digits standing alone once the links are taken out).
   `enrollWithInvite` (the code, the email, their name, the certificate request) makes the user and the certificate
   in one step. Its pairing token goes into `PttSettings.pendingInvitePair` as the inviter's `PairCode`, and
   `Messaging` pairs with it once the keys are up and `/ws` is live, as after a scan.
4. **An enrolled phone** opens it (the link, or a scan of the QR code in Add contact): Add contact asks "Pair with <inviter>?" (`PairingController.openInvite`); Pair
   calls `acceptInvite` for a token (no code: the friend is a user of the server already), then pairs the same way.
5. **The inviter's phone** recognises the pair request by the invite's secret and confirms it without a tap: making
   the invite was the decision. Both sides mark the contact **not verified**, since a link can be forwarded; they
   compare safety numbers to verify.

**The code is not in the link** (decided 2026-11-22). With it in the link, the link alone would be enough, so it
would have to be sent privately, and anything that sees only the link (a forwarded message, a link scanner, a chat
backup, a photo of the QR code) could use it. Kept apart, the link alone does nothing, and an inviter who wants to can
send the code another way (said aloud, another chat). The cost is typing six digits, or none when the whole message
is pasted. Either way, whoever has both can redeem the invite, so they go to the friend only. The QR code on the
inviter's screen is the exception: it is for showing face to face, where the friend is in the room, so it carries the
code; it is never sent.

**The email.** For an invite with one, the friend's account gets that email (shown, locked); for an invite to anyone
the friend may type theirs, or leave it empty. Nothing checks it: no code is sent there. The server names the user
from the email (`dana.cohen`), or without one from the name (`Dana Cohen` → `dana.cohen`); the friend's own name is a
display name (`Contact.displayName`, shown as "Dana Cohen · dana.cohen").

### Linking a phone

A user's second phone joins the same account with the same identity key (the plan's "Linked, lost and revoked
phones"), from a QR code the first phone shows, so it needs no admin step and no key ever reaches the server:

1. **Show it.** Settings → Linked devices → **Link a phone**: `createLinkCode` gives an ordinary enrollment code
   for this user (10 minutes, once). `Messaging.newLinkCode` builds
   `ptt://link?v=1&code=…&server=…&fingerprint=…&key=…&address=…&u=<user>&d=<device>&s=<secret>` (`LinkCode`): an
   enrollment link's server part, who shows it, and 32 random bytes that never leave the two phones. It keeps a
   `LinkOffer` (the secret and a new X25519 key) in the encrypted store, used once, answered until an hour past the
   code's end. The code is only shown; it has no Share or Copy.
2. **Scan it.** On the new phone, Scan QR code in the wizard (or the camera: the `ptt://link` intent filter) opens
   it as an enrollment link (`EnrollmentController.openLinkCode`), and once enrolled the code goes to
   `PttSettings.pendingLink`. `Messaging.start` turns it into a `LinkJoin` with its own X25519 key, makes **no
   identity of its own** (`KeysState.Linking`), opens `/ws`, and sends the showing phone a `PROVISIONING`
   envelope: a `LinkRequest` with its public key and an HMAC over it, its user and device number, keyed by
   HKDF(secret, "ptt link request v1"). It sends it again on each connection until answered.
3. **Answer it.** The showing phone keeps a request only from its own user, whose MAC matches one of its offers;
   it drops the offer, and sends back a `LinkAnswer`: its public key, a nonce, and `LinkData` (the identity key
   pair, and each paired contact's name, identity key, verified flag and display name) sealed with
   XChaCha20-Poly1305 under HKDF(X25519 shared secret, salt = the secret, "ptt link seal v1"). Link a phone then
   says "Phone N is linked" (`MessagingState.linkedDevice`).
4. **Take it.** The new phone opens the answer with its own key (another one doesn't open), takes the identity
   key pair with a registration id of its own (`CryptoEngine.importIdentity`), trusts each contact's key as the
   other phone did, verified or not, then uploads its prekeys under that identity like any phone.

The server only relays the two envelopes (it lets `PROVISIONING` through between two devices of one user only).
Without the secret it can't open the answer; with the server's help an attacker who saw the QR code could, which is
why the screen says to show it only to one's own phone. A phone enrolled already whose account's key is elsewhere
(`KeysState.OtherIdentity`) can scan a Link a phone code from Chats (**Scan the code**,
`MessagingController.linkWithScanned`): it drops its unused key and asks the same way.

This differs from the plan's wording (the new phone showing a second QR code with its key for the old phone to
scan): one scan instead of two, with the same end-to-end sealing, since the secret in the one QR code authenticates
both directions. Message history isn't moved.

**Contacts between your own phones.** `Content.contact` (`ContactSyncContent`), sent with `Sender` to the user's own
other phones only, says what one phone now knows of a contact: when a pair is made, accepted, verified or not, or a
key change is accepted. The other phones trust that key and take the verified flag (`takeContactSync`); without it
they would only learn of the contact from the server's `contacts`, not verified. A text sent from one phone already
reaches the others as a copy (`Content.destination`).

## Phase 3: messages and FCM

The server's side is its docs/messaging.md, "Push" and "Presence".

**Texts.** `Messaging.sendText` keeps a `TextMessage` first, with its own `id` (12 random bytes, hex) that is the same
on every phone, as `PENDING`; `deliver` sends it with `PushKind.MESSAGE` and marks it `SENT`. Not connected
(`SocketUnavailableException`, or the server unreachable) it stays `PENDING`: **the outbox**, sent oldest first once
`/ws` is live (`flushOutbox`). Refused (blocked, unpaired) or impossible (their key changed) it is `FAILED`, and the
conversation offers to try again (`retry`). A text that comes in is kept unless one with its id is (a second copy),
and answered at once with a delivered receipt.

**Delivered and read.** `ReceiptContent` (`read`, `ids`) goes back to the texts' sender with `PushKind.SYNC`:
delivered when a phone stored them, read when the conversation is on screen (`MessagingController.openChat` →
`markRead`). Statuses only go up (`PENDING` < `SENT` < `DELIVERED` < `READ`). A read receipt's copy reaches the
reader's own other phones (`destination`), which mark those texts read and take their notification down. A read
receipt that can't go yet (a notification's Mark as read while offline) waits for the connection.

**Typing** (`TypingContent`) goes with `PushKind.NONE`, only while connected: started at most every 4 s while the line
has text, stopped when it empties, after 6 s without typing, or by the text itself; the other side shows "typing…"
for at most 8 s (`TYPING_FOR`).

**Presence.** On each connection `refreshPresence` asks `presence(contacts)`, and `Presence` frames on `/ws`
(`RelaySocket`'s `onPresence`) keep `MessagingState.presence` current: the conversation's header says "online" or
"last seen <time>". Settings → Privacy → **Show when I'm online** (`setPresenceVisible`, kept in
`PttSettings.presenceVisible`) hides it both ways.

**Push.** The phone holds no socket in the background. Firebase starts by itself from the resources
`androidApp/build.gradle.kts` makes out of `google-services.json` (the google-services plugin isn't used); its
analytics are off. `Push.register` gives the server the FCM token and whether notifications are on
(`registerPush`, only when one of them changed, kept as `PttSettings.pushRegistered`): when the app comes to the
front, after enrolling, and when FCM makes a new token. `TalkMessagingService.onMessageReceived` gets only
`{"kind": …}` and calls `Messaging.wake`: open `/ws` unless the app is on screen, take the queue, send the outbox and
pending receipts, close again (at most ~20 s). The texts it took show as **notifications**
([screens/notifications.md](screens/notifications.md)) through `MessageNotifier`, which the app sets on the hub; a
conversation on screen reads them instead. The hub (`AndroidPtt.hub`) is one per process, shared by the screens
and the push service.

**Key changes.** A contact whose server key isn't the trusted one shows "safety number changed" in Chats and on top
of the conversation; texts to them fail until the new key is accepted on their page.

Not done yet from the plan's phase 3: the idle battery run, and checking `RemoteMessage.getPriority()` against
what was shown (the server sends HIGH only to phones that registered notifications as on).

## Phase 4: groups

`messaging/Groups.kt`, under `Messaging`; the server's side is its docs/messaging.md, "Groups". Screens:
[groups.md](screens/groups.md).

- **Two lists.** The server's `groups` (owner, members, invited) only routes: group Sends, and prekey bundles and
  pairwise sends between members who never paired. Who reads a group is what its owner's devices say: a
  `GroupStateContent` (id, owner, version, name, a picture of at most 192 pixels a side, the members and the invited,
  each with the identity key the owner trusts for them, deleted), encoded and signed with the owner's identity key
  (`CryptoEngine.sign`), sent as `Content.groupChange` over the pairwise sessions to everyone in it, to whoever just went,
  and (as copies) to the owner's other phones. A phone takes one only when the sender is the group's owner, the
  signature verifies against the key it trusts for them (its own for its user), and the version is newer
  (`max(version + 1, now)`, so the owner's phones stay in order).
- **Readers** (`Group.readers`): the signed members, and the signed invited whom the server lists as members (they
  accepted; the owner's phone then signs them in as members when it sees the server's list). A user only on the
  server's list is never one: editing groups.json adds nobody who can read (GroupsTest checks it).
- **Members this user never paired with** are trusted on the key the owner named, and show as "Added by the owner, not
  verified by you" on the group's page. Their sessions open from the server's prekey bundles only on that key.
- **Sender keys.** Each device has one libsignal sender key per group (`Group.senderKeyId`, a distribution id). It goes
  as `Content.senderKey` over the pairwise sessions to readers' devices (and this user's other phones) that don't have
  it (`Group.keyHolders`), never to anyone else; a phone takes one only from a reader, on the key the owner named. It is
  replaced (a new distribution id, nobody holds it) when a reader goes (left, removed, declined) or the group is gone
  for this user, and when a device that held it is gone from the server's list or re-registered (a revoke).
- **A group Send** is one `SENDER_KEY_MESSAGE` (`Content` with `group` set: a text, or typing with PUSH_NONE) and
  `group_devices`: the server's devices as last listed (`Group.devices`), with the registration id each key holder had
  (0 for devices given no key: the server insists on naming every member device, but those get nothing they can open).
  The first Send, and any after a change, is refused STALE_DEVICES with the members' devices; the new ones get the key
  and it goes again (three tries).
- **GROUP_CHANGED** fetches `groups` again (launched, not awaited in the read loop): invitations appear, groups this user
  is out of turn read only (`Group.gone`), and on the owner's phones the signed members follow the server (accepted,
  left, declined).
- **Group chats** use phase 3's pieces under the chat key `#<id>` (`Group.chatOf`): the outbox, notifications (the
  group's name, each line with its author), typing (`MessagingState.typing` keyed `#<id>/<user>`), unread counts.
  Group texts carry their `author`; there are no delivered or read receipts in groups yet.
- **Not yet:** linking a phone doesn't send the groups' signed state (the new phone lists the groups from the server,
  and takes the state from the owner's next change); MLS if groups grow past 50.

## Phase 5a: talk, the clip path

`talk/` in `sharedLogic`; the server's side is its docs/messaging.md, "Talk" and "Blobs". Screens:
[talk.md](screens/talk.md).

- **A burst** (`Talk.press` / `release`). The microphone opens on the press (`TalkCapture`: 20 ms Opus packets,
  24 kbit/s mono 48 kHz, `AndroidTalkCapture` on MediaCodec's Opus with the echo canceller, noise suppressor and
  automatic gain control; while the button is held, `SpeakerphoneMic` gives the microphone the speakerphone's tuning
  so a voice at arm's length is heard: communication mode with the built-in speaker as the communication device,
  only for the press, then back to the normal mode so playback stays media; not with a headset or during a phone
  call; logcat `TalkCapture` logs the first second's level) and
  `TalkStart` (a new 32-hex burst id, the conversation) goes on `/ws`. What is recorded before the answer is kept
  and sent first, so nothing of the start is lost. **GRANTED**: the burst is kept in the chat (`Clip`, kind BURST),
  its key goes in an encrypted "clip start" message (`Content.clip`, `ClipContent`, with PUSH_TALK), and each 200 ms
  segment (ten packets) goes as a `ClipSegment`. Release sends the last segment (`last`), which ends the floor.
  **BUSY**: nothing is kept or sent; "Dana is talking" shows. **REFUSED** or no microphone: said, nothing sent.
  **Not connected**, no answer within 3 s, or the connection breaking mid-burst: it goes on recording and becomes a
  voice message (below). The server ends a floor after 60 s (TIMEOUT: the phone lets go) or 3 s after the talker's
  connection dropped (DROPPED: what was recorded goes as a voice message).
- **Segments** (`ClipCrypto`): AES-256-GCM under the clip's own random 32-byte key; the nonce is the index
  (big-endian in the last 4 of 12 bytes: one key per clip, so a nonce never repeats); the authenticated data is
  `ptt clip v1|<clip id>|<index>|<0 or 1>`, so a segment moved to another clip or index, or marked last, doesn't open.
  Inside, the packets, each with a 2-byte length. A clip as a file (`ClipRecords`) is the server's records: a 4-byte
  big-endian length and a `ClipSegment` without its burst id. The phone keeps its clips that way (`ClipFiles`,
  `files/clips` outside backups; ciphertext only, the keys are in the encrypted store).
- **Listening.** `STARTED` (live, from the server) shows who talks (`TalkUiState.talkers`); a phone that will play
  answers `TalkAck` (or the server wakes it by FCM after 0.5 s). Segments forwarded on `/ws` and the key from the
  clip-start message meet in an `Incoming`, are opened in order and played (`TalkPlayer`). A gap (a slow phone
  missing forwarded segments), an ENDED without the last segment, or a phone that saw no STARTED reads the clip with
  `GET /blob/<id>` from its start (the server follows a growing clip) and plays from there. One clip plays at a time;
  others queue. Once whole, the clip is kept and its length set.
- **Who plays.** On screen, or with Stay connected: at once. Otherwise only a burst newer than 30 s, and by the
  chat's **When I'm away**: Play aloud asks the platform (`TalkHost.prepareToPlay`) for a talk session, Notify only (or
  a session that can't start) shows a notification with Play and Reply (`TalkHost.talkNotify`).
- **Catch-up** (`CatchUp`, Settings › Talk › Catch up to live): while a live clip plays more than 350 ms behind, the
  pauses are shortened (a pause keeps its first 100 ms), and with the default the speech plays 1.1 times faster
  (1.25 more than 2 s behind) with the pitch kept (AudioTrack's time stretching), until it is back at 250 ms. A pause
  is a 20 ms frame under about -45 dBFS (`Silence`). Off plays at normal speed. Clips from the history play at normal
  speed.
- **Voice messages** (`Talk.startVoice`, the composer's microphone): the same clip format without the floor, kept
  `PENDING` with its file, then `PUT /blob/<id>?peer=` or `?group=` (resumable: a 409 goes on from the server's
  `Blob-Offset`) and its key in a `ClipContent` (kind VOICE, PUSH_MESSAGE). The outbox sends them as it sends texts.
  Voice messages and bursts get delivered receipts like texts, and notifications ("Voice message").
- **Woken by `talk`** (`TalkMessagingService`): the wake-up runs on in the app's scope. Android 17 lets an app play
  from the background only from a foreground service with while-in-use rights; one started by the push doesn't get
  them, and its sound is silenced without an error (logcat `AudioHardening`; 1.0.3 to 1.2.0 were silent). So:
  - **Talk is ready** (`TalkReadyService`, always; 2026-11-22, hen's idea; its setting from 1.2.3 removed in 1.2.5): a media-playback foreground service started each time the app comes to the
    screen, so it has while-in-use rights. It holds no connection, wake lock or timer, with a silent notification on
    its own least-important channel ("Talk is ready", Stop until the next app open; the channel can be turned off in
    Android's settings and the service keeps working). A woken burst takes transient audio focus through it
    (`TalkReady`) and plays as media: the loudspeaker (or the headset in use) at media volume. The focus goes back
    20 s after the last burst. Never started at boot (Android would mute it).
  - **The call session** when Talk is ready isn't running (after a restart until the app is opened, or stopped) or focus is refused: a Telecom talk session (Core-Telecom `CallsManager.addCall`, an incoming audio
    call the app answers itself, "Talk · Family", run by `TalkSessionService` as a phone-call service), in which the
    burst plays as the call's voice (`TalkAudio.inCallSession`: Android mutes media during a call) on the loudspeaker
    (`requestEndpointChange`), quieter than media. It ends 20 s after the last burst.
  - The "Talk playing" notification while it plays, Talk back from it, and the 30 s reply window for hardware talk
    buttons ([notifications.md](screens/notifications.md), "Talk (background)"; `TalkPlaying`, `TalkReply`).
  - **Open Talk when someone talks to me** (off by default, 1.2.5): with the screen off, the playing notification's
    full-screen intent opens that Talk screen over the lock screen, and closes it 3 s after the bursts if untouched
    ([talk.md](screens/talk.md), "Opened when someone talks").
  - **The talk widget** ([widget.md](screens/widget.md), 1.2.5): one conversation per home-screen widget; a tap talks
    through `TalkReadyService` (the tap grants the microphone in the background), the next tap sends.
  - logcat `TalkHost` says which path ran; `TalkPlayer` logs the track's usage, route, Android's playback record and
    the volumes. `Messaging.wake` keeps `/ws` until the path's reply time is over (at most 2 minutes). If neither
    plays, the talk notification shows instead.
- **Stay connected** (`StayConnectedService`, Settings › Talk): a foreground service of types microphone and media
  playback, started from the screen, with an ongoing notification and Turn off; `Messaging.keepConnected` keeps `/ws`
  open while it runs. It resumes when the app is next opened after a restart (nothing starts at boot). Its media
  session takes headset and Bluetooth talk buttons with the screen off.
- **Hardware buttons** (Settings › Talk): with a conversation chosen in Buttons talk in, a volume key held more than
  350 ms talks while the app is on screen (a short press still changes the volume), and a headset's button
  (HEADSETHOOK, PLAY_PAUSE, CALL) presses and releases (`talkButton`).
- **Secrets.** The hub's `random` is the system's `SecureRandom` (clip keys and ids; and the pair, invite and link
  secrets, which used Kotlin's default generator before).

Not done yet from the plan's 5a: measuring press-to-hear on two phones (the plan's 0.7 s), R1 on a sleeping Pixel
and Samsung, the 8-hour idle run, and Stay connected's measured cost shown in the setting; the optional chirp when
the microphone opens.

## Phase 5b: talk, the live path

The server's side is its docs/messaging.md, "Live talk": `talkToken(peer | group)` signs a LiveKit token for the
conversation's room (`u-…` for a pair, `g-<id>` for a group) as identity `user.device`, LiveKit's signalling goes
through ptt-server's `/rtc` (mTLS, like `/ws`), and `serverInfo.liveTalk` says whether LiveKit is there.

- **`talk/LiveTalk.kt`** (common): one room at a time. It joins the room of the conversation on screen (or its Talk
  mode, through `MessagingController.openChat`), or of a burst that just started; it leaves 60 s after the last burst
  once that conversation is off screen. `LIVE_OFF`, a refused token or an unreachable LiveKit leave talk on the clip
  path, which always runs.
- **Talk keys.** Each join makes a fresh random 32-byte key for this device's audio (index 0–15, round LiveKit's key
  ring) and sends it end to end to the conversation (`Content.talkKey`, field 10, no push), and again whenever
  someone joins. Keys that come are kept by `user.device` and set on every join. LiveKit relays only ciphertext.
- **`TalkTransport`** is the carrier, so phase 7's QUIC would only replace it. On Android,
  **`talk/LiveKitTalkTransport.kt`**: LiveKit 2.30 with the same mTLS OkHttp client (pinned key and the server's CA),
  frame encryption per sender (`BaseKeyProvider`, no shared key, frames dropped while a cryptor isn't ready), no
  audio handler (the room takes no audio focus and doesn't change the audio mode while it only listens).
- **The microphone** is published only for a burst and unpublished (stopped) after it, so the microphone indicator
  shows only while talking (R3). While in the room the clip records from that same microphone (the track's sink,
  encoded to Opus by `SampleCapture`), so it isn't opened twice. The talker's phone publishes at the press, without
  waiting for the grant; Busy stops it like the clip. LiveKit's track has WebRTC's echo canceller, noise suppressor
  and gain control, and the press takes the speakerphone tuning (`SpeakerphoneMic`) like the clip path.
- **Hearing.** A listener in the room waits up to 400 ms at STARTED for the talker's live audio to decrypt
  (`hearsLive`); if it does, the burst's clip is kept but not played (and acked). Otherwise the clip plays and that
  talker's live track for this burst is silenced, so the two never play over each other.
- **Debug readout.** In Talk mode a debug build shows "Live: in the room", then "Live: heard 230 ms after the press"
  (STARTED to the first decrypted live audio on this phone).

Not done yet from the plan's 5b: R3 on the real setup (LiveKit on hen-server, UDP 7882 from 4G, the microphone
indicator), the 4G press-to-hear against 0.3 s, and the packet capture; a new talk key when membership changes and
every 7 days (now: one per join).

## Phase 6: calls

The server's side is its docs/messaging.md, "Calls": `callToken(call, peer | group)` makes the call's own LiveKit room
`c-<call id>` (at most 8 devices) and signs a token for it. Everything else is end to end between the phones.

- **Signalling** (`Content.call`, field 11, `CallContent`): INVITE (a new 32-hex-digit id and the call's fresh 32-byte
  media key, voice or video, sent with PUSH_CALL), ACCEPT, DECLINE, BUSY, HANG_UP, RECORDING. Sent as ordinary
  messages to the conversation, so the user's own other phones get copies.
- **`calls/Calls.kt`** (common): one call at a time. The caller sends the invite and waits in the room; nobody in
  it after 45 s is No answer. An invite rings for 45 s; one older than 30 s when it comes (the phone slept) doesn't
  ring and is a missed call; one that comes during a call is answered BUSY. Answering joins the room and sends ACCEPT,
  whose copy makes this user's other phones stop ringing ("Answered on another phone"); DECLINE's copy too. A
  one-to-one call ends when either side hangs up, declines, is busy, or leaves the room; a group call ends for a phone
  when it hangs up or is the last one in the room (the caller's hang-up while ringing stops the ringing). A call
  releases Talk and leaves the talk room first.
- **The call log** is in the chats: each call is a `TextMessage` with `call` (`CallEntry`: video, `CallOutcome`,
  length), keyed by the call's id. A missed one is unread. The Calls tab lists them.
- **`calls/LiveKitCallTransport.kt`** (Android): LiveKit with the same mTLS OkHttp client, the call's key as
  LiveKit's shared key (frames dropped while a cryptor isn't ready), LiveKit's own audio handling (focus, call audio
  mode), the camera and microphone published at join, video in LiveKit's simulcast layers.
- **Telecom** (`CallServices.kt`): every call is a Core-Telecom call (incoming or outgoing), so the system rings and
  routes it and keeps the process; `CallService` is its phone-call foreground service (with the microphone and camera
  types when allowed) and ongoing notification; an incoming call has a full-screen notification; a push that wakes
  the phone for a call keeps the process while it rings. Telecom's own answer and disconnect (a headset, the car) act
  on the call.

- **Save calls** (6b; Settings › Calls: Off, Ask each time, Always; `TalkSettings.saveCalls`): with Always, saving
  starts once someone else is in the call; with Ask each time the call screen asks then. `Calls.setRecording` starts
  the transport's recorder and sends RECORDING, so everyone shows the badge; the file's name goes on the call's entry
  (`CallEntry.savedAs`). **`calls/CallRecorder.kt`** (Android) draws the call picture (the others' cameras in a grid,
  this phone's own in a corner, from the room's own EGL context) into one hardware H.264 encoder at 720×1280, 30 fps,
  and mixes every voice (each track's sink, 48 kHz mono, summed every 20 ms) to AAC, in an MP4 in the app's private
  `files/calls/`; a voice call saves the audio only. It leaves the app only through Share (a FileProvider for that
  folder alone).

Not done yet from the plan's 6: R4 (HEVC; calls and saving use H.264 and LiveKit's default codec now); the 720p cap
set explicitly; the tests on phones (a call ringing a sleeping phone, three on 4G, a saved call played from Share).

## Quick talk (1.1.0)

The app opens on the **Talk** tab ([quick-talk.md](screens/quick-talk.md); 2026-11-22, hen: the main screen is push
to talk): a pager of the contacts and groups marked Quick talk, each with the hold-to-talk circle. The marks are
`TalkSettings.quickTalk` (an ordered list in `PttSettings`, per phone), the last page `quickTalkPage`; `QuickTalk`
keeps the order (a new mark goes last), skips chats that can't talk now, and picks the start page. Pressing on a page
is `Talk.press` in that chat, as in Talk mode.

## App updates (1.2.0)

The release app updates itself from this repo's GitHub releases (2026-11-22, hen), and the **server** tells it when:
ptt-server reads GitHub's latest release every hour (pre-releases skipped) and, on a tag it hadn't seen, sends every
device an `app_update` data push (normal priority, kept 7 days). The phone never asks GitHub for releases and never
looks on a timer. `AppUpdate` (sharedLogic) decides what is offered (`offer`: a newer version, an https APK, a real
SHA-256) and whether a push notifies (`notify`: once per version). `AppUpdates` (androidApp):

- **When it looks:** on an `app_update` push (`TalkMessagingService`), each time the app comes to the screen
  (`MainActivity.onStart`), and on Settings › App updates › **Check now**: the server's `appRelease` query (version,
  APK address, size, SHA-256, notes), compared with the app's own version.
- **Automatic** (Settings › App updates › Install updates automatically, on by default; 2026-11-22, hen: updates with no
  intervention): after a push, or when the app opens and the server has a newer release, `UpdateWorker` (WorkManager:
  network, battery not low) downloads and installs it in the background. It installs only when nothing is going on
  (`AppUpdate.quiet`: no call, no talk playing, queued or held, no live room) and the app isn't on screen, and tries
  again every few minutes otherwise; installing restarts the app. The session asks for no confirmation
  (`USER_ACTION_NOT_REQUIRED`, and update ownership on Android 14+), which Android grants once Talk installed the
  version on the phone itself (installer of record): the first update of a phone installed by hand needs one
  confirmation, later ones none. When Android still wants the user, its screen isn't popped from the background: the
  notification "Talk 1.3.0 is available. Tap to update." opens it. Install unknown apps off: the notification too,
  once per version. Off: a notification once per version, and the update starts on its tap.
- **Offer:** a home card on Talk and Chats with **Update**; Settings says where it stands (up to date and when it
  looked, downloading with a percentage, installing, failed with why).
- **Update** (the card, the notification's tap, or Settings): with Install unknown apps off for Talk, Android's page
  for it opens first (then Update again). The APK is downloaded to the cache, its SHA-256 checked against the
  server's, and it must be this package, a higher version code, and signed with the same key. Then PackageInstaller
  installs it; when Android asks, its confirm screen shows. The app restarts on the new version with its data and
  enrollment.
- **Debug builds** don't update (another key): Settings says "This is a test build", and a push shows nothing.

## Android Auto (1.3.0)

Conversation notifications with Reply and Mark as read for any car, and a Car App (messaging) whose tiles talk from
the car's microphone and end by themselves when the speech does (Silero VAD on LiteRT from Play services, or in plain Kotlin; `VoiceEnd`):
[android-auto.md](screens/android-auto.md). The same `SpeechDetector` and `VoiceEnd` can later end the phone's Talk
back and widget bursts (not turned on there).

## Next

iOS, as the plan says, once phases 5b and 6 are tried on phones.

The Firebase project for phase 3's push is `talk-3e556` (2026-11-22): `androidApp/google-services.json` is its
Android app `com.chencohen.ptt`. It isn't wired in yet (no google-services plugin, no Firebase dependency); phase 3
adds them. That file holds only public identifiers; the server's service-account key never goes in git.
