# Instant Playback — Architecture Audit

**Scope:** tap → audible playback for the YouTube Music extension
(`echo-youtube-extension-old`) inside the Echo host app (`echo-app-src`).
**Date:** 2026-11-20
**Method:** static trace of every hop between play tap and first audio byte, in
both repos, cross-checked against the Metrolist reference client (v0.5.2-era
source, same extraction approach: Innertube player request → signed URLs) and
Media3 1.8.0 bytecode (`ExoPlayer$PreloadConfiguration`).
**Evidence labels:** **[VERIFIED]** = read in the code in this workspace ·
**[MEASURED]** = resolver timing logs (device logcat, `YouTubeStreamResolver:
resolve … total=…ms`) or code-derived constants · **[INFERRED]** = follows from
code + platform behaviour.

---

## 1. End-to-end call graph

```
PLAY TAP (Echo UI)
  └─ PlayerViewModel.play(id, item, loaded)               app/ui/player/PlayerViewModel.kt:238
       └─ controller.sendCustomCommand(playCommand, …)    (MediaController IPC, in-process binder)
            └─ PlayerCallback.onCustomCommand → playItem  app/playback/PlayerCallback.kt:97-119, 230-289
                 ├─ Track branch: MediaItemUtils.build (UNLOADED)   :238-241
                 ├─ Main: setMediaItem + prepare + seekTo + play    :242-247
                 └─ prepare → StreamableMediaSource                 app/playback/source/StreamableMediaSource.kt
                      └─ prepareSourceInternal  :62-108
                           └─ StreamableLoader.load :38-53        (Dispatchers.IO)
                                1. loadTrack  → Cached.loadMedia  :63-68
                                │    └─ extension TrackClient.loadTrack (NETWORK, awaited)
                                │         └─ TrackLoader.loadTrackDetails         ext/…/providers/playback/TrackLoader.kt:15
                                │              ├─ EchoEnhancedSongEndpoint.loadEnhancedTrack (NETWORK)
                                │              └─ StreamPrefetcher.prefetchTrack (same track, deduped)
                                │    (loadTrack's 5 optional calls isSaved/isFollowed/… run in PARALLEL, Cached.kt:74-95)
                                2. loadServer ∥ loadBackground ∥ loadSubtitle      :45-52 (parallel, but ALL start after loadTrack)
                                     └─ Cached.loadStreamableMedia → extension TrackClient.loadStreamableMedia (NETWORK)
                                          └─ TrackLoader.loadStreamableMedia       TrackLoader.kt:27
                                               └─ YouTubeStreamResolver.resolveStreamable
                                                    ├─ StreamMediaCache.get(key) → HIT: return (~0ms)
                                                    └─ MISS: getOrResolve (single-flight) → resolveStreamableInternal
                                                         ├─ fast lanes raced: VISIONOS ∥ TVHTML5(cookie) fetchPlayerResponse (NETWORK)
                                                         ├─ extractMediaFromFormatResponse (parse + optional decipher)
                                                         ├─ optional probePlayable (≤800ms budget, web lanes only)
                                                         └─ cache.put (TTL 25min, LRU 64)
                      └─ prepareChildSource → HlsMediaSource/Progressive (manifest + first segments)
                           └─ ExoPlayer buffering → READY → AudioTrack advancing (audible)
```

Cache layers already present:

| Layer | Where | Key | TTL / bound | Persisted |
|---|---|---|---|---|
| Track/`MediaState` file cache | host `Cached.loadMedia` → FileKache | `media-{ext}-{id}-state` | none | yes, **fallback-only** (extension call always first) |
| `Streamable.Media` file cache | host `Cached.loadStreamableMedia` | `media-{ext}-{track}-{streamable}` | none | yes, **fallback-only** |
| Resolved stream cache | ext `StreamMediaCache` | `videoId\|type\|height\|spoof\|session` | 25 min / LRU 64, single-flight | **no — `StreamCacheStore` exists but is orphaned** |
| Audio byte cache | host `SimpleCache` (`PlayerService.getCache`, 250 MB default, LRU) | resolved URL / manifest URI | size-bounded | yes, covers HLS/Progressive |
| PO token cache | ext `ExternalPoTokenProvider` (memory, per binding), Android engine (WebView) | videoId | ≤30 min | no (by design) |
| HTTP pools | ext `StreamHttpClient` (player/probe/hls/token/solver, OkHttp) + host DefaultHttpDataSource | — | 5 min keep-alive | — |
| OkHttp/Ktor warm-up | ext `ExtensionComponents.init` (visitor ID, TLS warm-up, token warm-up after 3 s) | — | — | — |

