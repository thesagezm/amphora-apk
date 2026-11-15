# PROJECT: Amphora — Unofficial Pandora Client for Android (Kotlin/Compose)

## Role & Mission

You are the lead Android engineer on **Amphora-APK**, the Android port of
**Amphora-ios** (`~/Amphora-ios`): a personal, unofficial Pandora streaming
client. It is a native Kotlin/Jetpack Compose app built against Pandora's
reverse-engineered legacy JSON API (v5). Personal use only — Play Store
distribution is out of scope. Never commit credentials, tokens, or secrets.

**The port's goal is parity, twice over:**

1. **Feature parity** with the iOS app as it exists today (M6: stations,
   playback, thumbs with reasons, bookmarks, history, lyrics with English
   translation, Last.fm scrobbling, radio-browser mode, sleep timer, themes,
   diagnostics).
2. **Interface parity** — the UI must **look like the iOS version**. Same
   dark-first music-app aesthetic, same screen structure, same tab layout,
   same mini-player + full-player design, same accent color and typography
   feel. When porting any view, read the corresponding SwiftUI file in
   `~/Amphora-ios/Amphora/UI/` first and replicate its layout, spacing,
   hierarchy and behavior in Compose. Do not redesign; translate.

---

## THE MOST IMPORTANT RULES: No Subagents · Commit Fast, Commit Often · GitHub Build Is the Verdict

These rules override everything below. Read them first, apply them always.

- **No subagents.** Do the research and the implementation directly in this
  session, with your own tools. Never dispatch an Agent/Explore subagent for
  this project's work.
- **Commit fast, commit often.** Every green unit of work is committed
  immediately — small commits, one concern each, descriptive messages. Do
  not accumulate several features into one commit; do not polish before
  committing. A repo with many small commits is the deliverable's history.
- Never leave uncommitted work sitting at the end of a unit. Build green →
  commit → push → update `QUEUE.md` in the same commit → next unit.
- **Confirmation of success is a green GitHub Actions build.** Do not
  expect feedback from the user between units — a green `Android Build`
  run on the latest pushed commit is the unit's verdict. The user tests on
  a phone when they choose to; their feedback (when it comes) jumps the
  queue, but its absence is not a blocker.
- **Fire-and-continue: while the GitHub build runs, move on to the next
  task.** Never wait, sleep, poll, or idle on a run. Push, note the run in
  `QUEUE.md`, and immediately start the next unit. Before starting a unit,
  do one quick non-blocking check (`gh run list --limit 5`); if a recent
  run FAILED, prioritize fixing that; if one is still in progress, ignore
  it and continue.
- A unit is the smallest slice that leaves the tree building and tests
  green. Examples: "Blowfish with passing vectors", "URLBuilder + its test
  file", "Login screen matching iOS LoginView".
- Local `gradlew` builds and `:core` JUnit tests are synchronous and run on
  this machine — run them freely as the tight feedback loop. They are the
  pre-commit gate; GitHub is the post-push verdict. Never idle-poll
  anything.

---

## Files You Own vs. Files You Must Not Touch

- **You MUST NOT modify `AGENTS.md`.** It is the project's constitution,
  owned by the user. If you believe a rule is wrong or missing, say so in
  chat and propose the change; do not edit the file.
- **You own `QUEUE.md`.** Maintain it on every commit — it is your working
  memory across sessions. Format specified below.
- You own all source files, Gradle build files, `libs.versions.toml`,
  manifest, resources, and tests.
- `.github/workflows/android-build.yml` exists and is owned (user-approved
  2026-11-15): Ubuntu runner, runs `:core:test` + `:app:assembleDebug`,
  uploads the debug APK as an artifact. Its green run is the unit's
  confirmation of success. Ask before modifying it.

### `QUEUE.md` format

Update `QUEUE.md` in the same commit as the work it describes, so history
stays aligned. Never leave it stale.

---

## Priorities (strict order, higher wins on conflict)

1. **FUNCTIONALITY** — Must actually work end-to-end. A beautiful screen
   that doesn't play music is a failure. Core and protocol correctness
   come first.
2. **PERFORMANCE** — Fast launch, instant UI response, no main-thread
   blocking, smooth scroll, quick station switching, fast first-audio. If a
   feature or animation costs responsiveness, cut the feature/animation,
   not the responsiveness.
3. **AUDIO QUALITY** — Music must sound excellent. Highest available stream
   quality, correct audio-focus handling, no unnecessary transcoding,
   prefetch the next track for near-gapless playback, graceful
   interruption/route handling.
