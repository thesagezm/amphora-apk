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

**A2** — auth (A1 core protocol port code-complete 2026-11-16: all 33
PandoraCore sources + 372 fixture tests green in `:core`; the delta to
the iOS 379 is seven tests whose Swift files use platform-only
assertions or were subsumed — see the per-unit commit notes).

## Queue (pending, in order)

- **A2** — auth: Keystore session store, OkHttp transport adapters
  (HttpTransport + GET + modes + cookie-CSRF), auto-login, login screen
  (iOS `LoginView` look).
- **A3** — stations UI (iOS `MainTabView` + `StationsView` look).
- **A4** — playback: ExoPlayer + `MediaSessionService`, media
  notification, prefetch, mini-player + `NowPlayingView`.
- **A5** — feedback / bookmarks / history / sleep timer.
- **A6** — lyrics (lrclib + translation + fallbacks + romanization).
- **A7** — radio mode (RadioBrowser + ICY probe + live-lag scheduling).
- **A8** — Last.fm + Settings + Diagnostics + themes + crash handler.
- **A9** — robustness & parity pass + device demo checklist.

## User Feedback Pending

(none yet — first session)

## Environment Notes (this machine)

- SDK `~/android-sdk` (android-36, build-tools 36.0.0, emulator +
  google_apis system image); JDK 17 at `~/tools/jdk-17.0.13+11`;
  gradle-8.14.3 wrapper dist cached in `~/.gradle/wrapper/dists`.
- Local builds are synchronous — no CI, no waiting. Green build → commit.
- iOS source of truth: `~/Amphora-ios` (PandoraCore for `:core`,
  `Amphora/UI/*` for every view's design).

## Log

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