---

## 2. Why the FIRST play of a song costs ~2 s — latency attribution

The extension resolver alone is **not** the dominant cost. The full chain has
two sequential extension network calls *plus* Media3's default "prepare the
audio only 2.5 s before audible" policy. Ranked attribution for a cold
first-time song (warm HTTP, no cached bytes):

| # | Cost | Where | Estimate | Evidence |
|---|---|---|---|---|
| 1 | **loadTrack round-trip** (enhanced song metadata) blocks `loadServer` | host `StreamableLoader.load:40-43` | 200–600 ms | **[VERIFIED]** sequential await; **[MEASURED]** typical Innertube RT |
| 2 | **Stream resolution round-trip** (player request, fast-lane race → winner) | ext resolver | 300–900 ms (VISIONOS lane single RT; TVHTML5 race can halve it) | **[MEASURED]** resolver logs `player=…ms/1`, `total=…ms` |
| 3 | **ExoPlayer bufferForPlayback = 2500 ms default** — no LoadControl anywhere in host | Media3 default | up to 2500 ms of *audio-side buffering target* after media ready | **[VERIFIED]** zero `LoadControl` references in echo-app-src |
| 4 | Optional probe on non-fast lanes (≤800 ms budget) | ext `probePlayable` | 0 ms on visionOS win; +100–800 ms on TV/web lanes | **[VERIFIED]** `shouldProbe` skips token-free lanes |
| 5 | Optional HLS master fetch + sort (video only) | ext `getSortedHlsUrl` | 0 ms (audio), +200–500 ms (video) | **[VERIFIED]** |
| 6 | WebView PO-token cold boot if a web lane is entered | ext `WebViewMintEngine` | 0 ms (token-free lane wins first) / 2–5 s (restricted tracks) | **[VERIFIED]** warm-up delayed 3 s after startup |

Items 1+2+3 stack **serially** before the first byte: ~0.5–1.5 s of network
plus Media3's internal buffer-fill to reach READY, then audio sink ramp. On a
typical connection this lands at ≈2 s — matching the observed Scenario A.

**Why replay (Scenario B) is instant:** the host `SimpleCache` holds the
bytes; on replay `CacheDataSource` serves them and the extension is still
called (URL re-resolution) but its in-memory `StreamMediaCache` returns in
<1 ms, so only local work remains **[VERIFIED]**.

**Why automatic-next (Scenario C) works:** ExoPlayer's period queue prepares
the *next* MediaSource while the current one plays (ordinary playlist
behaviour), so the extension chain for B runs during A's tail **[VERIFIED
behaviour; INFERRED mechanism]**.

**Why manual skip (Scenario D) is slow:** the next period's MediaSource is
only prepared near the end of the current one, and the host explicitly
disables Media3's ahead-of-time preloader:
`preloadConfiguration = ExoPlayer.PreloadConfiguration(C.TIME_UNSET)`
(`PlayerService.kt:174`) **[VERIFIED]**. A skip seconds after play therefore
re-runs the whole loadTrack→loadStreamableMedia chain inline (items 1+2+3).

---

## 3. Metrolist comparison — why it feels instant

Metrolist (local copy; upstream `main` shows no newer preloading/latency
commits as of 2026-11-20) plays a tapped song with **zero gating network
round-trips**:

1. **prepare() before any network** — `MusicService.playQueue` sets the
   preload item and calls `prepare()` synchronously; the `/next` queue fetch
   runs concurrently (`MusicService.kt:1771-1783`).
2. **URL resolution lazily overlapped with buffering** — a `ResolvingDataSource`
   substitutes the real URL at first read; order is download cache → 1 GB
   player `SimpleCache` → in-memory `StreamUrlCache` (500 entries, URL-expiry
   aware) → network (`MusicService.kt:3798-3831`, `StreamUrlCache.kt:37-121`).
   Warm session, cold song = **~1 RT** total.
3. **Session prewarm at app start** — visitorData awaited + extractor
   prewarm 2.5 s after launch (`App.kt:104-113`), WebView PoToken generator
   reused across calls, failed clients blacklisted per video for 5 min.
