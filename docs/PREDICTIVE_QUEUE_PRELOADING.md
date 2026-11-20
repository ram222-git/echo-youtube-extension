# Predictive Queue Preloading

How a manual skip (Scenario D) becomes as fast as an automatic transition
(Scenario C). Two cooperating layers, one host-side (general, queue-aware) and
one extension-side (list-position-aware).

## Layer 1 — Media3 next-item preload (host, primary fix)

`PlayerService.createExoplayer` sets:

```kotlin
it.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_DURATION_US) // 15 s
```

While song A plays, ExoPlayer prepares the *next* queue item's
`StreamableMediaSource` up to 15 s ahead of time. Preparing the source triggers
exactly the same chain as real playback — `loadTrack` → `loadStreamableMedia`
— so by the time the user skips, the next item's MediaSource is already built
and its first segments buffered. `onMediaItemTransition` then reuses the
prepared source; `BenchmarkListener` logs the transition (`reason=seek|auto`).

Why this is the correct locus: only the player knows the real queue —
extensions never see it (the `echo.common` API has no "prepare next" hook and
`onTrackChanged` only carries the current track). Media3's own timeline
mechanism handles **every** queue type: playlists, albums, radio, manually
queued songs, and reordering during preload (the prepared source follows the
timeline item it was built for).

Cost control: Media3 preloads the single next position only — not the whole
queue — and the 15 s cap bounds buffered audio. Obsolete preparation (user
skips elsewhere) is bounded by the same source the timeline item holds; the
extension-side caches expire normally.

## Layer 2 — Successor prefetch (extension, complement)

`StreamPrefetcher` learns ordered track lists as the extension serves them
(`prefetchTracks` from album/playlist/radio pages, `prefetchShelves` for track
shelves; the four most recent lists, capped at 100 tracks). The host's
`TrackingListener` calls the extension's `onTrackChanged(details)` when a track
starts; `YoutubeExtension.onTrackChanged` forwards it:

```kotlin
components.streamPrefetcher.onCurrentTrack(details?.track)
```

`onCurrentTrack` finds the playing track in a remembered list and prefetches
the next `SUCCESSOR_COUNT = 2` audio targets with the same safety rails as
list prefetch:

| Rail | Value |
|---|---|
| Concurrent player requests | 2 (`maxConcurrent`) |
| Targets per event | 2 successors |
| Dedupe window | 10 min per video (`reserved` + `inFlight`) |
| Failure handling | silent; claim released so a real play retries |
| Duplicate work | collapses into `StreamMediaCache.getOrResolve` single-flight |
| Full-song downloads | never — resolution only (metadata + URLs) |

This layer alone fixes skips inside the lists Echo serves (album, playlist,
radio) even where Layer 1 is unavailable (older host builds, other hosts); it
cannot predict manually-queued successors, which is exactly what Layer 1
covers.

## Interaction with the audio cache

Prepared sources point at signed CDN URLs; the host `SimpleCache` then buffers
the first ~15 s of the next song during playback of the current one. A skip
afterwards starts from the byte cache — no network at all for the first
seconds.

## Rapid-skip safety

- A → B → C within seconds: each `onTrackChanged` claims only its own
  successors; the extension's single-flight dedupes; Media3 moves its preload
  to the new next item automatically (per-position, bounded).
- A → B → A: A's source/URL caches may still be within TTL → immediate.
- Obsolete in-flight resolutions finish bounded (network timeouts ≤ 12 s) and
  are simply not consumed; nothing is cancelled mid-CDN-write (cancelling the
  resolver mid-flight would discard work the cache needs to be usable).

## Verification hooks

`[EchoBench]` lines to look for on a skip:

```
[EchoBench] t+…ms mediaItemTransition reason=seek media=<B>
[EchoBench] t+…ms playerReady media=<B>            ← should be within ~500 ms
[EchoBench] t+…ms firstAudibleOutput media=<B>
```

A preloaded skip shows **no** `streamResolved` line for B between the
transition and READY (it was resolved earlier during A's playback).
