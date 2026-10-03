# iOS UI porting rules (shared by every UI porting agent)

> **Status:** the port is complete; every Android screen has its SwiftUI twin. These rules still apply to any new or
> changed iOS screen: change the Android screen first, then port the change following this file. The steps about an
> isolated copy, a dedicated simulator and a report are for porting agents working in parallel; when working directly
> in the repository, skip them.
>
> Moved here from `refs/ui-agent-rules.md` in the 2026-09-30 reorganisation. Paths now point inside this repository;
> `<copy>` is the agent's isolated working copy and `<UDID>` its dedicated simulator.

## What you are doing
Porting screens of the working Firepit Android app (Kotlin + Jetpack Compose, source of truth, READ ONLY:
android/app/src/main/java/com/getfirepit/app/ in this repository) to SwiftUI in the iOS app.
Same functionality, same states, same rules, same English microcopy. Native iOS look and behaviour (Apple HIG),
not a Material imitation. The data layer below the screens is already ported 1:1 with identical names
(ios/Packages/FirepitKit: FirepitModel, FirepitProtocol, FirepitCrypto, FirepitTransport, FirepitData).

## Where
Work ONLY in your isolated copy (path given in your brief) and ONLY in the feature folder(s) and test file(s) named in
your brief. Never edit DesignSystem/, App/, other features, the package, or the Xcode project. If you need a shared
component that does not exist, write it inside your feature folder with a feature prefix and list it in your report.

## Architecture
- ViewModel (Kotlin `@HiltViewModel class XViewModel`) → `@Observable final class XViewModel` in the app target (the
  target defaults to @MainActor). Its constructor takes the same dependencies as the Kotlin one, from `AppContainer`
  (read App/AppContainer.swift). Kotlin `data class XUiState` → `struct XUiState: Equatable` with the same fields;
  `StateFlow` properties → observable properties with the same names; same method names and parameters.
- Kotlin flows collected in `viewModelScope` → a `func observe() async` on the view model that loops over the
  repository streams (`for await …`), started from the screen's `.task { await model.observe() }` so it stops with
  the screen. One-off actions → `Task { … }` inside the method, as Kotlin's `viewModelScope.launch`.
- Screen (Kotlin `@Composable fun XScreen`) → `struct XScreen: View` taking its view model (or `app: AppContainer`) in
  init. Keep Kotlin's split of composables into small private views.
- Repositories and stores come from `AppContainer` (`app.mesh`, `app.rooms`, `app.receipts`, `app.location`…).
  AppRouter (App/AppRouter.swift) carries cross-screen requests (tab, pending channel, pending invite).

## Compose → SwiftUI mapping (use the native control, not a lookalike)
Scaffold + TopAppBar → NavigationStack + `.navigationTitle` (+ `.navigationBarTitleDisplayMode(.inline)` on detail
screens) + `.toolbar`; FirepitTopBar with avatar/subtitle → `ToolbarItem(placement: .principal) { FirepitBarTitle }`.
LazyColumn/ListItem → `List` with `Section`s (`.listStyle(.insetGrouped)` for settings-like screens, `.plain` for
conversation lists). ModalBottomSheet → `.sheet` with `.presentationDetents` or our `.fittedSheet()`. AlertDialog →
`.alert` (text fields allowed) or `.confirmationDialog` for choices/destructive actions (role: .destructive).
DropdownMenu/long-press → `.contextMenu` / `Menu`. Swipe-to-dismiss rows → `.swipeActions`. Switch → `Toggle`.
Radio groups/segmented → `Picker` (.inline/.segmented/.menu as appropriate) or `FirepitChip` rows where Android uses
FirepitChip. Snackbar → a transient banner or `.alert` for errors that need acknowledging. FloatingActionButton →
toolbar button or a floating control with `.floatingControlBackground(in:)`. Search field → `.searchable`.
Pull-to-refresh → `.refreshable`. Share intent → `ShareLink`. Clipboard → `UIPasteboard`. Open settings → the
`openURL` environment with `UIApplication.openSettingsURLString`. Empty states → `ContentUnavailableView`.
Haptics → `.sensoryFeedback`.

