# Style: the rules every screen follows

Journal's look, copied from journal-client-kmp's `docs/screens/style.md` and cut down to what this app has. New
screens follow these rules; a screen that breaks one is a bug. Android code is in `ui/style/`: `LibraryStyle.kt`
(colors, type, shapes, icons), `LibraryButtons.kt` (buttons), `LibraryPages.kt` (pages, empty and loading states),
`LibraryMessages.kt` (the message bar) and `LibraryChrome.kt` (the Offline pill, the tabs, cards and rows). The names
stay Journal's so the two apps can be compared side by side.

## Colors

One palette, light and dark (`LightLibraryPalette`, `DarkLibraryPalette`). Material's colors are built from it
(`materialColors`), so every Material piece (text fields, switches, default buttons) matches. There are no wallpaper
(Material You) colors: `TalkTheme` is `LibraryTheme`, and enrollment looks like the rest. Every screen follows the
system's light or dark theme.

| Color | Is for | Never for |
|---|---|---|
| Pink (`Inverse` / `OnInverse`: orange-pink (coral) #E9504F with white, in both themes) | The one main action of a screen (later the talk button), the selected tab, text actions, the profile circle, the new-chat pencil | |
| Teal (`Accent`) | "Live" and progress: a running step, a check, later someone talking | Buttons and links |
| Red (`Error`) | Actions that leave, remove or revoke (Register again, Block, Remove contact, later Revoke device), failures | Anything that only changes a setting |
| Orange (`Warning`) | Warnings: the Offline pill's cloud, the rejected card's mark, "Can’t reach your server right now", "Safety number changed" | Links and buttons |
| `Muted` | Second lines, notes, section labels, outlines, a control that isn't there yet | |
| `Raised` | Cards and tiles on `Background` | |

Material mapping: primary = Inverse (onPrimary = OnInverse), secondary and tertiary = Accent, surface and background
= Background, surfaceContainer* = Raised, onSurface = OnBackground, onSurfaceVariant and outline = Muted, error = Error.

- **My chat bubbles** (2026-11-23, hen: option C, deep teal; `LibraryColors.Mine`/`OnMine`): `#163A36` with text
  `#DFF3EF` in the dark theme, `#D3EEE9` with `#0E2B28` in the light one; their time and ticks at 60%. In the
  conversation and the quick-reply window; received bubbles stay as they are. A draft (mine or theirs, live typing)
  keeps its outline (mine coral), and mine fills into the teal bubble on Send. Coral stays for the talk button and
  actions.

## Buttons

- **PrimaryButton**: filled ink, full pill. One per screen or card: Get started, Continue, Register phone, Open
  Chats, Register again. `destructive = true` makes it red (only for leaving, removing or revoking).
- **SecondaryButton**: outlined ink, full pill. A second action (Scan QR code, Paste link), or a card's action under
  its body.
- **TextAction**: ink semibold text (red when destructive): actions inside a row ("Change", "Copy", Cancel). Never
  teal or orange.

## Cards on home

A card on home is one `HomeCard`: `Raised`, a title, one line, one ink action. Phase 1 has one: Register again, with a
small warning mark, when the server refuses this phone. Nothing shows on home while all is well.

## Type

Named sizes in `LibraryType` (`LibraryStyle.kt`); no hand-typed sizes. A call may still set its own weight or color.

| Style | Size | Weight | For |
|---|---|---|---|
| ScreenTitle | 30 | bold | "Chats", "Calls" |
| PageTitle | 22 | bold | A page's title (`TitleBar`) |
| SectionLabel | 13 | semibold, Muted | The label over a settings card (`SectionTitle`) |
| RowTitle | 16 | semibold | A row's title, the profile circle's letter |
| Body | 14 | | Text, messages |
| Caption | 13 | | Second lines, notes (`PageNote`) |
| Small | 12 | | Badges and counts |
| Tiny | 11 | | The smallest |

Nothing is drawn below 11 sp. Text fields keep their own sizes.

## Motion

- **Combined changes move together, in one move** (2026-11-25, hen: "every combined animation in the app should be in
  one move unless otherwise specified"). When several things change at once (an item's size, its position, others
  making room, an item coming or leaving), they animate together over the same time, about 300 ms with an ease:
  never in steps such as grow-then-recentre, snap-then-slide, or one leaving and then the others gliding back.
- **One thing changes, it doesn't swap.** A voice pill becoming a video box is one element whose frame grows (centre
  and top edge fixed), not a pill going and a box coming; an item that stays live across a short gap (voice into
  video) never leaves and comes back.
