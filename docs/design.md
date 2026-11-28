# Design

What is built and what comes next. The whole plan (architecture, decisions, phases) is
https://claude.ai/artifact/CPewKaHcVw4FeLyKthu2Cm; this page only keeps what the code relies on. The server is
`ptt-server` in `../push-to-talk-server-go`.

## Placeholders

- **"Talk" is a placeholder name** (`app_name`, `Theme.Talk`, `TalkTheme`, the launcher icon). The package and
  applicationId, `com.chencohen.ptt`, stay when the app gets its own name.
- **The launcher icon is "One roof"** (hen chose it 2026-11-27): two speech bubbles, white with a voice and mint,
  under one teal roof, on Talk's coral. Its layers are PNGs per density (`drawable-*/ic_launcher_foreground`,
  `_background`, `_monochrome` for themed icons), rendered from `docs/screens/mocks/app-icon-one-roof.html` (the soft
  shadows need a raster; a vector drawable can't blur). Re-render from that file to change it.
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
`read`, `ids`), `typing` (5: `typing`), and on the copies for the sender's own devices `destination` (15). Later
fields are added the same way and older apps skip them; among them `about` (27: the app version) and `colors` (28:
`ColorsContent` `mine`, `theirs`, `at`; the chat colours the sender chose for a one-to-one chat, in the sender's
words, sent with `PushKind.NONE` when they change; see docs/screens/conversation.md, Chat colours).

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
pending receipts, close again (at most ~20 s). A wake-up that ends while another still holds the connection (a
burst playing, its reply time, a warm room) leaves it open: the last one closes it (2026-11-27: the server also
pushes a connected phone that is slow to say it plays a burst, so a run of pushes left a line of wake-ups, and each
closed `/ws` under the one before, 15 times in 25 seconds in the server's log). The texts it took show as **notifications**
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
  `TalkStart` (a new 32-hex burst id, the conversation) goes on `/ws`, once my last burst has let go of its floor
  (up to 3 s: a renewal or a handover would otherwise find my own floor still held). What is recorded before the answer is kept
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
    (`TalkReady`, through `AndroidTalkFocus`, shared with the room's dimming; none with Lower other audio while
    someone talks off) and plays as media: the loudspeaker (or the headset in use) at media volume. The focus goes back
    20 s after the last burst. Never started at boot (Android would mute it).
    **Without media rights** (2026-11-25, hen: "i don't hear some times"): Talk is ready started by a push (after a
    restart or an install, the app not opened since) has no while-in-use rights, so Android mutes its media ("AudioHardening
    background playback muted") and refuses its focus. `TalkReadyService.withRights` says whether it was started (or
    started again, from `MainActivity.onStart`) with the app on screen; without them a burst goes straight to the call
    session, and the live room is joined with its audio as call audio (`TalkAudio.playAsCall`, LiveKit's
    `CallAudioType`), since a call session mutes media too. When its chat comes on screen, a room joined that way is
    joined again as media (`TalkTransport.stale`).
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
- **Other apps dimmed** (2026-11-25, hen: "Sharon now listen to audio on other app and barely hear me"; Settings ›
  Talk › Lower other audio while someone talks, on by default): while someone else's push is heard in the room (chat
  open, the floating window, or in the background), `TalkDuck` (sharedLogic) holds transient audio focus, "others may
  duck" (`AndroidTalkFocus`: usage media, content speech), so music and videos in other apps go quieter. The first
  heard burst takes it; it goes back 2 s (`RELEASE_AFTER_MS`) after the last heard burst ends (its floor's end, all its
  segments in, or the stale-floor limit), so a push right after keeps the music down. Refused: talk plays anyway.
  Own pushes never take it. `AndroidTalkFocus` is the one focus holder for the room and Talk is ready (`TalkReady`),
  so they don't fight: asked when the first takes it, given back when the last lets go. Off: neither takes focus,
  other apps play on at full volume. Some music apps pause rather than dim (their own choice).