4. **bufferForPlaybackMs = 750** (`MusicService.kt:1308-1316`) vs Echo's
   Media3 default 2500.
5. **Zero IPC** — extraction is in-process; Echo pays a controller command hop
   and an extension call boundary (in-process too, but a second serialization
   + classloader hop).

Net: Metrolist's first tap ≈ 1 player RT + 750 ms buffer target ≈ 0.8–1.2 s.
Echo ≈ 2 sequential RTs + 2500 ms buffer target ≈ 1.8–3 s. The gap is fully
explained by the table above — the extension's resolver is *not* the problem;
the *host* prepare pipeline is.

---

## 4. Extension-side gaps found (this repo)

| # | Finding | Evidence |
|---|---|---|
| E1 | **`StreamCacheStore` (disk persistence of resolved streams) is orphaned** — defined with tests but never referenced by `StreamMediaCache`. First tap after app restart always re-resolves. | `grep` shows zero usages outside its own file/test |
| E2 | Prefetch covers **loaded lists** (first 3 tracks, 2 concurrent) and the tapped track, but the extension never learns the **queue successor**: `onTrackChanged(details)` only carries the current track. | `StreamPrefetcher` claims; host `TrackingListener` contract |
| E3 | Scenario-D fallback therefore does not exist extension-side; only list-position luck warms track #2/#3. | `StreamPrefetcher.prefetchShelves` call sites |
| E4 | `onTrackChanged` already reaches the extension (TrackingListener calls the music extension directly) — a free hook for successor prefetch. | host `TrackingListener.trackMedia:56-71` |
| E5 | Resolver already: races fast lanes, single-flights duplicates, TTL+LRU cache, warm-up at startup, redacts URLs. No changes needed to the cascade itself. | `YouTubeStreamResolver`, `ExtensionComponents.init` |

## 5. Host-side gaps found (`echo-app-src`)

| # | Finding | Evidence |
|---|---|---|
| H1 | **Media3 next-item preload disabled** (`PreloadConfiguration(C.TIME_UNSET)`). Enabling with a positive duration makes ExoPlayer prepare the upcoming item's MediaSource (and thus run the extension chain) during playback — exactly Scenario C's mechanism, applied to any queue position. | `PlayerService.kt:174`; `ExoPlayer$PreloadConfiguration(long)` confirmed in media3-exoplayer 1.8.0 |
| H2 | **Default LoadControl** — `bufferForPlaybackMs` 2500 ms default. Metrolist ships 750/2000 in production. | no `LoadControl` refs; Metrolist `MusicService.kt:1308-1316` |
| H3 | Host caches (`Cached`) are **fallback-only**: the extension network call always runs first, so a resolved `Streamable.Media` is never *reused* across plays — only used on failure. The ext in-memory cache covers this, but dies with the process (see E1). | `Cached.kt:141-145` |
| H4 | `StreamableLoader.load` runs `loadTrack` → (await) → `loadServer`: two sequential extension RTs per prepare. `loadServer` could start as soon as the *streamable* is known (it is: `mediaItem.track.servers[serverIndex]` exists before `loadTrack` returns — `loadTrack` only enriches metadata/likes). | `StreamableLoader.kt:38-53` |
| H5 | No benchmark instrumentation: no timestamps for playTap → audible; `state.servers` WeakHashMap unused for reuse decisions. | repo-wide grep |

---

## 6. Root causes of the four scenarios

| Scenario | Root cause | Fix locus |
|---|---|---|
| A: first play ~2 s | serial `loadTrack`→`loadStreamableMedia` + 2500 ms `bufferForPlayback` (+1 RT resolver) | host H2/H4 + ext cache hits (E1) |
| B: replay instant | SimpleCache byte cache + ext memory cache | preserve |
| C: auto-next instant | ExoPlayer period-queue prepares next during playback | preserve; strengthen with H1 |
| D: manual skip slow | next item's MediaSource not prepared (preload disabled H1; no queue knowledge ext-side E2/E3) | **H1 primary**, E2/E3 complement |

---

## 7. Privacy notes

- Resolver logs already redact stream URLs (`redactedStreamUrl`); token values
  are logged as lengths only; `StreamCacheStore` persists signed URLs in the
  host's private storage only (never cookies/tokens; PO tokens stay in memory
  by design). The planned benchmark logs follow the same rules.
