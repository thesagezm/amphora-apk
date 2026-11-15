# QUEUE.md

Working memory for the Amphora-APK build pipeline. Owned by the agent.
Updated in the same commit as the work it describes.

**Working style (2026-11-15, user):** no subagents — do the research and
the implementation directly in this session. Commit fast, commit often —
small commits, one concern each, at every green unit. The UI must look
like the iOS version (`~/Amphora-ios/Amphora/UI/` is the design spec;
read the SwiftUI file before porting any view).

## Current Milestone

**A0** — repo skeleton (Gradle `:core` + `:app`, Compose placeholder,
manifest, icon, this file). In progress this session.

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
  created. A0 starting.