## Look
- Tokens only: `FirepitColors`, `IdentityColors`, `FirepitFont`, `FirepitSpacing`, `FirepitRadius`; icons via
  `FirepitIcon`/`Image(icon:)` or SF Symbols matching Android's glyph. Never a colour literal.
- Page background `FirepitColors.surface`; on Lists use `.scrollContentBackground(.hidden)` +
  `.background(FirepitColors.surface)` and `.listRowBackground(FirepitColors.surface2)`, as Android draws white cards
  on the warm page.
- Reuse the design system components (read DesignSystem/): IdentityAvatar, RoomAvatar, InfraAvatar, LiveRing,
  FirepitChip, ChipRow, UnreadBadge, SectionLabel, TimelinePill, FirepitCard, Callout, MessageBubble, QuotedMessage,
  SystemChip, StatusTick, FirepitBarTitle, FirepitButtonStyle (.prominent/.outlined/.destructive/.pill),
  fittedSheet, floatingControlBackground, chatScrollAnchor, FirepitMark; and QrCode, QrScanner, PermissionNeeded,
  SecureWindow (`holdsSecureWindow`) from Features/.
- Identity colours come from the environment (`identitySlots`/`identityMarks`), which IdentityAvatar already reads.

## Text
- Android's English strings verbatim. Each user-facing string is ONE literal (`Text("…")`, `LocalizedStringKey`,
  `String(localized: "…")`), with interpolation inside the literal. NEVER build a sentence with `+` or from fragments:
  a concatenated `Text("a" + "b")` is shown unlocalized. Long strings: multi-line literal with `\` line continuation.
- User content (names, messages) via `Text(verbatim:)`.
- Plurals: `^[\(count) item](inflect: true)` or formatters; numbers and dates with `.formatted(...)` (locale-aware).

## Accessibility
Every icon-only button has `.accessibilityLabel`; decorative images are hidden; rows that Android merges
(`clearAndSetSemantics`/`mergeDescendants`) → `.accessibilityElement(children: .combine)` with a label; status that is
colour-coded also has text; Dynamic Type everywhere (fonts from FirepitFont, no fixed sizes); hit targets ≥ 44 pt;
nothing truncates at the largest accessibility size without a way to read it.

## Code quality
Swift 6, zero warnings. One statement per line, no line over 120 characters, `///` doc comments carrying the
Kotlin KDoc substance. No force unwraps without a comment proving safety. No `print`; os.Logger with nothing personal
logged. No `@unchecked Sendable`/`nonisolated(unsafe)` without justification.

## Verification you must do
- Build: `xcodebuild -project <copy>/ios/Firepit.xcodeproj -scheme Firepit -destination 'platform=iOS Simulator,id=<UDID>' -derivedDataPath <copy>/DerivedData -clonedSourcePackagesDirPath refs/DerivedData/SourcePackages build` → no errors, no warnings in your files.
- Tests: `… -parallel-testing-enabled NO -only-testing:FirepitTests test` → all pass, including your new ones.
- Parity: `python3 scripts/check-port-parity.py --kotlin <your Kotlin files> --swift <your Swift files>` → OK (justify any --ignore; composable-only helpers that became SwiftUI modifiers may be ignored with reason).
- Look at your screens on YOUR dedicated simulator (the brief gives its UDID; never use another one): boot it,
  install the built app, launch it with `-demo` (an in-memory demo world: rooms "Camp" and "Trail crew", people
  Maya/Jonas/Priya/"Camp base" with positions, a day and a half of messages incl. a reply and read receipts, a direct
  thread with Maya, pins, unread messages in Trail crew, and live sharing to Camp — see Features/Demo/DemoWorld.swift)
  plus your screen's debug route (`xcrun simctl launch <id> com.getfirepit.app -demo -route chat.room`), and take
  screenshots (`xcrun simctl io <id> screenshot file.png`) in light and dark
  (`xcrun simctl ui <id> appearance dark|light`); view them and fix anything misaligned, clipped or off-token. Debug
  routes must host the real screen with its real view model and `.task { await model.observe() }`, so they show live
  state. The simulator cannot reach the internet here, so map tiles stay blank — that is expected.
- Build with the same destination id: `-destination 'platform=iOS Simulator,id=<your UDID>'`.