- **Geometry doesn't depend on the language's direction.** Positions are worked out left to right and the language's
  order (theirs at its start side) is chosen explicitly, so Hebrew mirrors the order, never the motion.
- **No size guessed and corrected.** A box grows straight to its final size (its picture's shape known before it
  grows), so it never moves a second time.

## Shapes

`LibraryShapes`: cards and sheets 16 dp (`Card`, `Sheet`), tiles inside a card 12 dp (`Tile`), controls a full pill
(`Pill`: buttons, the tabs, the Offline pill). Round things (the profile circle, the pencil) are circles.

## Pages

- A settings-style page is `SettingsScaffold(title, back) { … }` (`LibraryPages.kt`): the page color, the scroll,
  room for the system bars and the floating `TitleBar` (back and the title in PageTitle). Settings uses it, and so
  will every page under it.
- Its sections are a `SectionTitle` over a `SettingsCard` of `InfoRow`s with `RowDivider`s between them.
- Intros and notes on those pages are one `PageNote`: Caption, Muted, 32 dp from the sides.

## Empty, loading, unreachable

- `EmptyState(text, actionLabel?, onAction?)`: a muted line, centered, with an optional outlined button under it.
  Chats and Calls put their line icon over it.
- `LoadingState()`: a small spinner with "Loading…" under it, centered.
- `UnreachableState(onRetry)`: "Can’t reach your server right now." with Try again.

The same paddings everywhere (32 dp).

## Icons

Line icons in `LibraryIcons` (24 dp, stroke 2): no typed characters as icons, no Material icon set, and no Signal
icon or artwork. Person (the profile circle before the server says who this is), Chat and Call (the tabs' empty
states), Edit (the new-chat pencil), Scan (scan a QR code), Shield (a verified safety number) and Block were added
for this app.

QR codes (`ui/Qr.kt`, ZXing, as journal) are drawn black on white in both themes, on a white tile with 8 dp corners;
they are scanned with Google's code scanner from Play services (no camera permission).

## Messages

No toasts and no AlertDialogs. One `MessageBar` (`LibraryMessages.kt`), hosted once by home (and once by
enrollment) above the bottom controls: an ink bar with the text, an optional action and a close button. Screens post
to `LocalMessages`. A plain note goes after 6 s; one with an action stays until dismissed.

## Words

- **This phone**, not "this device", in the app's own strings; enrollment and Settings' device rows keep
  "device", the server's word.
- **Register again** is the one name for enrolling anew, on home's card and in Settings.
- Curly apostrophes (’) in `values/strings.xml`.
- Screens, flows and gestures follow Signal's messenger pattern; no Signal name, icon or artwork.

## Hebrew and right to left

- English (`values/`) and Hebrew (`values-iw/`, Android's folder name for Hebrew; it also serves `he`). Every string
  added in English gets its Hebrew in the same commit. `res/xml/locales_config.xml` lists both, so Settings > Apps >
  Talk > Language picks one apart from the phone's language.
- Native Hebrew words, no loanwords (שרת, קוד רישום, תעודה, מספר ביטחון, צ׳אט). Buttons and steps in the infinitive
  (להתחיל, סריקת קוד QR, שליחה) and other text phrased so it addresses nobody as a man or a woman (״אין לך חיבור״,
  not ״אתה לא מחובר״). Gershayim and geresh are the Hebrew marks (צ׳אט, דק׳), not quotes.
- Plurals give `one`, `two` and `other` (and `many` as `other`): ״חבר אחד״, ״שני חברים״, ״%d חברים״.
- Screens are right to left as they are (`android:supportsRtl`): padding, alignment and shapes use start and end,
  never left and right. Back and the row chevron are mirrored (`mirrored = true` in `LibraryIcons`); icons whose
  meaning doesn't flip (the pencil, QR, search) aren't. As in Hebrew messengers, your own bubbles sit at the end, which
  is the left.
- What is read left to right stays so: the server address, the enrollment and invite codes and email fields
  (`TextDirection.Ltr`), the safety number's groups (laid out left to right), and IDs and versions inside a
  Hebrew sentence, wrapped in FSI/PDI marks (U+2068, U+2069) so their dashes and brackets don't move.
