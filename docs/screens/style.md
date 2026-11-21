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
| Ink (`Inverse` / `OnInverse`: ink on light, paper on dark) | The one main action of a screen (later the talk button), the selected tab, text actions, the profile circle | |
| Teal (`Accent`) | "Live" and progress: a running step, a check, later someone talking | Buttons and links |
| Red (`Error`) | Actions that leave, remove or revoke (Register again, later Remove contact, Revoke device), failures | Anything that only changes a setting |
| Orange (`Warning`) | Warnings: the Offline pill's cloud, the rejected card's mark, "Can’t reach your server right now" | Links and buttons |
| `Muted` | Second lines, notes, section labels, outlines, a control that isn't there yet (the pencil) | |
| `Raised` | Cards and tiles on `Background` | |

Material mapping: primary = Inverse (onPrimary = OnInverse), secondary and tertiary = Accent, surface and background
= Background, surfaceContainer* = Raised, onSurface = OnBackground, onSurfaceVariant and outline = Muted, error = Error.

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
states) and Edit (the new-chat pencil) were added for this app.

## Messages

No toasts and no AlertDialogs. One `MessageBar` (`LibraryMessages.kt`), hosted once by home (and once by
enrollment) above the bottom controls: an ink bar with the text, an optional action and a close button. Screens post
to `LocalMessages`. A plain note goes after 6 s; one with an action stays until dismissed.

## Words

- **This phone**, not "this device", in the app's own strings; enrollment and Settings' device rows keep
  "device", the server's word.
- **Register again** is the one name for enrolling anew, on home's card and in Settings.
- Curly apostrophes (’) in `values/strings.xml`. English only for now ([design.md](../design.md)).
- Screens, flows and gestures follow Signal's messenger pattern; no Signal name, icon or artwork.