- **The microphone** is published only for a burst and unpublished (stopped) after it, so the microphone indicator
  shows only while talking (R3). While in the room the clip records from that same microphone (the track's sink,
  encoded to Opus by `SampleCapture`), so it isn't opened twice. The talker's phone publishes at the press, without
  waiting for the grant; Busy stops it like the clip. LiveKit's track has WebRTC's echo canceller, noise suppressor
  and gain control, and the press takes the speakerphone tuning (`SpeakerphoneMic`) like the clip path. The unmute
  comes first and the tuning after it, off the messaging thread (2026-11-27, hen: "sometimes the partner doesn't hear
  my first words"): Android takes a few hundred ms over the audio mode and route, and that used to pass before the
  unmute, on the thread that also asks for the floor. The log says how long each took ("microphone live in the
  room, N ms after the press reached it", "speakerphone tuning took N ms"). The track is disabled before it is
  published, so nothing of the microphone leaves the phone between the publish and its mute.
- **Hearing.** A listener in the room with the talker waits up to 400 ms at STARTED for the talker's live audio to
  decrypt (`hearsLive`); if it does, the burst's clip is kept but not played (and acked). If the clip's segments have
  come by then, the clip plays, from its first segment, and that talker's live track for this burst is silenced, so
  the two never play over each other. If neither has come, the phone goes on waiting for whichever comes first (it
  acks at 400 ms, so the server doesn't wake it by FCM): the live track used to be silenced at 400 ms whatever
  happened, and a room-only push whose microphone opened late was then not heard at all. A talker who isn't in the
  room is not waited for. While a burst's way of playing is being decided (this wait, or readying a talk session
  off screen, which takes seconds), its segments wait (`Incoming.deciding`); they used to be taken unplayed, and the
  clip then began after them.
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

- **Kept calls** (2026-11-22, hen: the server keeps a call only while someone records it; [calls.md](screens/calls.md),
  "Playing a kept call"): Save also asks the server to keep the call (`recordCall`; Stop is `stopRecordingCall`).
  `Calls` reads the LiveKit room's metadata to know while the server keeps the call (no badge is shown), and keeps
  the call's key on the entry (`CallEntry.keptKey`) once the server kept it. **`calls/LiveKitFrames.kt`** undoes LiveKit's frame encryption
  (PBKDF2-SHA256 of the key's base64 with salt "LKFrameEncryptionKey", 100 000 rounds, AES-128-GCM; the clear header
  as authenticated data; H.264 unescaped first), **`calls/KeptCallFile.kt`** downloads and decrypts each track blob
  and **`calls/Matroska.kt`** writes one file without re-encoding. The player is one ExoPlayer per person over that
  file. `tools/keptcall` makes the test fixture with Go's own crypto, so the decryption is checked against another
  implementation.
- **Download saved calls** (2026-11-23, hen; Settings › Calls › Save calls › Download saved calls): when a call this
  phone saved ends, `TalkApp` schedules **`CallDownloads`**, a WorkManager job (the Wi-Fi and/or charging terms from
  `TalkSettings.autoDownload*`) that reuses `loadKeptCall`/`saveKeptCall` to download and decrypt the kept call into
  the chosen folder once the server has it, retrying until then. A saved call's id is recorded in
  `TalkSettings.downloadedCalls` (`Messaging.markCallDownloaded`, by both the worker and a manual Save), and its
  **Save as file** button is then hidden on the Calls-tab row and in the player (2026-11-24, hen).

- **H.265 video** (2026-11-22, hen; Settings › Calls › Video): `LiveKitCallTransport` publishes the camera in H.265
  with VP8 as LiveKit's backup codec when chosen and the phone has a hardware HEVC encoder (`hasHevcEncoder`). The
  frame cryptor keeps the first slice segment's 2-byte NAL header clear and escapes the rest as for H.264
  (`LiveKitFrames`); a kept call's H.265 track goes into the file as V_MPEGH/ISO/HEVC with its hvcC.

Not done yet from the plan's 6: R4 (HEVC; calls and saving use H.264 and LiveKit's default codec now); the 720p cap
set explicitly; the tests on phones (a call ringing a sleeping phone, three on 4G, a saved call played from Share).

## Quick talk (1.1.0)

The app opens on the **Talk** tab ([quick-talk.md](screens/quick-talk.md); 2026-11-22, hen: the main screen is push
to talk): a pager of the contacts and groups marked Quick talk, each with the hold-to-talk circle. The marks are
`TalkSettings.quickTalk` (an ordered list in `PttSettings`, per phone), the last page `quickTalkPage`; `QuickTalk`
keeps the order (a new mark goes last), skips chats that can't talk now, and picks the start page. Pressing on a page
is `Talk.press` in that chat, as in Talk mode.

## Push-to-video (1.4.4)

2026-11-22 (hen): a press can send the camera with the voice (a Talk screen's Video, off by default). It goes in the
same burst, through the same encrypted clip path (live, woken, from the history), so the server doesn't change: the
"clip start" says `video`, and every packet is tagged (`TalkVideo`): the Opus packets as before, and the camera's
frames in pieces of at most 3 KB, a segment cut early so it stays under the server's 16 KB. The camera is the front
one into a hardware encoder (`AndroidTalkVideoCapture`), 640×480, 15 fps, 350 kbit/s (a minute fits the 4 MB clip),
frames as captured with their rotation. One format per burst: H.265 only when Settings › Calls › Video is H.265,
this phone has the encoder and every other phone in the chat said it decodes H.265 (`ContentCodec.caps`, on every
message, end to end); otherwise VP8, or H.264 (which every phone decodes) where VP8 can't be set up (the S25 has
only Android's software VP8, which refused the encoder's settings). The listener splits the packets, plays the voice (without catch-up, so the
pictures keep up) and draws each frame when the voice gets there (`AndroidTalkVideoOut`), on the Talk screen or in
the reply bubble (its own Video switch, off by default).

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

## Pictures (2026-11-24, hen)

Each user can have a picture: an illustration of their face, made on their own phone, cut out on a clear background and
drawn free-standing wherever the letter circle was (chats, the chat list, the reader circle, the floating window,
calls). People without one keep the letter circle.

- **Made on the phone only.** A selfie or a photo from the gallery is the input and is never kept or sent (the camera
  writes it to `cache/selfie`, which is read once and deleted). ML Kit finds the face; the head and shoulders (about 2.4
  face widths) are cropped to 384 px; MediaPipe's selfie segmenter (Apache-2.0, bundled, 250 KB) gives the outline,
  firmed up, softened and faded out at the bottom; a style model draws it; the result is a 256 px WebP with alpha,
  about 15 KB (`FaceMaker`).
- **Styles** (the person picks; each previewed on light and dark): Soft, Painted and Hand-drawn are DCT-Net
  (menyifang/DCT-Net, Apache-2.0: 3d, sd-design, handdrawn), Ink is Informative Drawings (carolineec, MIT: contour).
  Converted to LiteRT fp16 at 384 px by `tools/face/` (3.25 MB each, Ink 8.6 MB; 18.4 MB for all four). Anime was
  tried and dropped: it changed the eyes too much. They run on LiteRT from Play services, 4 threads.
- **Downloaded on first use** from the app repository's release `face-models-1`, one at a time, the first as the
  Your picture step opens, each tile with its own progress ring. Without a network the step still opens and Skip
  works. The fast build carries them in its assets for testing.
- **Head only** (2026-11-24, hen): the crop is about 1.9 face sizes around the face; below the jaw line (ML Kit's face
  contour) the outline fades out over a few pixels, and only what joins the head is kept.
- **Expressions** (2026-11-24, hen: "used as emoticons"): smiling, laughing, sad, surprised, winking, angry, made on
  the phone from the same drawing (`Expressions`): ML Kit's lip, eye and brow contours; the drawing moved around them
  with soft gaussian displacements (mouth corners, lids, brows); an open mouth painted between the lips' inner edges
  for laughing and surprise (teeth for laughing), a closed lid painted for the wink. Nothing learned, no extra model,
  so no licence question. Each is a 256 px WebP with alpha like the picture.
- **Faces as messages and reactions.** `TextContent.face` (the Expression code + 1) sends one of the sender's faces;
  its `body` is the matching emoji, which apps without faces show. `ReactionContent` (field 22) reacts to a message
  with one of the reacter's faces (0 takes it back). Each phone draws the face from the sender's own set, so only its
  owner can send it. The set travels with the picture (`ProfileContent.faces`, about 12 KB each, only where the
  picture's bytes go); `faceCount` lets a phone with fewer ask again (`needPicture`).
- **End to end.** `ProfileContent` carries `pictureId` ("-": none) and, only to a chat that doesn't have that id yet,
  the picture's bytes (at most 96 KB taken). Contacts get it with the profile (email, gender); each group gets it as a
  group message. Which id each chat was sent is kept (`picture_sent`), so the profile said again at each start sends no
  bytes. A phone told of an id it doesn't have (it had an app without pictures) asks with `needPicture`, and the bytes go
  again. The server sees only sealed envelopes.

## Push-to-video through LiveKit (2026-11-24, hen: "do it")

A video burst also goes through the conversation's talk room (the same room, token and per-sender E2EE keys as live
voice), so the picture arrives as the voice does instead of in clip segments over `/ws`:

- **Sending.** When a video burst starts and this phone is in the chat's room, the push-to-video camera's frames go to
  the room as well: `TalkCameraCapturer` gives LiveKit a surface (`SurfaceTextureHelper`) that
  `AndroidTalkVideoCapture.setLiveTarget` adds to the camera's session in Follow face's small copy's place (the camera
  keeps three outputs: the clip's encoder, the preview, the room). Published as `talk-camera`, 640×480 at 30 fps in
  simulcast layers with `MAINTAIN_FRAMERATE`; the server's congestion control picks the layer per listener and the
  room has dynacast on, so a weak link gets a lower layer instead of a growing lag (adaptive stream stays off: it
  pauses a track no visible view shows, and the frames are watched before one does). It is unpublished when the burst ends (each hands-free part is its own burst).
  The camera that can't take the extra output (the session fails) sends by clips only.
- **Receiving.** The room subscribes to it like any track; `LiveTalkVideo` watches each talker's video for frames and
  calls it "arriving" from the first frame until a second passes without one. The chat's top box and the floating
  window show the room's video (`RoomVideoView`, a `TextureViewRenderer`) while it arrives, and the clip's pictures
  otherwise, never both; the box takes the shape of whichever shows. The listener's camera and microphone stay off.
- **The clips stay.** Every burst still goes as clip segments over `/ws`: they wake a sleeping phone (full screen),
  make the "Keep videos in chat" copy, and show when the room can't be reached or its key isn't there yet.
- **Delay readout.** The debug and fast builds show, on the received video, which path it came by and how long after
  the server started the floor its first picture came ("LiveKit · first +380 ms", or "clips · first +900 ms · behind
  120 ms"); every build logs the same per burst under the `TalkDelay` tag.
- **Server.** Nothing changed: the talk token already lets a device publish any track.
- **Make it a call** (2026-11-25, hen: "instant"): while both are live in the chat, the talk room itself becomes the
  call (`Messaging.startRoomCall`, `Content.roomCall` field 25): floor bursts stop, both microphones (and cameras, if
  live) stay unmuted in the room, no reconnect, no new room; the chat shows the call in place (`RoomCallTop`,
  `RoomCallBottom`: time, End, microphone, camera), and the call log gets a line when it ends. Not yet: a Telecom
  call session for it, Save calls (the server's `recordCall` knows call rooms only), and the floating window's chip
  (it still starts the usual call). Not in the room: the usual video call starts, as before.

## Voice back in the talk room, and the silent warm-up (2026-11-24/25, hen)

**Why voice had left the room (1.4.0, commit 2fa066b).** A press published a new microphone track, which took a
moment to start (first words lost), and the release unpublished it at once (the end still on its way lost), while the
listener played the room's audio instead of the clip.

**Now.** The microphone's track is published once, muted, while the chat is on screen (`prepareAudio`), unmuted at the
press and muted 300 ms after the release (`TAIL_MS`). The clip records the same samples (`SampleCapture`), starting
with the 300 ms before the press from a ring it keeps while muted, and stays the record (sleeping phones, "Keep voice
in chat"). The listener counts a talker as heard through the room only while their microphone publication is unmuted
and decrypting (`hears`); otherwise it plays the clip. Off screen (a warm room in the background) no microphone track
is published. While muted, no voice leaves the phone, but the microphone itself is open while the chat is on screen
(Android's microphone dot), as LiveKit's audio device keeps recording for the published track.

**Silent warm-up.** Opening a chat sends `Content.warm` (field 24) to its other phones, pushed as `PUSH_WARM` (the
server's ux/warm-push branch; older servers deliver it as `sync`), again every 5 minutes while it stays open, and once
more when it is left. The phone it reaches joins the chat's talk room quietly, keeps `/ws` open, and Talk's quiet
service (`TalkReadyService`, the least important, silent notification) keeps the process: no sound, no heads-up,
nothing lights the screen. It stays as long as the sender's chat is on screen (the app in front, screen on: it
refreshes every 5 minutes), and ends when the sender leaves the chat or goes to the back, or 6 minutes pass without a
refresh (2026-11-25, hen chose this over a 10-minute cut-off without a burst, after a push to a long-open chat came
cold). A phone that joins this way only listens (2026-11-25, hen: "don't open microphone if the user entered the room
in the background"): no microphone or camera track, and LiveKit's audio prewarming is off, so no microphone opens and
no privacy dot shows. The microphone is published, muted, only once that phone's own chat is on screen; a press
from outside the chat (the floating window) publishes it at the press. Android may demote high-priority pushes
that end in no notification: the app logs each push's priority as sent and as delivered. The `TalkDelay` log says
whether the phone was in the room before a push.

## Live in the chat (2026-11-24, hen)

- Presence is per chat only: the viewing signal (`ViewingContent`, every 20 s while the chat is open and in view, from
  those who show their presence) rings the reader's face at the chat's end; the header no longer shows online or last
  seen.
- `TalkSettings.keepVoice`, `keepVideo`, `bothLiveCall` (sets of chats, this phone's own, off by default; stored as
  `talk_keep_voice`, `talk_keep_video`, `talk_both_live_call`). `keeps(chat, clip)` decides whether a burst shows as a
  message or as a muted line; the clip itself is stored as before, so turning the switch on shows past ones again.
- Hands-free is the app's: the Talk button's lock keeps pressing, letting go at 55 s and pressing again as soon as the
  last burst is let go (the server's floor limit is 60 s). `Content.liveWaiting` (field 23, a `ViewingContent`, live
  only) tells the chat this user is hands-free and waiting for the floor; with it both sides know they are both live.
- "Make it a call" starts an ordinary video call (phase 6) and marks it shown inside the chat; the other phone, both
  live in that chat, accepts a call from it at once and shows it inside the chat too. Nothing changes on the server.

## Sharing media (2026-11-28, hen; being built)

Pictures, videos and files shared into a chat from Android's Share menu (or the composer's + button), one or many at
a time. The build plan is https://claude.ai/artifact/M4ptCwXCPZgeMMtK1v1zY9 and the design is
https://claude.ai/artifact/K8LQASQNarMxVAYKQdMQoQ. The server side is done (ptt-server PR #24: open, growing blobs,
`?keep=media` kept `media_days`, `PUSH_MEDIA` with `push_user`, the disk guard). Built so far in the app (A1):

- **What goes in the message.** `Content.media` (`MediaContent`: id, time sent, caption, items) with one
  `AttachmentContent` per item: its own random 32-byte key, the original's SHA-256, size, type, name, picture size,
  length, when and where it was taken (from EXIF; each phone names the place itself), its previews and a video's
  poster (`PartContent`: blob, size, codec, HDR, `growing`), which of the sender's devices holds the original, and a
  ThumbHash. `mediaRequest` asks that device for a part, and `mediaReply` answers (ready, waiting for Wi-Fi, gone,
  changed). `AboutContent` now carries the screen size, so the sender makes each preview as wide as that screen.
- **Caps.** `CAPS_MEDIA` (it takes shared media) and `CAPS_HEVC10` (HDR video previews keep their HDR for it).
- **Encryption** (`media/MediaCrypto.kt`). Each part has its own key, HKDF-SHA256 of the item's key (salt: the blob
  id; info: `talk media v1|preview`, `|poster` or `|original`). Parts are AES-256-GCM in 64 KiB chunks. The nonce is
  the chunk index, and the authenticated data binds the blob, the index and the last flag, so the server can't change,
  move, reorder or cut a part. Chunk i starts at i × (64 KiB + 16), so a video seek reads only the chunks it needs.
  The key is only on the sender's and recipients' phones, never the server's or Journal's.
- **Frames.** `Send.push_user` and `PushKind.MEDIA` (7), synced from the server's ptt.proto.

- **Sending (A2).** `MediaSharing.prepare` reads each item through the platform's `MediaPreparer`
  (`AndroidMediaPreparer`: name, size, type, SHA-256, EXIF time and place with `setRequireOriginal`, a video's own
  metadata; for pictures one JPEG XL per width, upright, quality 95, effort 7: hen chose Journal's "max" preview
  quality, and on hen's photos a 1080 × 1440 preview is about 450 to 500 KB). The widths are `PreviewSpec.widths`:
  each receiving screen's (from `AboutContent`, kept in `contact_screens`; 1080 when a phone didn't say) and this
  phone's, none wider than the picture, at most three. Each item gets its own key; its previews are sealed and kept in
  `MediaFiles` (the sender's in app storage, `media-sent`; what came in the cache, `media`). The message is a text
  (caption, or 📷 / 🎬 / 📎 for older apps) with `media`; `deliver` uploads the previews first, so the outbox retries
  a broken upload with the message. The original isn't copied: its URI is kept (`media_sources`).
- **Receiving.** The three text paths (one to one, a copy from another of this user's phones, a group) keep
  `MediaSharing.accept`'s checked media on the message. `MediaSharing.preview` picks the preview for this screen
  (`PreviewSpec.pick`), opens it from `MediaFiles`, else downloads it once (several asks share one download), checks
  its size and every chunk, and keeps it; a cleared cache is fetched again the same way.

- **Originals on request (A3).** Opening an item (`MediaSharing.openOriginal`) shows it at once on the phone that
  shared it (its URI) or when it came before; otherwise it sends `mediaRequest` in the chat with `PushKind.MEDIA` and
  `push_user` = the sharer, so only that user's phones wake. The device that keeps the item's source answers
  (`MediaSharing.took`): it reads the file again through `MediaPreparer.open` (an `OriginalReader` that works out the
  SHA-256 as it reads), seals it in 64 KiB chunks and PUTs it in about 1 MB pieces with `?keep=media` (`BlobApi.putPiece`),
  telling the chat `mediaReply` READY once its first piece is on the server. Its last chunk goes only after the whole
  file matches the SHA-256 that was shared: a changed file is deleted from the server and answered CHANGED, a file that
  is gone GONE. An upload done in the last 29 days is answered with the same blob, and a wake-up keeps the connection
  while an upload runs (`MEDIA_WAKE_LIMIT`, 30 minutes). The asker reads the blob with `?follow=1` from its last whole
  chunk (`BlobApi.readFrom`), opens each chunk and appends it to `media-originals` in the app's cache (opened, so the
  viewer and the player read it; only Talk can read that folder), and `originals` says how far it got. Waiting for Wi-Fi
  is not built yet; hen chose it off by default (2026-11-28).
- **The viewer** (`ui/media/MediaViewer.kt`, docs/screens/viewer.md): growing out of its place in the chat and back into it, an Info sheet (EXIF, what this phone keeps, who has what), the preview at once, the original swapped in when
  it is here, pinch and double tap zoom, swipe between a message's items, a video's original played with ExoPlayer, a
  file opened in an app that reads it (the FileProvider serves `media-originals`). A pill at the bottom says "Getting the
  original · 3 of 8 MB" with ✕, or why it didn't come, with Try again.

- **Drafts and half bubbles** (2026-11-28, hen). `Messaging.draftMedia` prepares what was picked without keeping it
  (`MediaSharing.prepare(keep = false)`; its previews show from `MediaSharing.drafts`), the writing bubble shows it
  (`MediaDraftBox`), and `sendDraft` keeps it with the caption, less the items taken out, and delivers it;
  the previews start uploading on `prepare` (`draftUploads`; `upload` on Send waits for that job and skips what
  went up), and `MediaSharing.drop` (or an item taken out) deletes the previews here and, with `BlobApi.delete`, on
  the server. `Messaging.sendPicturesFirst` sends them `quiet` (PushKind.SYNC, no notification) and
  `commitCaption` sends the caption as an `EditContent` with `notify`, which `takeCaption` keeps and notifies. `MediaSharing.uploads` says how far each message's previews
  uploaded, which my half bubbles' dashes show. Pictures in the chat sit between half bubbles
  (docs/screens/conversation.md).

Still to build: each person's download shown as a ring on their face (mock `sending-progress.png`; needs each phone
to tell how far it got), the top half bubble's place name (hen to pick what it says), files' copies, albums, the viewer's Share and Save. Journal as a source for originals that
are gone from the phone is the next phase (shed).

## History from contacts (2026-11-28, hen)

"Make it resilient by requesting history from the peer": a new install (a contact that comes from the server's
`contacts` with no messages here) keeps them in `Meta.historyWanted` and asks each for our chat's history
(`Content.historyRequest`, field 32), again on each start until answered. Their phone opens it only once its user
accepted this install's new key, then sends the chat back end to end in parts under the 256 KiB envelope
(`Content.history`, field 33, `historyParts`), oldest first, an empty last part when there is none; clips and calls
stay out. It arrives quietly, read, no notification. Pictures keep their shape, when and where, so the chat lays them
out at once; only the last 30 days' previews are fetched as the history comes, older ones as they scroll in. One-to-one
chats only for now; groups and this user's other phones are next, and a backup on the Journal server later (hen).
(`MessagingTest.aNewInstallGetsTheHistoryFromItsContact`.)

## Who holds what (2026-11-28, hen)

"Monitor who have what in a smart way, so the app could clear its cache but make sure all the original images exist
somewhere; if my image is in the journal server it counts as existed." Each phone tells the chat what it holds of each
shared item when that changes (`Content.holdings`, field 34, `HoldingsContent`; no push, batched 3 s, only what
changed since it last said): the original (`HeldOriginal`: the shared file itself, a copy opened here, or a copy kept
as the only one), the widest preview it keeps, and (the last phase) whether its Journal server has the original. The
others keep it per item and phone (`MediaSharing.holdersOf`, `#holders` beside the item's source); the sharer's
answer to a request for the original counts too (ready: its file is there; gone or changed: it isn't), so older apps
are followed. The viewer's Info asks the chat again (`ask`) and shows what each phone said and when.

The sharing phone checks its shared files once a day as it comes to the front (`checkSources`): one deleted or changed
is told. A copy of an original opened on this phone that is then the only one known (the sharer's file gone, no one
else has one, nor a Journal) is moved out of the cache (`MediaFiles.keepOriginal`, `media-originals-kept` in the app's
no-backup files), so neither Android nor Clear deletes it; once another holder is known it goes back to the cache.

Talk keeps no copy of what it shares, so it needs access to all photos and videos (2026-11-28, hen chose it over
copies): with only selected photos, a picked picture's pass ends when Talk restarts. Sharing asks for it (again at most
once a day while it isn't given) and the viewer offers Allow all photos; a shared file found unreadable counts as
gone until it can be read again (`sourceBack`).

A chat's settings show its Storage (`ChatStorage`: previews sent and got, originals opened here, only copies kept,
how many of mine are read from where I shared them, and how many originals are known to be nowhere), and Clear cache
removes only what can come again (`clearChat`): previews got that are still on the server (shared within 29 days) and
copies of originals someone else has. My own shared previews (the others may ask for them) and the only copies stay.

The whole cache has a limit (2026-11-28, hen: Settings › Storage, 10 GB by default, 1 GB at least; `trimCache`): over
it, what was read longest ago goes first (`MediaFiles.cached`, each part's last read: a preview's file time is set when
it is read, an original's when it is opened). Only what can come again goes, as with Clear cache, and nothing read in
the last 10 minutes (on screen or playing). It runs a few seconds after the cache grows and each time the app comes to
the front. With Clear when storage is low (on by default), under 2 GB free on the phone all of it that can go goes.

What I share is kept on the server forever (2026-11-28, hen: "don't use the 1 year, keep forever everything"; the
server's `media_total_gb`, 1 TB, lets what was fetched longest ago go): previews and originals go up with
`&keep=media` (`BlobApi.putMediaPiece`) and the message says so (`MediaContent.keepDays`, `FOREVER`; 0 from older
apps means 30 days), so a phone that got it lets its previews go from the cache, to be fetched again. On Wi-Fi (a network that isn't metered) an original goes up right after its message is on the server
(`uploadAhead`; off Wi-Fi, `uploadPending` sends it on the next start on Wi-Fi), and the chat is told with a READY
reply as if someone had asked: it then opens for anyone straight from the server, even with my phone off or the file
gone from it.

**Video clips** (2026-11-28, hen: 1080p HEVC at 3 Mbit/s flat, the video's own frame rate and HDR, autoplay muted, no
loop). It is made after the draft shows, while the caption is written (hen: "the encoding should happen while writing
a message with progress indication"): `MediaSharing.makeClips` runs in the draft's upload job, one video at a time,
says how far each got (`encoding`, by item id; a ring round the video's ▶), and puts the clip on the draft, or on the
kept message when Send came first (the outbox's `upload` waits for it, then sends the message with it).
`AndroidMediaPreparer.clip` makes it with Media3 Transformer (`Presentation.createForShortSide(1080)` when the
video is larger, `DefaultEncoderFactory` at 3 Mbit/s, HDR kept as Transformer does by default, the sound as it is) into
an MP4, when the video fits 60 MB at that rate (about two and a half minutes; longer ones have only their first frame
and open their original). It is sealed as `AttachmentContent.clip` (field 22, `MediaCrypto.Part.CLIP`, its own blob)
and goes up with the previews (`uploaded`); `MediaSharing.clip` opens it (fetched and kept as a preview is, asked of the
sharer again with `MediaPart.CLIP` when the server let it go), and `fetchPreviews` fetches it with the video's frame
when videos come at once. The first frame stays in `previews`, so the chat, the receipt and older apps are unchanged.

A preview the server has let go (404) is asked of the phone that shared it (`MediaRequestContent` with `PREVIEW` and
the width, `askAgain`, at most once an hour for a part): that phone puts the very sealed file it keeps back on the
server for that chat (`answerPreview`; shared again when another chat still has it), so the key in the message still
opens it, and answers READY; the asker then fetches it. Only for an item of a message it sent in that chat, not
deleted, to someone still in it, as for originals. With that phone gone or off, the preview stays missing until it
answers.

Every file has its own key, and a chat gets the keys of exactly the files it may read (2026-11-28, hen chose "a key
per file, inside each chat's message" after Fable's review found that a re-share handed chat B the item key chat A
has, which would open what is made later for B). A first share sends the item's key, from which each part's is
derived (`MediaCrypto.partKey`, salted by its blob id). Shared again to another chat (`again`), the item goes
without its key: each preview and the poster carry their own (`PartContent.key`), the same blobs, so it is still
stored once; its original's key goes in the READY reply (`#okey`, the first chat's original's own key, or a new random
one when it is uploaded for this chat). A new crop's previews get random keys of their own in every chat, and an
edit never changes an item's key. A fetched original is checked against the SHA-256 its message named.

Original videos can stream (2026-11-28, hen): a video bigger than the cache limit always does, and with Settings ›
Storage › Stream original videos (off by default) every one does. Opening one gets its blob as usual (asking the
sharing phone when the server doesn't have it) and then `OriginalState.Streaming` instead of fetching: the viewer's
player reads it through `StreamDataSource`, which asks `streamOriginal` for the bytes from where it plays; that reads
the blob from the chunk there (`Range: bytes=N-`), opens chunk by chunk in memory and writes nothing. A seek opens it
again; a file still being uploaded is followed as it grows.

Every request for an original is logged on the sharing phone (2026-11-28, hen: "every original image that requested
will log somewhere; how can we be sure there isn't a way to download all my images"), refused ones too
(`OriginalRequest`, `RequestResult`). A request names only a message and an item; it is answered only for a message
this phone sent in the very chat it came from, not deleted, to someone still in that chat (a group's reader), and the
file read is the one kept when it was shared: nothing in a request names a file, and the ids are random. The original
goes up sealed with that item's own key, which only the chat has.
(`MediaOriginalTest.everyRequestForAnOriginalIsLoggedAndOnlyMineInThatChatAreAnswered`.)

A picture deleted for everyone by its sender is gone for everyone (2026-11-28, hen: "if i delete image i sent from chat,
the peer no longer have access to that image"): its previews, poster and uploaded original are deleted from the
server and the sender's phone (`revoke`), the others' phones delete what they kept, the only copies too
(`forgetTaken`), and a later request for the original finds no message and is refused. Delete for me only removes it
from this phone. (`MediaOriginalTest`.) Next, the last phase: a Journal server's copy counts as existing, and the
originals sent to me can be backed up there.

A picture shared again, to another chat or the same one, is stored once (2026-11-28, hen: "share images between groups
without doubling the storage"). When it is picked, `prepare` looks for an item this phone shared before that is the same
file (`earlier`: the same source, else the same size and then the same SHA-256) whose file was readable when last
checked and whose previews are still here; it is sent again as that item with its own id (`again`: the same key,
previews and poster, so the same blobs; `#from` names the first item). Its previews aren't made or uploaded again: the
server is asked to let the new chat read them too (`POST /blob/<id>?peer=` or `?group=`, the server's
docs/messaging.md, "Blobs"), and only if they are gone from there are they uploaded. A request for its original is
answered with the original already uploaded for the first sharing when it is still on the server, shared the same way.
Deleting it in one chat takes the blobs out of that chat only (`DELETE /blob/<id>?peer=`): the server deletes a blob
when no chat is left, and this phone keeps a preview while another message names it. The previews are the widths made
for the first chat's screens. (`MediaSharingTest.aPictureSharedToAnotherChatIsStoredOnce`.)

Each picture may have a composition of its own (2026-11-28, hen: "pick the point of interest of every image i send ...
when composing an image, by default its the full width of the image and the top and bottom border can be trimmed";
"edit the composition after message sent"). `AttachmentContent.crop` (`CropContent`, in 1/10000 of the original) says
how much is trimmed off the top and the bottom (a tenth stays at least) and the point of interest; `width` and `height`
stay the original's, and `shownHeight` is what the chat shows. `MediaSharing.recrop` makes the previews again from the
original's kept part (`MediaPreparer.crop`; Android decodes it upright again and cuts it before encoding), seals them
with the item's own key under new blob ids, and drops the old ones here and on the server. A draft's go up with the
rest (`keep` and `drop` read the draft as this phone holds it now); a sent message's go up first, then the chat is told
with a quiet edit (`EditContent.item`: the same item id, kind and key, its new crop and previews; `takeRecrop` refuses
anything else and keeps what the item is, so only its look changes). Opening it still shows the whole original; until
the original is here, the cropped preview sits where its part lies. Where a box is another shape than the crop, the
picture is cut around the point of interest. (`MediaSharingTest.aDraftsPictureIsCroppedBeforeItGoes`,
`aSentPicturesCompositionChangesQuietlyOnTheirPhone`.)

## Next

iOS, as the plan says, once phases 5b and 6 are tried on phones.

The Firebase project for phase 3's push is `talk-3e556` (2026-11-22): `androidApp/google-services.json` is its
Android app `com.chencohen.ptt`. It isn't wired in yet (no google-services plugin, no Firebase dependency); phase 3
adds them. That file holds only public identifiers; the server's service-account key never goes in git.

## Sending only while the screen shows it (2026-11-25, standing rule)

hen: "when sending video or voice, only when the UI says it's transmitting, then transmit." What the screen shows and
what goes out are one thing:

- A press is live (pill or box, Stop) from the moment its sound goes into the talk room, and the room's microphone and
  camera are unmuted only while it is live.
- Nothing waits for a floor (2026-11-27, hen: "remove this logic"; TalkPhase.WAITING is gone): a Busy with my own
  floor asks again after letting go of my recent bursts, a Busy from anyone else ends the press as busy.
- Let go offline, or with no answer to the floor: nothing of it is kept or sent (it used to be kept as a
  voice message, which put 0:01 bubbles in the chat). Only the composer's voice message button records to keep.
- A burst that was live and then lost the connection keeps what went out as its clip.

Tests: TalkTest aPressWhileTheOtherTalksWaitsSendingNothingAndGoesLiveWhenTheirsEnds,
aPressLetGoWhileWaitingSendsAndKeepsNothing, aPushWhileOfflineIsShownOfflineAndNothingIsKeptOrSent.

## The microphone only while Push shows (2026-11-25, standing rule)

hen: the microphone is open and ready (muted) only while a red Push button is on screen, in the chat or the floating
window, and no other app holds the microphone. Everywhere else (warmed in the background, the chat list, the app in
the back without the floating window, the screen off) the phone only listens: no microphone track, no recording, no
privacy dot. The exception is a hardware button (a headset's or a PTT key) pressed with no Push on screen: it opens the
microphone at the press; the clip records from the press and the room catches up.

Never taken from someone else (hen: "if the phone already uses the microphone for some reason don't hijack it"): in a
phone call, or while another app records, the microphone isn't opened ahead and a press is refused ("The microphone
isn't free", nothing shown live). If another app starts recording while ours is open and idle, ours is let go at once
and comes back when the microphone is free and Push still shows (MicGuard, AudioRecordingCallback).

## The camera only while the slide-up shows (2026-11-25, standing rule)

hen: "the camera should open only when the slide-to-video is appearing on screen". The camera (and its encoder and the
room's video track reading it) is open only while a press holds Push with the slide-up-to-video target showing (with
Settings › Talk › Get camera ready while talking on), or while video is live. Letting go without sliding up closes it at
once (no linger); a quick press again opens it anew one step at a time, so it never comes up black. A camera another app
holds is not taken (CameraManager's availability): the slide up stays voice only and says "The camera is in use by
another app".

## Each push sent once: the room, or the clip (2026-11-25, hen)

A push goes through the talk room when the other side hears it there; its clip is uploaded only as the fallback.

- One to one: at the grant the phone waits up to 500 ms (CLIP_DECIDE_MS) to see the other user in this phone's room
  with its microphone (and, for video, its camera) track subscribed (LiveKit's LocalTrackSubscribed). If so, the
  clip's segments are recorded and kept on the phone but not uploaded, and the floor ends with a TalkEnd.
- If they aren't in the room, aren't subscribed in time, or leave or the room fails during the push, the clip goes
  up from its first segment, as before.
- A chat that keeps its pushes (Keep voice / Keep video) always uploads the clip, live, as the kept copy.
- Groups always upload for now (who is in the room isn't known on the phone).
- The receiver that heard a push live doesn't read the clip from the server afterwards; a woken phone's screen shows
  the room's picture and keeps it through a moment without frames (a simulcast layer switch had blanked it).

## The server buffer for pushes (2026-11-25, hen: "server buffer", "send each push once")

A push goes out once, into the chat's talk room. Whoever isn't in the room gets it from the server's buffer, so the
phone never uploads a second copy.

**Sending.** Nobody waits to send, and there is no "Waiting for …" anywhere: in a one-to-one chat and in a group,
every talker has a floor of their own (a group no longer has one floor for all). A press unmutes the microphone track
(and, for video, the camera track) already published, muted, in the room.

**LiveKit only** (2026-11-27, hen: "livekit only, show connection indication without words, only visual"). A push goes
into the room and nowhere else: nothing is uploaded beside it. A press the room can't take yet waits for it instead
of going another way (the phone isn't in the room yet: right after a chat opens, a hardware button, the floating
window, the widget, a notification's talk-back, the car; or the room has no microphone track of this phone's):

- `Talk.connect` shows the press as `TalkPhase.REQUESTING` (the Push button's connecting look, no words) while
  `LiveTalk.readyForPress` joins the room and publishes the microphone's track, asking again every 500 ms for 10 s at
  most. Nothing is recorded meanwhile: what is said before the press is live isn't sent.
- The moment the room can take it, and the button is still held, the press begins like any other: the unmute, then
  the floor. The floor is asked for only once the voice is live in the room (`Outgoing.room`, `LiveTalk.talking`'s
  answer), so the others are never told of a push that reaches nobody. `TalkPhase.TALKING` shows from the grant.
- Let go while connecting: back to ready, nothing sent or kept.
- The room can't be reached in 10 s, or doesn't take the unmute: the press ends as `TalkPhase.REFUSED` with
  `OwnTalk.ROOM` (the button falls back to ready with a short buzz, no words), nothing sent or kept.
- The room is lost during a push (`LiveTalk.onLost`, or a segment recorded while the microphone is no longer live
  there): the press ends the same way; what went into the room until then is the push, kept on the talker's phone
  as its clip.

The older clip upload stays as a setting, "Send through the server when they're away" (off), for a server without the
buffer; with it on, or on a server without live talk, a press goes by its clip from the moment before the press, as
before.

**The recorder in the room.** ptt-server's recorder (package recorder, as for saved calls) joins a chat's talk room as
a hidden participant right after the first phone joins it (on its talk token), so it is there before any press. It
subscribes every talker's microphone and camera (the best video layer) and sends nothing.

**What it buffers.** For each push (a talker's track from unmute to mute), while any of the chat's members isn't in
the room with that track subscribed (the other user of a one-to-one chat; in a group, each member), the recorder
writes the push's frames, still encrypted end to end with the talker's room key (which the server never has), into a
growing blob of kind TalkBuffer, in the saved calls' track record format (microseconds since the push began, a key
frame flag, the frame). When nobody is missing, nothing is written. The blob's readers are the members who weren't in
the room (the relay's rules apply: pairing, blocks, group membership).

**Telling the absent.** Each of those members' devices gets a BufferedTalk frame on /ws (proto/ptt.proto: blob id,
the push's burst id, the talker's user.device, the chat (peer or group), audio and/or video, when it began) and, when
not connected, a talk push (the existing kinds), so a sleeping phone wakes for it.

**Keeping.** The buffer goes when every reader has read it to its end, or after livekit.talk_buffer_hours (default
24). It is not the kept-in-chat copy: each phone keeps a push in its chat (Keep voice / Keep video) from what it played
(the sender from what it sent); never a second upload.

**Playing.** The phone reads /blob/<id> as it grows, decrypts each frame with the talker's key (the talk key ring sent
end to end when it joined; the saved calls' decryption), and plays it through Opus and the video decoders with the
usual catch-up until live. If the same push is also heard live in the room, it plays once: whichever is live wins,
never both, and the picture never flips between the two (the last frame stays until the next draws).

**Fresh pushes only play by themselves.** A phone that learns of a buffered push plays it with catch-up only if the push
is still live or ended at most 5 s ago. Older than that (a phone that was offline for hours), it never plays by itself:
it downloads in the background and shows in the chat as a voice or video message bubble, a missed push at its own
time, with a usual message notification; it plays on a tap. The same for audio and video, one to one and in groups.

**The sender's bubble, and taking a push back.** A push someone didn't hear live shows on the sender's phone too, as a
voice or video message bubble made from its own recording, with its state (waiting, delivered, played; per member in
a group). The sender can delete it for everyone before or after it is played: deleteTalkBuffer(id) (its talker only)
removes the server's buffer at once, and an end-to-end delete, as for a message, removes it from the phones that
already have it. A push everyone heard live makes no bubble (unless the chat keeps pushes).

**Groups.** Members with the group chat open are in its room live; the others get the buffer and a push. Opening a
group doesn't warm its members (no silent group warm-up) unless hen asks for it.

## Open issues

### A push saved as kept message bubbles (fixed 2026-11-25 by the rule above)

At 12:50 a quick push from the S25 to testbot (voice, then slide up to video) ended as two kept bubbles on hen's side, a
0:01 voice and a 0:01 video, although testbot's keep switches were off. The pushes after it folded into the quiet line as
they should.

Likely cause: Talk.finish keeps any push that never got the floor (`!out.granted`: the start frame failed, or no answer
within GRANT_WAIT, so toVoice) as a voice message (ClipKind.VOICE, kept, sent by deliverVoice with ClipContent.VOICE). The
slide-up ends the voice burst and starts the video one 300 ms after the first lets go, so both can miss the grant together.
Since the burst was already heard live through the talk room, it should still be delivered, but as a burst (BURST kind,
folded into the session's line on both phones), not as a kept voice message. To do: reproduce with a quick
press, slide and Stop; log each message's kind and keep decision; add a test.

## Who can hear you, urgent pushes, live as long as they transmit (2026-11-25, hen)

Server: TalkStart/TalkState.urgent, HearState frame (15), Presence.hear, setHearVisible (server ux/dnd-hear). App: this
phone's Hear (AndroidHear: call, Do Not Disturb, ringer, media volume) goes to the server as HearState; a push that
comes while it can't hear is saved (CanHearRules), the talker told end to end (TalkHeardContent, HeardStatus); urgent
pushes through Do Not Disturb with the contact's switch and Do Not Disturb access (AndroidDndGate). Screens:
docs/screens/talk.md, "Who can hear you". The listener's live state follows the talker's room beats (TalkBeat,
LiveSigns): docs/screens/talk.md, "Live for exactly as long as they transmit".