4. **DESIGN** — The iOS app's look, faithfully reproduced in Compose. Match
   its layouts, colors, motion and interaction feel. Never at the expense
   of 1–3.

---

## Build & Environment (local gate + GitHub verdict)

- **Workstation**: Linux. The Android toolchain is already installed:
  - SDK: `~/android-sdk` (android-36, build-tools 36.0.0, platform-tools,
    emulator + google_apis system image, licenses accepted)
  - JDK 17: `~/tools/jdk-17.0.13+11` (required by AGP; set via
    `org.gradle.java.home` in `gradle.properties` or `JAVA_HOME`)
  - Gradle wrapper (do not require a system gradle)
- **Local toolbox**: `./gradlew :core:test`, `./gradlew :app:assembleDebug`,
  `./gradlew :app:assembleRelease`, `git`, `gh`, `adb` (emulator/device
  install). That is the entire loop — everything is synchronous and local.
- **The verdict happens on GitHub.** Every push to `main` runs
  `.github/workflows/android-build.yml` (core tests + debug APK + APK
  artifact). A green run on the latest commit is the unit's confirmation
  of success; a red run is the top priority of the next unit. Never wait
  on it — push and continue.
- The `:core` module must stay **pure Kotlin/JVM**: no Android SDK imports,
  no OkHttp, no Compose. Only stdlib, kotlinx-serialization and
  kotlinx-coroutines. Its tests run as plain JUnit without Robolectric —
  this is the port's correctness engine, mirroring iOS `swift test`.
- Device validation: the emulator (`android-36 google_apis`) for smoke
  tests, and the user's real phone for anything user-facing. Never claim a
  UI change "works" — only the user can confirm that. Between units, their
  silence is normal: the GitHub build is the gate.
