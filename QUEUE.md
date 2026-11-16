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

**A7** — radio mode (RadioBrowser UI, favorites/recents/custom stations,
ICY probe + live-lag scheduling). User directive 2026-11-17: "start from
A7" (the keep-moving instruction AGENTS.md asks for). A6 landed
code-complete and CI-green before it (A6.1–A6.2); A5 (A5.1–A5.6) and A4
before that. The phone demo checklists (A4 + A5 + A6, below) are still
owed. Unit plan: A7.1 discovery controller + data layer → A7.2 playback
wiring → A7.3 RadioView UI → A7.4 ICY probe + identification → A7.5
player radio surfaces + parity pass.

## Queue (pending, in order)

- **A6 verification (user)**: play a song → tap the quote button in
  the full player → synced lyrics scroll with playback, tap a line
  seeks; foreign song → auto/manual Translate ("Translated"/"Already
  in English"/failure with Retry); a non-Latin song shows the
  romanization reading line; a song with no lyrics shows "No lyrics"
  + Retry.
- **A5 verification (user)**: thumbs up/down on the full player (tap
  the active one to undo), the player ⋯ menu (Sleep Timer… incl. the
  live label, Tune Station…, Bookmark Song/Artist → "Song/Artist
  bookmarked" capsule, Hide This Song → skips ahead), the Home ⏱
  button → Recents sheet (segment pill, rate a past track, relative
  times, delete a bookmark), sleep timer pausing at the deadline /
  end of track, skip-limit badge + "Skip limit reached" row caption.
- **A6 verification (user)**: play a song → tap the quote button in
  the full player → synced lyrics scroll with playback, tap a line
  seeks; foreign song → auto/manual Translate ("Translated"/"Already
  in English"/failure with Retry); a non-Latin song shows the
  romanization reading line; a song with no lyrics shows "No lyrics"
  + Retry.
- **A4 verification (user)**: install the CI APK artifact (or
  Waydroid once unblocked) and run the demo checklist — launch →
  auto-login → station list → tap a station → audio within ~2s →
  media notification with metadata → lock-screen controls → skip →
  next-track prefetch (Up Next fills) → pause/resume → mini-player →
  full player (scrubber, volume, quality chip, artwork ambient
  theme, **thumbs up/down now too**) → headphones unplug pauses →
  no crashes. Report failures; silence is not a blocker.
- **BLOCKED on user action — Waydroid smoke test**: the container
  service needs root (`sudo systemctl start waydroid-container`, or
  however they normally start Waydroid); agent-side `sudo` requires a
  password. Once the container runs, resume: `waydroid session start`
  → `adb connect` (or `waydroid app install Amphora-debug.apk`) →
  launch → login screen → wrong-password error copy → real login →
  signed-in → station list renders → then the A4 checklist above.
  Per user directive (2026-11-16): Waydroid ONLY, never an emulator.
- **A3/A2 verification owed** (user phone): wrong-password copy,
  station list, reorder, create/rename/delete, tune sheet.
- **A5 — feedback / bookmarks / history / sleep timer** (next
  milestone, gated on A4 confirmation): thumbs with reasons
  (FeedbackBar), sleep-song, bookmarks, RecentsView + the Home
  toolbar button, sleep timer sheet, skip-limit UX polish, the
  onTrackChanged observers documented in AmphoraStore (rating reset,
  history record) + NowPlayingView's hidden menu items.
- **BLOCKED on user action — Waydroid smoke test**: the container
  service needs root (`sudo systemctl start waydroid-container`, or
  however they normally start Waydroid); agent-side `sudo` requires a
  password. Once the container runs, resume: `waydroid session start`
  → `adb connect` (or `waydroid app install Amphora-debug.apk`) →
  launch → login screen → wrong-password error copy → real login →
  signed-in → station list renders. Per user directive (2026-11-16):
  Waydroid ONLY, never an emulator.
- **A3 verification**: the user tests on their phone via the CI APK
  artifact in parallel with A4 (explicit "keep moving" received).
- **A3 shipped-deltas** (each documented in its file header):
  row taps start playback — A4 wiring (rows are long-press-only until
  then, so no dead tap); recents toolbar button — A5 (with
  `RecentsView`); Radio/Settings tabs show quiet named shells until
  A7/A8; mini-player slot — A4.
- **Porting notes learned in A3.2** (m3 1.3.2 + coil 3.1.0):
  `NavigationBarItem` is a `RowScope` extension (call inside
  `NavigationBar { }`); `PullToRefreshBox` lives in
  `androidx.compose.material3.pulltorefresh`; coil's
  `SubcomposeAsyncImage` wants the `loading`/`error`/`success` slot
  overload (matching `painter.state` inside the scope confuses K2's
  typing); `SleepTimer()` is private — use `SleepTimer.off`.
- **Porting notes learned in A4.1** (media3 1.8.0): the transition
  constant is `Player.MEDIA_ITEM_TRANSITION_REASON_AUTO` (not
  `…_TRANSITION_AUTO`); a constructor param must be `private val` to
  be visible in methods; `MediaMetadata.setArtworkUri` wants `Uri?`,
  not `String?`.
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

- 2026-11-17 — **A7.4 landed — ICY identification**: `player/RadioMetadataProbe.kt`
  (port of RadioMetadataProbe.swift: its own connection with
  `Icy-MetaData: 1`, FORCE_NETWORK; the core ICYMetadataParser fed 4KB
  chunks; repeat-title dedupe; Listening/Announced/AnnouncesNothing
  events; readTimeout 20s + callTimeout disabled — the iOS request-20s/
  resource-24h port; stop() cancels the call so the blocked read
  unblocks; error *type* logged, never the message — stream URLs must
  never reach the log) + the store grows RadioIdentification
  (inactive/identifying/identified/stationAnnouncesNothing) +
  `radioNowPlaying`/`radioSongArtwork`/`isIdentifyingRadioSong` +
  startRadioIdentification (main-hop delivery with the station-uuid
  identity check) / stopRadioIdentification / handleRadioMetadata /
  scheduleRadioAnnouncement (the live-lag wait: lag > 1.5 s delays
  apply by `PlaybackClient.liveStreamLag`, newer announcements cancel
  the waiting one, identity re-checked at application time — the
  2026-11-12 "lyrics ahead of the audio" fix) / applyRadioAnnouncement /
  identifyRadioSong (synthetic `radio-song.<uuid>.<rawTitle>` Track —
  never reaches the player — feeds loadLyrics + its cache; the iTunes
  artwork lookup via ITunesSongArtworkClient, cancelled-lookup guard) /
  identifyRadioSongIntent (the Identify button: with announcement =
  re-lookup, without = probe restart). Lyrics panel ownership follows
  the synthetic song: isCurrentLyricsToken + currentLyricsTrack now
  radio-aware; RefreshLyrics/TranslateLyrics dispatch through
  currentLyricsTrack. Wiring: playRadio starts the probe, stopRadio /
  play(station) hand-over / record()'s session-dead path stop it.
  Port delta: OkHttp `Response.use` is not inline, so the read loop
  checks its own captured Job instead of the suspend `isActive`
  (documented in the probe). Local gate green: `:core:test` +
  `:app:assembleDebug`.

- 2026-11-17 — **A7.3 landed — the Radio tab** (`ui/RadioView.kt` +
  `ui/RadioStationArtwork.kt` + `ui/RadioStationFormSheet.kt`, the
  MainTabView Radio slot swapped in): RadioView (port of RadioView.swift:
  large title + add button + the `.searchable` field bound to
  `radio.searchText` (the controller owns the 400ms debounce; non-empty
  swaps the root for results); home list = hero (radioRecents.first, up
  to 240dp tile, "Now playing/Last played", 58dp accent play/pause with
  the press-dip — the Home hero's radio copy) + My Stations (long-press
  Edit/Delete — iOS contextMenu) + Recently Played (Clear in the
  header, long-press Remove — iOS swipe action) + Favorites +
  Discover (Popular/Countries/Tags with the 44dp accent tiles) +
  `RadioResultsBody` (results/loading/error+Retry/empty states, shared
  by the search root and the pushed lists; the "Show More" paging
  footer — spinner/more-error+Retry/chevron-down button, deduped by
  the controller) + the NavigationStack pushes become local pushed
  state + BackHandler (Popular / Countries → country drill-in / Tags →
  tag drill-in, `LaunchedEffect(mode)` = iOS `.onAppear`) +
  `RadioStationRow` (favicon-or-tile 44dp, accent name when current,
  caption, playing glyph, the nested 44dp star toggle — takes its own
  taps) + form sheet (Name + Stream URL fields, URI keyboard, the
  footnote copy, Cancel/Save with the http(s) `java.net.URI`
  validation, edit prefill) + `RadioStationArtwork` (generated
  initials-over-hue-gradient tile always underneath; the real favicon
  wins when ≥ minimumArtworkPixels — Coil ORIGINAL-decode through the
  shared loader, the ArtworkPaletteService idiom; + `radioTileBitmap`
  for the notification artwork, wired in A7.5). Icon ports:
  antenna→Radio (no Antennas glyph in the rounded set), chart→TrendingUp,
  globe→Public, tag→Sell, star→Star/StarOutline. Local gate green:
  `:core:test` + `:app:assembleDebug`.

- 2026-11-17 — **A7.2 landed — radio playback wiring**: `PlaybackClient`
  grows `playLiveStream(track)` (one slot, no prefetch, duration 0 —
  the scrubber reads it as live; the A4 clear-fires-null contract kept,
  the radio item's own transition adopts the stream) +
  `liveStreamLag` (bufferedPosition − currentPosition, 0 when not
  meaningful, capped 45 s — the iOS loadedTimeRanges port, delta
  documented) + the store: `currentRadioStation` state, intents
  PlayRadioStation/StopRadio, `playRadio` (toggle-on-same; clears the
  Pandora hand-over — currentStation/queuedTrackTokens/prefetch retry/
  stationModes; recordRadioRecent; playLiveStream; the click-ping via
  registerClick skipped for custom stations) + `stopRadio` (forgets the
  station, clears lyrics, player.stop) + `recordRadioRecent` (dedupe →
  top → 20-cap) + the A7.1 seams completed (update/delete custom
  station follow the playing snapshot; delete stops playback) + the
  radio guards: play(station) clears currentRadioStation (the player
  is singular), skipToNext stops the stream, history record +
  rateCurrentTrack + loadLyrics skip `isRadio` tracks, record()'s
  session-dead path clears the radio station. Port delta: iOS's
  "lost player slot" guard has no Android counterpart (every queue-
  empty path reports the nil transition; documented in playRadio's
  header). Identification (probe + announcements + lag scheduling) is
  A7.4 — explicitly scoped. Local gate green: `:core:test` +
  `:app:assembleDebug`.

- 2026-11-17 — **A7.1 landed — RadioController + the store's radio
  data layer** (user said "start from A7"): `store/RadioController.kt`
  (port of RadioController.swift: the Mode sealed class —
  Popular/Search/Country/Tag with `title`; results + didLoadResults +
  isLoading + errorMessage + canLoadMore + isLoadingMore +
  moreErrorMessage; 50-per-page "Show More" with uuid-dedupe across
  page boundaries; fetch-once countries/tags catalogs with
  retryX clearing; the 400 ms search debounce via a setter that
  cancels + relaunches; the shared run() — a newer call cancels the
  older one, cancelled tasks never touch state; the RadioBrowserError
  → user-copy port) + the store grows `radioFavorites`/`radioRecents`/
  `radioCustomStations` (loaded from preferences at init — device
  data) + helpers isRadioFavorite/isRadioCustom + intents
  ToggleRadioFavorite (insert-at-0 then the name sort — the
  localizedCaseInsensitiveCompare port, lowercase delta documented) /
  RemoveRadioRecent / ClearRadioRecents / Add/Update/Delete
  RadioCustomStation (update follows the new identity through
  favorites + recents; delete removes it everywhere so stale entries
  never 404). Store rename: the `RadioBrowserClient` val `radio` →
  private `radioClient`; `val radio` is now the RadioController (iOS's
  exact name). Documented A7.2 seams: update/delete's playing-snapshot
  lines + recordRadioRecent. Local gate green: `:core:test` +
  `:app:assembleDebug`.

- 2026-11-17 — **A6.2 landed — LyricsPanel + the player lyrics
  toggle, A6 code-complete**: `ui/LyricsPanel.kt` (port of
  LyricsPanel.swift: the translation bar — "Translating…" spinner /
  checkmark "Translated" in the artwork accent / "Already in English" /
  yellow failure message with a **Translate**-→-**Retry** rounded
  accent-wash button; synced lyrics — LazyColumn of lines, active line
  highlighted white/accent, tap-a-line seeks, romanization reading
  line over the original script with the translation beneath,
  lineLimit 3; auto-scroll via animateScrollToItem on activeIndex
  change + a 150ms landing scroll on arrival (iOS's `.task`); plain
  lyrics with the translation under a divider; Instrumental and
  "No lyrics" + Retry states) + NowPlayingView grows the lyrics
  toolbar toggle (quote glyph, accent-tinted while shown,
  "Show/Hide lyrics" description) and swaps the artwork/header/
  scrubber region for the panel with the transport fixed below
  (iOS's VStack branch). Port deltas: SF quote.bubble(.fill) → one
  Material FormatQuote tinted; ScrollViewReader center-anchor →
  animateScrollToItem; `.bordered`+`.tint` → rounded accent-wash
  button. Local gate green: `:core:test` + `:app:assembleDebug`.
- 2026-11-17 — **A6.1 landed — store lyrics section** (user said
  keep moving into A6; port of AppStore's Lyrics section, radio
  branch documented as the A7 seam): `AmphoraStore` grows
  `lyrics`/`isLoadingLyrics`/`lyricsTranslationState` (the
  LyricsTranslationState sealed class — None/Translating/Translated/
  AlreadyEnglish/Failed(message)) + the per-trackToken lyrics cache
  ("not found" cached as `Lyrics.empty`) + intents RefreshLyrics
  (force reload of the current track) / TranslateLyrics (manual
  pass). The onTrackChanged observer loads lyrics after the
  sleep-timer fire (iOS order); ads excluded, radio guard lands A7.
  `loadLyrics` cancels the in-flight fetch + translation +
  romanization on every call; superseded fetches never touch state
  (CancellationException rethrow + isActive + isCurrentLyricsToken
  guards — the port of iOS's `!Task.isCancelled` checks).
  `requestTranslation` (auto on arrival + manual button; synced lines
  or plain text split on \n) → `applyTranslation` via the core's
  alignment guard; `requestRomanization` → `applyRomanization`; each
  rebuilds from the cache entry so the second arrival keeps the
  first's field. Clients wired: `LyricsProviderChain.defaultChain` +
  one `GoogleTranslateClient` serving both the translator and
  romanizer seams (iOS injects two — both stateless; noted). Port
  delta: iOS AppLog.debug → app AppLog.info (no debug level on the
  app side; the diagnostics tail is A8). Local gate green:
  `:core:test` + `:app:assembleDebug`.
- 2026-11-17 — **A5.6 landed — parity verification pass, A5
  code-complete**: skip-limit surface checked line-for-line against
  iOS — store (`isSkipLimitReached`/`skipLimitMessage`/
  `isSkipLimited`/`handleSkipLimit` incl. the "playlist end"/"skip
  refused" log split) matches; player badge (SKIP LIMIT capsule +
  secondary message) matches; station-row "Skip limit reached"
  caption (iOS badgeCaption order: skip limit → Quick mix →
  Thumbprint) already ported. `liveBadge` is radio-only (A7) — not an
  A5 gap. Core `PlayHistory.record` parity confirmed (keeps a
  user-given rating on re-listen; does not seed from `songRating` —
  iOS identical). Port-delta notes: iOS `rateCurrentTrack`'s
  currentRadioStation guard is the A7 seam; A8's clearHistory/
  clearArtworkCache intents belong to Settings (their state layers —
  history, artwork cache — exist already). No code deltas needed;
  A5.1–A5.6 complete. Local gate green.
- 2026-11-17 — **A5.5 landed — RecentsView + the Home toolbar
  button**: `ui/RecentsView.kt` (port of RecentsView.swift: the soft
  capsule segment picker — Recents/Bookmarks, 250ms fill lift; the
  history half — empty state "Nothing played yet", rows with 44dp
  artwork, title/artist, the two stacked rating glyph buttons (30×24,
  accent when active, tap toggles off, disabled while that token's
  rating is in flight or when the entry's station is gone — opacity
  0.4), relative time via DateUtils (the `.relative` style port);
  the bookmarks half — loading/empty states, Songs/Artists sections,
  rows with kind-aware fallback glyphs (MusicNote/Mic) + destructive
  delete, refresh-on-open (`refreshBookmarks` from LaunchedEffect =
  iOS's `.task`)) + StationsView grows the toolbar clock button (iOS
  order: recents, add, reorder) + the sheet host. Local gate green:
  `:core:test` + `:app:assembleDebug`.
- 2026-11-17 — **A5.4 landed — history**: `persistence/HistoryStore.kt`
  (JSON file in filesDir, fail-closed load, atomic best-effort save —
  the PreferencesStore pattern; the core `PlayHistory` JSON round-trip
  is already fixture-tested) + `AmphoraStore` gains `history` +
  `ratingTrackTokens` + intents RateHistoryTrack/RemoveHistoryTrackRating
  (remove maps to `.none`, exactly iOS's dispatch). The onTrackChanged
  observer records the play (ads excluded; the radio guard lands with
  A7) right after the rating reset, iOS order. Port delta: Swift's
  mutating struct methods re-trigger `@Observable`; the Kotlin
  `PlayHistory` mutates in place, so each call site does
  `history = history.copy().also { it.record/setRating(...) }` before
  saving — swap-in-a-copy so Compose sees the change. History is
  device data: it survives sign-out and relaunch (loaded in init; iOS
  keeps it on sign-out too). Local gate green: `:core:test` +
  `:app:assembleDebug`.
- 2026-11-17 — **A5.3 landed — bookmarks + sleep-song + the player
  menu items**: `AmphoraStore` grows `bookmarks`/`isLoadingBookmarks`
  + intents SleepCurrentTrack / BookmarkCurrentSong /
  BookmarkCurrentArtist / RefreshBookmarks / DeleteBookmark
  (`sleepCurrentTrack` = user.sleepSong then the player's skip — iOS
  drives the player directly there, the ad guard precedes it;
  bookmark adds refresh the list + "Song/Artist bookmarked"
  confirmation; `refreshBookmarks` is public for the Recents sheet's
  on-open refresh in A5.5). NowPlayingView's actionsMenu grows the
  iOS non-radio branch: Bookmark Song (Bookmark), Bookmark Artist
  (PersonAdd ≈ person.crop.circle.badge.plus), Hide This Song
  (HearingDisabled ≈ ear.slash), separated by dividers, haptics.light
  per iOS. The radio branch (Identify Song instead of the song-level
  items) arrives with A7 — documented in the header. Local gate
  green: `:core:test` + `:app:assembleDebug`.
- 2026-11-17 — **A5.2 landed — sleep timer**: `AmphoraStore` grows
  `sleepTimer`/`sleepTimerLabel` (live label refreshed only when the
  visible minute changes — the loop wakes every ≤30 s, not every
  second) + intents SetSleepTimer/CancelSleepTimer (iOS dispatches
  synchronously; port delta: the function is `applySleepTimer` —
  Kotlin generates a `setSleepTimer` setter for the property, JVM
  clash). Duration mode counts down and pauses on expiry;
  end-of-track mode fires in the onTrackChanged observer at the next
  track start (iOS's exact spot); restore-from-preferences in init
  resumes an unexpired duration timer or carries an end-of-track one.
  + `ui/SleepTimerSheet.kt` (5 duration rows + End of current track +
  destructive Cancel timer when set, card-row language, Bedtime /
  SkipNext icon ports of moon.zzz / forward.end) hosted as a sheet
  from the player menu + the "Sleep Timer…"/"Sleep Timer (label)…"
  menu item (Bedtime icon) — the app's only sleep-timer entry, one
  moon, no duplicate. Local gate green: `:core:test` +
  `:app:assembleDebug`.
- 2026-11-17 — **A5.1 landed — feedback: store surface + FeedbackBar**
  (user said keep moving into A5 in the same message that pointed at
  the handover): `AmphoraStore` grows `currentRating` +
  `currentFeedbackId` (private; reset per track in the onTrackChanged
  observer — pithos keeps the id per song) + intents
  RateCurrentTrack/RemoveCurrentRating (`rateCurrentTrack` deletes the
  previous feedback first, then adds — pithos `Song.rate`;
  `removeCurrentRating` needs the id, without it there is nothing to
  undo); rating comes from the playlist entry (`songRating`) via the
  `feedbackRatingFor` port of AppStore's static helper. +
  `ui/FeedbackBar.kt` (52dp glyphs 64 apart, accent when active, tap
  toggles off, haptics.light, Crossfade glyph swap, "On"/"Off"
  stateDescription) slotted into PlayerControls between volume and Up
  Next (iOS order), hidden for ads (the radio guard arrives with A7).
  `Theme.kt` grows the shared `PressableIconButton` (the
  PressableButtonStyle-as-icon-button port; NowPlayingView's private
  copy deleted — transport + FeedbackBar + upcoming Recents rows share
  it). Note: iOS has no "why" reason UI (grep empty; the legacy v5 API
  carries no reason param — core Feedback.kt documents it), so the
  port is plain thumbs like iOS. Icon pair: Material
  ThumbUpOffAlt/ThumbUp (no *Outlined variants in the rounded set).
  Local gate green: `:core:test` + `:app:assembleDebug`.
- 2026-11-16 — **A4.5 landed — robustness pass, A4 code-complete**:
  `PlaybackService` grows a named notification channel ("playback" /
  "Playback" — the provider builder takes a @StringRes channel name,
  noted) + RootView requests POST_NOTIFICATIONS once per signed-in
  session on Android 13+ (targetSdk 36 gates the media notification
  behind it; the launcher no-ops on grant/permanent denial — no prefs
  flag needed; iOS has no equivalent, documented delta). Session-expiry
  during playlist fetch was already covered by A4.2's record() path
  (dead session → player.stop + return to sign-in). Local gate green:
  `:core:test` + `:app:assembleDebug`.
- 2026-11-16 — **A4.4 landed — the player UI** (MiniPlayer +
  NowPlayingView + PlayerScrubber + the ambient theme, ported
  view-for-view): `ui/MiniPlayer.kt` (card with the top-edge progress
  hairline — isolated flow reads so the per-tick elapsed redraws only
  the 2.5 dp strip; artwork, title/artist, 34dp accent play/pause
  circle, skip; onOpen reports to MainTabView) + `ui/MainTabView.kt`
  slot (between content and tab bar = real layout space — the *better*
  behavior iOS chased in 2026-11-11: lists never scroll under the
  card; one ModalBottomSheet owns NowPlayingView for the whole shell)
  + `ui/NowPlayingView.kt` (ambient playerBackground from the artwork
  palette + AmbientGlow ellipse, artwork with the double shadow,
  left-aligned title/artist/album/station header + quality chip,
  scrubber + time labels, ad badge, SKIP LIMIT badge + message,
  transport (back15 / 72dp accent circle / next) with the press-dip,
  volume slider (live drag, disk write on release — the port of the
  draftVolume pattern), pithos-style Up Next disclosure, "Tuned to …"
  confirmation capsule, error text, Nothing Playing state; Tune
  Station… menu item → TuneStationSheet(station = nil) works today;
  FeedbackBar/lyrics/radio surfaces documented as A5/A6/A7)
  + `ui/PlayerScrubber.kt` (Canvas capsule + drag knob, snap-while-
  dragging / 200ms glide otherwise, TalkBack ±10s custom actions)
  + `player/PlaybackClient.kt` grows `upcomingTracks` + `volume`
  StateFlows (the Up Next list + the volume slider's source)
  + `player/ArtworkPaletteService.kt` + `store/ArtworkThemeController.kt`
  (the 32×32 RGBA8 sample → `ArtworkColors.extract` port; Coil shared
  loader cache; palette memo cache 24 cap + request counter;
  attached in RootView like iOS) + `support/TimeFormat.kt`.
  Local gate green: `:core:test` + `:app:assembleDebug`.
- 2026-11-16 — **A4.3 landed — the play surface lights up**:
  StationsView rows send `.playStation` (iOS Button+contextMenu →
  `RowPressableButton` with onClick play + onLongClick menu — which
  also FIXED a latent A3 gap: `menuOpen` was never set true, the
  long-press menu had no opener; noted in the A3.2 log claim) +
  `rememberHaptics().light()` on taps (the port of Haptics.light()) +
  hero play button now sends `.playStation` with the accessibility
  label "Play/Pause <station>" (iOS had it; the A3 port omitted it —
  the store's play() turns the tap on the playing station into a
  pause). Local gate green: `:core:test` + `:app:assembleDebug`.
- 2026-11-16 — **A4.2 landed — store playback surface** (port of
  AppStore's Playback section): store owns `val player` (PlaybackClient);
  intents PlayStation/TogglePlayback/SkipToNext/SetPlaybackVolume;
  `play(station)` — already-playing toggles instead of restarting,
  playlist fetch + `streamURL != null` playable filter, "No playable
  tracks" copy, currentStation set + cached-modes invalidation,
  `queuedTrackTokens` dedupe set, `lastStationId` persist (hero lights
  up), skip-limit clear on replay, `player.play(first, getOrNull(1))`
  (the current+next prefetch); `fetchMoreTracks` on need-more + fresh
  filter + 5/15/30 s `schedulePrefetchRetry` (max 3); `handleSkipLimit`
  (skipLimitStationId + station-name copy + "stops asking", cancel
  retry); ad-skip refusal copy; `prepareAdIfNeeded` (resolve + register
  impression, best-effort, tracking-tokens log); `isSkipLimitReached` +
  `skipLimitMessage` helpers; `isPlayingCurrentStation` wired via the
  player-state collector; volume restore from preferences; the A3.5
  mode-change seam completed — playing-station branch now shows
  `showConfirmation("Tuned to …")` (3 s auto-clear) +
  `restartWithNewPlaylist`; session-dead path stops the player +
  clears the skip-limit mark. onTrackChanged observers: log +
  prepareAd (history/lyrics/scrobbler observers documented as A5/A6/A8).
  Local gate green: `:core:test` + `:app:assembleDebug`.
- 2026-11-16 — **A4.1 landed — player infrastructure**:
  `player/PlaybackService.kt` (Media3 port of PlayerService +
  NowPlayingService unified: ExoPlayer with audio-focus interruption
  handling + noisy-unplug pause + 30/60 s buffer tuning (the iOS
  preferredForwardBufferDuration=30 port) + wake-mode network + ±15 s
  seek increments (the lock-screen skip±15 port); MediaSession with a
  session-activity intent; default media notification via
  MediaSessionService; onTaskRemoved stops the dead service) +
  `player/PlaybackClient.kt` (the PlayerService API surface behind a
  MediaController: same names — play/enqueue/skipToNext/pause/resume/
  togglePlayback/seek/skip±/stop/setVolume/clearError; StateFlow ports
  of the @Observable state; observer-list ports of onTrackChanged/
  onTrackFinished/onTrackEnded/onNeedMoreTracks/onPlaybackFailed with
  the iOS ordering contract (finish before track-changed); 0.5 s
  elapsed ticker; trackToken→Track resolution map; connect retry ×3 →
  fail("Couldn't start audio output."), never a crash) + manifest
  service entry (exported, mediaPlayback foreground type). Port deltas
  in the file headers: commands fire-and-forget on one main dispatcher
  (IPC ordering preserved), resetQueue's pause-then-drop unneeded
  (clearMediaItems drops the playing item + fires the null transition,
  which is where the outgoing track is reported), interruption/route
  observers are platform-handled. Local gate green: `:core:test` +
  `:app:assembleDebug`. CI verdict pending on push.
- 2026-11-16 — **Waydroid smoke test attempted, blocked on root**:
  Waydroid installed (`/usr/bin/waydroid`, MAINLINE vendor) but
  Session STOPPED; `waydroid session start` cannot run without the
  container service, which needs sudo (password is the user's). Filed
  the blocker; session state recorded here so any session can resume.
- 2026-11-16 — **A3.5 landed — A3 units complete**: store gains the
  tuning surface (`stationModes`/`isLoadingModes`/`isChangingMode`/
  `stationModeChangedTick` + private `modesStationId`, intents
  LoadStationModes/SetStationMode; previousModeID only when the cached
  modes describe the same station; the playing-station restart +
  confirmation toast is A4 wiring, documented in the store) +
  `ui/TuneStationSheet.kt` (mode rows with Premium capsule +
  checkmark, selectability via `isSelectable(isSubscriber)`, footer
  "Changing modes starts a new song."/"Changing mode…", inline error
  section, no-options/error/loading states, tick-dismiss) + the Tune
  Station… context item (quickmix-guarded) + its sheet host. Local
  gate green: 372/372 + APK. **A3 milestone code-complete** — CI
  verdict pending on this push, then Waydroid smoke test.
- 2026-11-16 — **A3.4 landed**: store gains the search/create surface
  (`searchResults`/`isSearching`/`genreCategories`/`isLoadingGenres`/
  `stationCreatedTick`, intents SearchStations/CreateStation/
  CreateGenreStation/LoadGenreStations; search cancel-supersede via
  Job cancel + CancellationException rethrow, local append deduped by
  id like pithos, tick bump = the sheets' dismiss signal) +
  `ui/AddStationView.kt` (search field with the 350 ms
  LaunchedEffect debounce, ≥2-chars policy, kind icons mic/note/radio,
  song captions from subtitle, plus/spinner on rows, banner +
  prompts) + `ui/BrowseStationsSheet.kt` (category list → internal
  drill-down with BackHandler, tick-dismiss, error/empty states) +
  StationsView grows the toolbar Add button, the Browse Pandora
  Stations row, the empty-state Add button, and the two
  ModalBottomSheet hosts; `RowPressableButton` gains `strong` (the
  fuller `PressableButtonStyle` dip where iOS chose it). Local gate
  green: 372/372 + APK.
- 2026-11-16 — **A3.3 landed**: `ui/TransportGlyph.kt` (full Canvas
  port of the drawn transport family — play/pause/next/stop/back15
  with the rounded-polygon quadratic-corner math one-for-one;
  back15's "15" uses sans-serif-round) + StationsView hero (last-played
  card, up to 240dp, double shadow incl. the accent glow, 58dp accent
  play circle with the drawn glyph + Crossfade swap + press dip —
  play action is A4 wiring; hero is unreachable until A4 populates
  lastStationId, documented in the header) + edit-mode drag reorder
  (toolbar SwapVert toggle → trailing DragHandle grip on rows,
  `detectDragGestures` moves rows one slot at a time through the
  store's MoveStation intent with finger-tracking offset correction +
  `animateItem` placement; iOS auto-scroll past the edge is lift-and-
  repeat here, documented). Local gate green: 372/372 + APK.
- 2026-11-16 — **A3.2 landed**: `ui/MainTabView.kt` (Home/Radio/
  Settings shell, `RowScope.TabItem` with accent tinting; Radio/
  Settings quiet named shells until A7/A8) + `ui/StationsView.kt`
  (large title, loading/empty/error states with Retry, pull-to-refresh
  via `PullToRefreshBox`, explicit-content notice row, station rows
  with artwork/badges/quickmix glyph/shared icon, long-press context
  menu with working Rename + Delete dialogs incl. the quickmix +
  thumbprint guards, non-blocking bottom error banner with dismiss) +
  `ui/CachedArtwork.kt` (Coil 3 port: recessed tile → spinner →
  fallback glyph states) + `support/Haptics.kt` (light/medium port) +
  `Theme.kt` grows `amphoraCardRow` + `RowPressableButton` (the
  Theme.swift list-language ports). `RootView`'s `PlaceholderHome`
  deleted — signed-in now shows the real shell. Local gate green:
  372/372 + APK.
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
