# Instant Playback — Optimization Plan

Derived from `INSTANT_PLAYBACK_ARCHITECTURE_AUDIT.md`. Ordered by expected
impact; every change is small, reversible and keeps the verified PO-token /
auth behaviour intact.

## P0 — Host: enable Media3 next-item preload (fixes Scenario D at the root)

`echo-app-src` `PlayerService.createExoplayer`:

```kotlin
// before
it.preloadConfiguration = ExoPlayer.PreloadConfiguration(C.TIME_UNSET)
// after
it.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_DURATION_US) // 15 s
```

While song A plays, ExoPlayer prepares song B's `StreamableMediaSource`
immediately (not near A's end) → `loadTrack` + `loadStreamableMedia` for B run
during A → a manual skip to B is a prepared-source transition. Works for every
queue type (playlists, radio, albums, manual queueing, reordering) because it
uses the player's own timeline. Cost: one extra metadata+player-request pair
per upcoming item, bounded by the next position only (Media3 preloads the
single following item).

## P1 — Host: make first play audible sooner

`echo-app-src` `RenderersFactory`/`PlayerService`: add a `DefaultLoadControl`
with `bufferForPlaybackMs = 750`, `bufferForPlaybackAfterRebufferMs = 2000`
(the values Metrolist ships), keeping the 50 s back/max buffer defaults.
Audio quality and stability unchanged; only the "fill this much buffer before
opening the audio sink" target drops from 2500 ms.

## P2 — Host: start the two resolution calls concurrently

`echo-app-src` `StreamableLoader.load`: `loadTrack` currently blocks
`loadServer`. The streamable to resolve (`track.servers[serverIndex]`) is
known before `loadTrack` returns, so `loadServer` can start in parallel and
only the *MediaItem rebuild* needs `loadTrack`. Expected saving: one full RT
(200–600 ms) on every uncached prepare.

## P3 — Extension: persist resolved streams across restarts (wire `StreamCacheStore`)

`StreamMediaCache` gains an optional `StreamCacheStore`:

- load entries on first use, keep only `expiresAt > now` (never revive past
  expiry — signed URLs die with their signature window),
- after each successful `put`, persist the bounded LRU snapshot async,
- cleared on account switch / provider change (already wired via
  `streamCache.clear()` call sites),
- file: `filesDir/stream-cache.json` (host-private), no secrets — signed URLs
  are IP-bound to this device.

Effect: first tap after app restart is a cache hit within the URL validity
window instead of a fresh resolution.

## P4 — Extension: successor prefetch on `onTrackChanged` (belt-and-braces for Scenario D)

The extension cannot see the host queue, but it *serves* the lists (album /
playlist / radio) and receives `onTrackChanged(current)` when a song starts.
`StreamPrefetcher` learns the last served lists; on track change it looks up
the current video ID in the most recent list and prefetches the next 1–2
audio targets — same claims/limits as list prefetch (3 per event, 2
concurrent, 10 min dedupe, silent failures, no full-song downloads).
Rapid skips are safe: claims are per-video, in-flight deduped by the resolver's
single-flight cache, and a newer `onTrackChanged` simply issues claims for a
different successor; obsolete speculative work finishes bounded and unused.
This also covers hosts/queues where Media3 preload is unavailable.

## P5 — Extension: benchmark instrumentation

Add a lightweight monotonic `PlaybackBench` (log-only, no secrets):

- ext: `playRequest` (loadStreamableMedia entry), `cacheHit`,
  `streamResolved` (already in `ResolveTiming`), token readiness,
- host: `playTap` (PlayerCallback.playItem), `mediaSourcePrepared`
  (StreamableMediaSource), `playerReady`, `firstAudibleOutput`
  (`AnalyticsListener.onAudioPositionAdvancing` — distinguishes READY from
  actual audible output).

One `EchoBench` line per scenario so the five mission measurements can be
read straight from logcat.

## Explicitly not doing

- No changes to the extraction cascade order, PO-token providers, auth, or
  cookie handling (verified behaviour).
- No full-song downloads, no playlist-wide prefetch, no new backend.
- No ExoPlayer instance reuse changes (Media3 session lifecycle untouched).
- No URL hardcoding; no new cache that outlives URL validity.