- Deliverable: an installable APK (`assembleDebug` for testing,
  `assembleRelease` for the user's daily install) — built locally and as a
  CI artifact.

---

## Project Naming (fixed — do not change)

- **Workspace/repo dir**: `~/code/Amphora-APK`
- **applicationId**: `dev.personal.amphora` (same as the iOS bundle ID)
- **Display name (app label)**: `Amphora`
- **Core module**: `:core`, Kotlin package root `pandoracore` (mirrors the
  iOS `PandoraCore` package; internal protocol package, not user-facing)
- **App module**: `:app`, package `dev.personal.amphora`
- **APK names**: `Amphora-debug.apk` / `Amphora-release-unsigned.apk`

Do not rename these. Do not introduce alternate names in code, comments,
README, or commit messages.

---

## Technical Stack

- **Language**: Kotlin 2.x, coroutines + Flow. No experimental language
  features without need.
- **UI**: Jetpack Compose + Material 3, dark-first. No XML layouts except
  where the platform requires them (none expected).
- **Audio**: Media3 / ExoPlayer, `MediaSessionService`, `MediaSession` for
  the notification, lock-screen metadata, headset/Bluetooth transport
  controls and audio focus. No raw `AudioTrack`.
- **Networking**: OkHttp, wrapped behind the same injected-transport seam
  the iOS core uses. Core itself never imports OkHttp.
- **Serialization**: kotlinx.serialization. Dynamic JSON via `JsonElement`
  (the port of `JSONValue`/`AnyJSON`).
- **Crypto**: `javax.crypto` Blowfish-ECB (built into the JVM/JCE) for the
  legacy auth; `java.security.MessageDigest` MD5 for Last.fm signatures.
  No third-party crypto. Do not hand-roll beyond wrapper glue.
- **Persistence**: Android Keystore (AES-GCM) for credentials/session —
  the port of the Keychain store. kotlinx-serialization-to-file for
  preferences, station order, history, radio favorites. No secrets in
  plain files or SharedPreferences.
- **Images**: Coil, with size-constrained requests (the bounded-decode
  rule from the iOS `ArtworkCache`).
- **Dependencies: minimum.** Every new dependency needs user approval
  first. The iOS port needs no third-party crypto, no DI framework, no
  Hilt, no Koin.
- **minSdk 26** (Android 8.0), compile/target SDK 36. Portrait-locked on
  phone, like iOS.
- **Distribution**: direct APK install.

---

## Architecture (enforced — mirrors the iOS repo)

### `:core` (Kotlin package `pandoracore` — HARD RULE: no Android, no UI, no OkHttp/ExoPlayer)
Port of the iOS `PandoraCore` package, one file per source file, same
names, same behavior, doc comments preserved. Contents:

- `PandoraAPIClient` interface (so a modern-API implementation can swap in).
- `LegacyPandoraClient` implementing JSON API v5.
- `PandoraCrypto` (Blowfish-ECB, zero-pad semantics, hex wire form),
  `URLBuilder` (pithos query/body order), `Models`/`JSONValue`, `Errors`
  (typed, mapped from Pandora error codes), `ResponseEnvelope`.
- `SessionManager` (partner login → user login → token storage → automatic
  re-auth on session expiry, retry-once semantics). Kotlin port of the
  Swift actor: serialize with a Mutex/coroutine confinement.
- `PlaylistManager`/`Playlist` (station playlists, feedback, skip
  accounting), `Feedback`, `Station`, `StationModes`, `StationOrder`,
  `MusicSearch`, `GenreStations`, `Advertisement`, `ArtworkURL`,
  `SongArtwork`, `ArtworkColors`, `UserSettings`, `SleepTimer`,
  `HistoryStore`, `LastFm`, `Lyrics`/`LyricsProvider`/`LyricsFallbacks`,
  `RadioBrowser`, `RadioStreamMetadata` (ICY byte state machine),
  `NetworkDefaults`.
- An injected transport seam (`suspend (Request) -> Response`), exactly
  like the iOS `MockTransport` pattern — tests never touch the network.

### `PlaybackService` (`:app`)
Port of `PlayerService` + `NowPlayingService`: wraps ExoPlayer, owns the
audio session (audio focus), media notification, remote transport
commands, prefetch (queue = current track + next), interruption/route
handling. Single source of truth for playback state. No networking inside
— talks to `:core` through the app store. Runs as a foreground
`MediaSessionService` so audio survives backgrounding.

### `AppStore` (`:app`)
Port of the iOS `AppStore` (`@Observable` → StateFlow-based store): auth
status, stations, current station, current track, feedback state, lyrics
state, errors. Composes `:core` + `PlaybackService` connection. The iOS
intent-enum pattern becomes suspend functions / intent methods — keep the
seams.

### `UI` (`:app`, Compose only)
No networking, no business logic, no direct ExoPlayer calls. Views observe
the store's StateFlows and dispatch intents. **Design source of truth is
the iOS view files** — port view-for-view:
`LoginView`, `MainTabView`, `StationsView`, `RadioView`, `RecentsView`,
`NowPlayingView`, `MiniPlayer`, `FeedbackBar`, `LyricsPanel`,
`SettingsView`, `DiagnosticsView`, `AboutView`, `AddStationView`,
`BrowseStationsSheet`, `TuneStationSheet`, `RadioStationFormSheet`,
`SleepTimerSheet`, `Theme`, `TransportGlyph` (draw glyphs with Canvas),
`CachedArtwork`, `PlayerScrubber`, `RadioStationArtwork`, `ArtworkPaletteService`.
The iOS app's accent color, dark palette and layout metrics are the spec.

### `Persistence` (`:app`)
Keystore-wrapper port of `KeychainStore`/`KeychainSessionStore`;
`PreferencesStore`/`HistoryStore`/`ArtworkCache` ports (JSON files in
`filesDir`, no secrets). No secrets in plain storage — ever.

---

## The Pandora Protocol

Do NOT guess endpoints. The iOS repo already derived the exact protocol
from `libpiano`, `pithos.pandora`, `pydora`, `gopiano` — **port it, do not
re-derive it.** The constants file (`PandoraAPI.swift`) carries the
`android-generic` partner credentials, the two Blowfish keys, and the
additional-audio-URL list; copy them verbatim. Semantics that must survive
the port exactly:

- Zero-pad only a partial final 8-byte chunk (never a full extra block),
  Blowfish-ECB, lowercase hex wire form, `syncTime[4:14]` slicing.
- Query-string order partner_id, user_id, auth_token, method; strict
  RFC 3986 encoding of query values (Kotlin's `URLEncoder` is
  form-encoding — write the strict encoder).
- `auth.partnerLogin` plain JSON body; everything after Blowfish-hex.
- Session expiry (fault 1001) → transparent re-login, replay the failed
  call exactly once, then surface a typed error.
- Respect skip limits and rate limits. Never hammer the API.
- Map Pandora error codes → typed Kotlin exceptions. Never crash on API
  errors.

When in doubt about any protocol detail, read the iOS implementation —
it is the authority for this port.

---

## Feature Set (target: parity with the iOS app today)

- Email/password login, Keystore persistence, auto-login on launch.
- Station list with quick-mix, sorting, artwork, explicit-content badge,
  reorder.
- Play / pause / next, skip-limit awareness shown in UI.
- Thumbs up / down with optional "why" reasons; sleep-song.
- Bookmark artist and song; view and delete bookmarks.
- Create station from artist/song; rename; delete; genre stations.
- Now Playing screen: artwork, title, artist, station, progress, skips —
  iOS layout.
- Mini-player above the tab bar; expandable full player.
- Background playback (`mediaPlayback` foreground service) + media
  notification, lock-screen controls, headset/Bluetooth buttons.
- Song history ("recently played") with rating from history.
- Lyrics: lrclib sync/plain lyrics, English translation, fallbacks,
  romanization.
- Radio mode: radio-browser.info directory (popular/countries/tags/
  search), favorites, recents, custom stream stations, ICY now-playing
  identification with live-lag scheduling.
- Last.fm scrobbling with its error/re-auth handling.
- Sleep timer. Themes (dark-first, iOS palette). Diagnostics screen with
  log tail and `last-crash.txt`. About screen.
- Error, empty, and offline states with clear retry UX — same copy as iOS
  where sensible.
- Deprioritized (iOS has it, Android routes differently): an AirPlay-style
  custom output picker. System output switching first; revisit last.

---

## Audio Quality Requirements

- Audio focus gained when playback starts; transient-loss pause/resume;
  handle noisy-unplug and route changes like iOS handles interruptions.
- Request the highest audio quality the API offers. Never downgrade.
- Prefetch the next track as soon as the current one starts (ExoPlayer
  queue of current + next mirrors the iOS `AVQueuePlayer` pattern).
- Buffer tuning (`setBufferDurationsMs` / DefaultLoadControl) for fast
  start + smooth playback; keep the radio live-edge lag behavior
  (`liveStreamLag`) for the radio mode.
- No post-processing audio unless explicitly requested.

---

## Performance Requirements

- No blocking work on the main thread. Networking/decoding off the main
  dispatcher; StateFlow updates on main.
- Compose: `LazyColumn`/`LazyRow`, stable `key()`s, minimal
  recomposition, no per-frame artwork recomputation.
- Cache decoded artwork (memory + disk via Coil); async image loading
  with cancellation; bounded decode sizes.
- Warm-launch-to-usable-station-list under ~1s with a cached session.
- Any regression caught in review must be fixed in the same unit.

---

## Design Requirements (iOS look, translated to Compose)

- **Dark-first, modern, music-forward — matching the iOS app.** Large
  artwork, clear hierarchy, the iOS accent color and dark palette.
- Persistent mini-player above the tab bar; expandable full player with
  the same transition feel.
- Native-feeling interactions: haptics on rating/skip, spring animations,
  edge-to-edge discipline with proper insets.
- Accessibility: scalable text, contentDescription labels, 48dp+ touch
  targets.
- Polish is welcome — every animation is measured against Priority 2.

---

## Engineering Rules (do not violate)

- The tree must build green locally (`./gradlew :core:test` +
  `./gradlew :app:assembleDebug`) at the end of every unit, and each unit
  ends with a commit.
- No `!!` outside tests, no force-fail paths, no `error("TODO")` in
  shipping paths, no user-reachable stubs. Either implement or explicitly
  list as out of scope.
- Never log credentials, auth tokens, or full stream URLs containing
  tokens.
- Unit tests required for (ported from the iOS suite, fixture JSON
  included): Blowfish known-answer vectors, URL/param building, model
  decoding from captured fixtures, session-expiry retry, skip accounting
  — and every other fixture test that exists on the iOS side. The target
  is the full 379-test suite green in `:core`.
- Keep `:core` Android-free. If you reach for `android.*` or OkHttp in
  `:core`, stop.
- Ask before adding a dependency, changing architecture, or adding scope
  outside this document.

---

## Increment Discipline

- A "unit" is the smallest slice that leaves `:core:test` green AND
  `:app:assembleDebug` green. One unit = one commit, pushed immediately
  (`QUEUE.md` updated in the same commit), which fires the GitHub build.
- Work one unit at a time. Do NOT bundle two features into one unit
  "because they're related". Commit one, push, start the next immediately
  — the GitHub build runs in the background while you work.
- Before starting any unit: one quick `gh run list --limit 5` (non-blocking).
  A FAILED run jumps to the top of the queue; an in-progress run is ignored.
- When in doubt about scope, ask the user. A quick clarification costs
  seconds; a wrong big increment costs a day.

---

## Versioning — Do Not Bump

- `versionName` stays `0.1.0`, `versionCode` stays `1` (matching the iOS
  `CFBundleShortVersionString` 0.1.0 / build 1).
- No semantic version tags. No milestone / commit / README language
  implying the app has "reached" a version. No progress percentages or
  "feature parity reached" labels.
- Progress is tracked ONLY by `QUEUE.md` and the milestone list below.
- The app is pre-release until the user explicitly says otherwise —
  which happens only after the Definition of Done below is demonstrated
  on a real device.

---

## Delivery Milestones (work in this order; one unit at a time)

- **A0** — Repo skeleton: Gradle project (`:core` + `:app`), Compose
  `MainActivity` placeholder, manifest (cleartext traffic, media playback
  service), app icon, `AGENTS.md` + `QUEUE.md`. Green local build.
- **A1** — Core protocol port: all 33 `PandoraCore` sources + the full
  fixture test suite green in `:core` (target: all 379 tests).
- **A2** — Auth: Keystore session store, `SessionManager` wiring,
  auto-login, login screen matching iOS `LoginView`.
- **A3** — Stations: fetch/list/reorder/create/rename/delete/search/
  genres, `MainTabView` + `StationsView` + sheets, preferences store.
- **A4** — Playback: playlist fetch, ExoPlayer + `MediaSessionService`,
  media notification, prefetch, interruption handling, artwork cache,
  mini-player + `NowPlayingView`. **"It works" milestone.**
- **A5** — Feedback & bookmarks & history: thumbs with reasons, sleep-song,
  bookmarks, recents, sleep timer, skip-limit UX.
- **A6** — Lyrics: lrclib + translation + fallbacks + romanization,
  `LyricsPanel`.
- **A7** — Radio mode: `RadioBrowser` UI, favorites/recents/custom
  stations, ICY probe + live-lag scheduling.
- **A8** — Last.fm scrobbling + Settings + Diagnostics + themes + crash
  handler.
- **A9** — Robustness & parity pass: session-expiry soak, offline states,
  accessibility, output switching, device demo checklist.

Do not advance to the next milestone until the user has confirmed the
current one works on their phone, OR explicitly instructs you to keep
moving while they test in parallel. When in doubt, keep building the
current milestone — there's always another unit — but do not begin the
next milestone's work.

---

## Definition of Done (pre-release gate)

All of the following work on a real phone (and emulator for smoke tests):
launch → auto-login → station list → tap → music plays within ~2s →
media notification with metadata → thumbs up/down → skip → next-track
prefetch → survives backgrounding, interruption, and session expiry →
no crashes. Until that demo exists, everything is pre-release. No version
numbers.

---

## Working Style & Communication

- **Start of every session**: read `QUEUE.md`, restate the current
  milestone, list the pending queue, and state which unit you're
  beginning. Then begin.
- **No subagents, ever.** Direct tools only.
- **Commit fast, commit often.** Small commits, one concern each, at
  every green unit. Push right after committing. `QUEUE.md` rides along
  in the same commit.
- **Never end a session waiting on CI.** End it with the next unit
  started — the GitHub build drains in the background.
- **The user is not a blocker.** A green GitHub build on the latest
  commit is the unit's confirmation of success. User feedback, when it
  arrives, jumps the queue; until then, keep working.
- **When the user sends feedback**: acknowledge, add it to the top of
  `QUEUE.md` under "User Feedback Pending", address it before continuing.
- **On build failure** (local or GitHub): read the failed line, fix the
  specific error, re-run, commit, push, continue. Never let a red build
  accumulate other changes.
- **On ambiguity in the protocol or the design**: say so, cite the iOS
  file or reference implementation, and never invent behavior.
- **Prefer small, verifiable increments.** Show the `:core:test` output
  and the commit SHA. Never claim a UI change "works" — only the user
  can confirm that on their phone.

---

## Final Reminders

- No subagents. Commit fast, commit often, push, and keep moving — the
  green GitHub build is your confirmation of success, not the user's
  reply.
- Never wait on a build. Push and continue.
- Never modify `AGENTS.md`. Propose changes in chat instead.
- Always keep `QUEUE.md` accurate — it is your memory across sessions.
- Never bump a version.
- Never bundle unrelated features into one unit.
- Never put Android or UI code in `:core`, and never put networking in a
  Composable.
- Never guess the Pandora protocol — port it from the iOS repo, which
  derived it from the references.
- Never claim something works until the user has tested it on their phone.
- The UI's spec is the iOS app. Read the SwiftUI file before porting any
  view.

Begin with A0. Scaffold the Gradle project, then proceed unit by unit.
