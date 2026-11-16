# QUEUE.md

Working memory for the Amphora-APK build pipeline. Owned by the agent.
Updated in the same commit as the work it describes.

**Working style (2026-11-15, user):** no subagents — do the research and
the implementation directly in this session. Commit fast, commit often —
small commits, one concern each, at every green unit. **Confirmation of
success is a green GitHub Actions build — do not expect feedback from the
user between units; while the GitHub build runs, move on to the next
task.** The UI must look like the iOS version
(`~/Amphora-ios/Amphora/UI/` is the design spec; read the SwiftUI file
before porting any view).

## Current Milestone

**A3** — stations (A2 auth code-complete 2026-11-16, local gate green;
CI verdict pending on the handover push — check `gh run list` first).

## Queue (pending, in order)

- **A3.2 (next)**: `MainTabView` shell (Home/Radio/Settings tabs,
  mini-player slot seam) + `StationsView` port — loading/empty/error
  states, station rows with badges, toolbar (recents/add/reorder) —
  replaces `RootView`'s `PlaceholderHome`. Read
  `Amphora/UI/MainTabView.swift` + `StationsView.swift` first.
- **A3.3**: hero + browse row + pull-to-refresh + reorder UI
  (`moveStation` store intent already landed in A3.1).
- **A3.4**: `AddStationView` + `BrowseStationsSheet` + genre stations
  (store: create/createGenre/loadGenres intents).
- **A3.5**: rename/delete alerts + `TuneStationSheet` + station search
  (store intents for search landed with A3.4's MusicSearch plumbing —
  check `SessionManager.search`).
- **A3 note**: `.playStation` intent is A4 wiring — station taps stay
  inert in A3 (playback milestone); documented, not a hidden stub.
- **A2 verification owed before A4** (user-perceived): Waydroid smoke
  test — launch → login screen → wrong password shows the typed error
  copy → real login lands signed-in. Then user phone install via the
  CI APK artifact.
- **A3** — stations: fetch/list/reorder/create/rename/delete/search/
  genres, preferences store, mini-tab scaffold.
