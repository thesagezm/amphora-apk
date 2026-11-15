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

**A1** — core protocol port (A0 landed 2026-11-15).

## Queue (pending, in order)

- **A1** — core protocol port: all 33 PandoraCore sources to `:core`
  (package `pandoracore`), with the full fixture test suite ported
  alongside — target: all 379 iOS tests green via `./gradlew :core:test`.
  Order within A1: JSONValue/AnyJSON → PandoraAPI constants →
  PandoraCrypto + vectors → URLBuilder → ResponseEnvelope → Errors →
  SessionManager → LegacyPandoraClient → the model/feature files →
  lyrics/radio/lastfm → network defaults.
- **A2** — auth: Keystore session store, auto-login, login screen
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
- **Version-pinning lesson (from speechnotes-android)**: proven pair AGP
  8.9.1 + Kotlin 2.4.20 + compose BOM 2026.09.00 — but that BOM resolves
  Compose 1.12.x which needs AGP 9, so the app module carries the
  speechnotes `resolutionStrategy.force` block pinning Compose to the
  1.9.x line / material3 1.3.2 / icons 1.7.8. JDK 17 pinned in
  user-level `~/.gradle/gradle.properties`
  (`org.gradle.java.home=~/tools/jdk-17.0.13+11`) — system java is 11;
  CI gets its JDK from setup-java, so nothing JDK-specific is committed.
