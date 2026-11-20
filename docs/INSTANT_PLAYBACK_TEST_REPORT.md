# Instant Playback — Test Report

**Date:** 2026-11-20 · **Status:** code complete + unit-tested; device runs pending
(static expectations come from `INSTANT_PLAYBACK_ARCHITECTURE_AUDIT.md`; the
numbers in `INSTANT_PLAYBACK_BENCHMARKS.md` are filled in from this matrix).

## Changes under test

| Repo | Change | Purpose |
|---|---|---|
| `echo-youtube-extension-old` | `StreamMediaCache` wired to `FileStreamCacheStore` (disk, expiry-aware, debounced) | warm first tap after app restart |
| `echo-youtube-extension-old` | `StreamPrefetcher.onCurrentTrack` + `onTrackChanged` hook | successor prefetch → instant manual skip |
| `echo-youtube-extension-old` | `[EchoBench]` marks: `streamResolved`, `tokenReady` | measurement |
| `echo-app-src` | `ExoPlayer.PreloadConfiguration(15 s)` (was disabled) | next item's MediaSource prepared during playback → instant skip |
| `echo-app-src` | `DefaultLoadControl` 750/2000 ms buffer-for-playback (was 2500/5000 default) | audio starts ~1.75 s sooner on uncached tracks |
| `echo-app-src` | `StreamableLoader.load` parallel server warm | prepare pays max(RT₁, RT₂) instead of RT₁+RT₂ |
| `echo-app-src` | `PlaybackBench` + `BenchmarkListener` (`playTap`, `mediaSourcePrepared`, `playerReady`, `firstAudibleOutput`, `mediaItemTransition`) | measurement, READY vs audible distinction |

Preserved untouched: PO-token providers, auth/cookies, extraction cascade
order, SimpleCache sizing, download feature, fallback lanes.

## Build artifacts

- Extension jar: `ext/build/libs/youtube-music-v<git-hash>-all.jar` — install
  via Echo → Extensions → Add (or replace the file the host already loaded and
  reload the extension).
- Host APK (debug, installable next to a nightly build):
  `echo-app-src/app/build/outputs/apk/debug/app-debug.apk`
  ```bash
  adb install -r echo-app-src/app/build/outputs/apk/debug/app-debug.apk
  ```

## Unit-test status (CI-equivalent, JVM)

`./gradlew :ext:test` → **104 tests, 0 failures** (includes new
`StreamMediaCacheTest` persistence round-trip/expiry/clear tests and four new
`StreamPrefetcher` successor-prefetch tests). Host: `:app:compileReleaseKotlin`
and `:app:assembleDebug` build clean.

## Device test matrix

Run with `adb logcat | grep "[EchoBench]"` capturing. Clear the audio cache
via Settings → (Player) cache size re-apply or clear app data for TEST 1/3.

| # | Test | Procedure | Expected after changes | Result |
|---|---|---|---|---|
| 1 | First play, never played | clear cache, play song X | `playTap`→`firstAudibleOutput` ≤ ~1.2 s (was ~2 s); one `streamResolved` for X (cache=resolved) | ☐ |
| 2 | Replay same song | stop, replay X | < 300 ms; `streamResolved cache=hit`, no `player=` network line | ☐ |
| 3 | Different never-played song | play Y | ≤ ~1.2 s | ☐ |
| 4 | Skip before finish | play A, skip to B (same list) | ≤ 500 ms; B's `streamResolved` appears **during** A (preload/prefetch), not after skip | ☐ |
| 5 | Rapid skips A→B→C | play A, skip B, immediately skip C | C starts ≤ ~500 ms; no stale audio (transition marks ordered) | ☐ |
| 6 | Full play + auto next | play A to the end | transition `reason=auto`, B audible ≤ ~500 ms | ☐ |
| 7 | Restart + replay cached song | kill app, relaunch, play X | fast; `streamResolved cache=hit` from disk store (within URL window) | ☐ |
| 8 | Valid + expired auth | repeat 1 & 2 signed in / after logout | same behaviour; no 403 regressions | ☐ |
| 9 | PO-token refresh / 403 | force web lane (restricted track) | `tokenReady` mint line, playback OK; on 403 → re-mint once → ok | ☐ |
| 10 | Slow/interrupted network | throttle, kill Wi-Fi mid-resolve | graceful failure + retry; no ANR | ☐ |
| 11 | Queue modification while preloading | reorder/remove next during A | skip follows the new timeline; no stale playback | ☐ |
| 12 | Background playback | lock screen during 1–6 | unchanged; audio continues | ☐ |

## Acceptance criteria cross-check

1. First-play significantly faster — parallel resolution (host) + tuned buffer → expected ~1.2 s vs ~2 s (verify row 1).
2. Replay instant — unchanged SimpleCache + memory/disk URL cache (row 2).
3. Auto-next instant — unchanged + preload strengthens it (row 6).
4. Manual skip without resolution delay — Media3 preload + successor prefetch (row 4).
5. Rapid skips produce no stale playback — per-position preloading + dedupe/single-flight (row 5).
6. Queue changes cancel obsolete preparation — Media3 per-position preload follows the timeline; extension claims expire (row 11).
7. Auth + PO-token intact — no auth/token code touched; rows 8–9 verify.
8. No full-song downloads — preload/prefetch resolves metadata+URLs and ≤15 s of audio only.
9. Audio quality/background unchanged — no renderer/track-selection changes; row 12 verifies.
10. Real device measurements — this matrix.
