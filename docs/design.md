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

## Next

Push-to-talk (5), calls (6) and iOS, as the plan says.

The Firebase project for phase 3's push is `talk-3e556` (2026-11-22): `androidApp/google-services.json` is its
Android app `com.chencohen.ptt`. It isn't wired in yet (no google-services plugin, no Firebase dependency); phase 3
adds them. That file holds only public identifiers; the server's service-account key never goes in git.