- **A4** — playback: Media3 ExoPlayer + `MediaSessionService`, media
  notification, prefetch, mini-player + `NowPlayingView` (the "it
  works" milestone).
- **A3** — stations UI (iOS `MainTabView` + `StationsView` look).
- **A4** — playback: ExoPlayer + `MediaSessionService`, media
  notification, prefetch, mini-player + `NowPlayingView`.
- **A5** — feedback / bookmarks / history / sleep timer.
- **A6** — lyrics (lrclib + translation + fallbacks + romanization).
- **A7** — radio mode (RadioBrowser + ICY probe + live-lag scheduling).
- **A8** — Last.fm + Settings + Diagnostics + themes + crash handler.
- **A9** — robustness & parity pass + device demo checklist.

## User Feedback Pending

- **2026-11-16, user (standing directive): device testing via Waydroid
  ONLY — no Android emulator, ever.** Overrides the AGENTS.md
  "emulator for smoke tests" line (AGENTS.md itself must not be edited
  by the agent). All on-device validation (smoke tests, UI checks,
  installs) goes through Waydroid with `adb connect`. No subagents —
  reconfirmed.

## Environment Notes (this machine)

- SDK `~/android-sdk` (android-36, build-tools 36.0.0); JDK 17 at
  `~/tools/jdk-17.0.13+11`;
  gradle-8.14.3 wrapper dist cached in `~/.gradle/wrapper/dists`.
- Local builds are synchronous — no CI, no waiting. Green build → commit.
- iOS source of truth: `~/Amphora-ios` (PandoraCore for `:core`,
  `Amphora/UI/*` for every view's design).

## Log

- 2026-11-16 — **user directive recorded**: device testing via Waydroid
  ONLY (no emulator), no subagents (standing).
- 2026-11-16 — **A3.1 landed**: `persistence/PreferencesStore.kt`
  (kotlinx-serialization JSON in `filesDir/preferences.json`, atomic
  best-effort save, fail-closed load; fields mirror the Swift file so
  names survive) + `AmphoraStore` stations state layer: `stations`/
  `isLoadingStations`/`isMutatingStations`/`currentStation`/
  `skipLimitStationId`/`isExplicitContentFilterEnabled`,
  `featuredStation`, `preferences` StateFlow, intents (refresh/rename/
  delete/move/dismissError), `displayOrder` port (QuickMix first →
  stored order → A–Z, id tiebreak), post-sign-in
  `refreshUserSettings` → `refreshStations` on both restore and login
  paths. Local gate green: 372/372 + APK.
- 2026-11-15 — session 1: plan approved (native Kotlin + Compose +
  Media3, full parity, repo `~/code/Amphora-APK`). AGENTS.md + QUEUE.md
  created (commit `6f92f4a`). User additions: success = green GitHub
  build, no user feedback expected between units, fire-and-continue
  while CI runs → `.github/workflows/android-build.yml` added
  (`:core:test` + `:app:assembleDebug` + APK artifact).
- 2026-11-15 — **A0 landed**: Gradle skeleton (`:core` pure Kotlin/JVM +
  `:app` Compose), placeholder MainActivity (dark, iOS-palette colors),
  manifest (cleartext traffic, media-playback permissions, portrait
  lock), adaptive launcher icon generated from the iOS AppIcon via
  `Tools/gen_android_icons.py`, wrapper 8.14.3 copied from
  speechnotes-android. Local gate green: `:core:test` 2/2 +
  `:app:assembleDebug` → `Amphora-debug.apk`.
- 2026-11-15/16 — **A1 landed, unit by unit** (A1.1→A1.13, every push
  green on GitHub): PandoraCrypto (vectors byte-exact incl. Schneier
  KAT) · URLBuilder/ResponseEnvelope/errors · transport seam +
  LegacyPandoraClient · Station/StationOrder/RadioStationIdentity ·
  Feedback/Playlist/Track (quality promotion) · MusicSearch/
  GenreStations/Advertisement · ArtworkURL/SongArtwork/ArtworkColors
  (same HSV derivation → identical theming) · PlayHistory/UserSettings/
  SleepTimer · **SessionManager** (actor→Mutex, 1001 replay-once, all
  deferred suites) · Lyrics trio (LRC parser, lrclib, translation
  alignment contract, romanization gates, fallback chain) · LastFm
  (MD5 signing, scrobble policy) · RadioBrowser/RadioStreamMetadata
  (ICY state machine, mirror rotation). **372/372 green in `:core`.**
- 2026-11-16 — **A2 landed (auth)**: `ui/Theme.kt` (the full
  Theme.swift token port — 6 appearances × 5 accents, radii, gradients,
  press styles; `ThemeController` grows its persistence in A8);
  `pandora/OkHttpTransports.kt` (one shared OkHttpClient + cookie jar;
  Pandora POST/GET/modes/radio/Last.fm adapters with the URLError
  reason classification ported: timeout/unreachable/network failure);
  `pandora/CookieCsrfProvider.kt` (the app-side half of the
  CookieCSRFProvider split); `pandora/KeystoreSessionStore.kt`
  (AES-GCM in AndroidKeyStore, record sealed in
  `filesDir/session-session.seal`); `store/AmphoraStore.kt` (auth
  phase StateFlow, login/restore intents, the full `userMessage(for:)`
  error-copy port + session-dead return-to-sign-in);
  `support/AppLog.kt` (logcat + ring buffer; file mirror + crash tail
  in A8); `ui/LoginView.kt` + `ui/RootView.kt` + `MainActivity`
  rewired (restore on launch, phase switch). Local gate green:
  372/372 `:core:test` + `:app:assembleDebug`.
- **HANDOVER (2026-11-16, end of session 1)**: A0–A2 code-complete.
  Next agent starts A3 (stations UI) — read AGENTS.md, then QUEUE.md,
  then the iOS `MainTabView.swift`/`StationsView.swift` before writing
  any Composable. No subagents. Commit every green unit. CI green =
  the verdict; the Waydroid smoke test listed above is owed before
  A4's "it works" claim. `PlaceholderHome` in RootView is the only
  user-reachable placeholder (explicitly an A3 stub — replace it
  first).
- **Known port deltas** (each documented in its file header):
  URLError classification + CookieCSRFProvider (URLSession cookie jar)
  move to the app-side OkHttp adapter (A2); the seven-test gap vs the
  iOS 379 comes from platform-only assertions and the two Swift
  `stations(_:)` overloads collapsing to `stationsByCountryCode`/
  `stationsByTag` (JVM erasure).
- 2026-11-15 — **A0 landed**: Gradle skeleton (`:core` pure Kotlin/JVM +
  `:app` Compose), placeholder MainActivity (dark, iOS-palette colors),
  manifest (cleartext traffic, media-playback permissions, portrait
  lock), adaptive launcher icon generated from the iOS AppIcon via
  `Tools/gen_android_icons.py`, wrapper 8.14.3 copied from
  speechnotes-android. Local gate green: `:core:test` 2/2 +
  `:app:assembleDebug` → `Amphora-debug.apk`.

## Environment Notes (permanent)

- Version pair proven on this machine (speechnotes-android): AGP 8.9.1 +
  Kotlin 2.4.20 + compose BOM 2026.09.00, with the app module's
  `resolutionStrategy.force` block pinning Compose to the 1.9.x line /
  material3 1.3.2 / icons 1.7.8 (BOM 1.12.x needs AGP 9). JDK 17 pinned
  user-level in `~/.gradle/gradle.properties`
  (`org.gradle.java.home=~/tools/jdk-17.0.13+11`); CI supplies its own
  JDK — nothing JDK-specific is committed.
