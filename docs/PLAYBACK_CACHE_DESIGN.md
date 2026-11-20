# Playback Cache Design

The independent cache layers between a play tap and audible output, each with
its own validity rules. Ordered by proximity to the player.

| # | Layer | Owner | Contents | Key | Validity | Persistence | Eviction |
|---|---|---|---|---|---|---|---|
| 1 | Audio byte cache | host `SimpleCache` (`PlayerService.getCache`) | raw audio bytes / HLS segments | resolved URL / manifest URI (per-source) | as long as bytes exist | disk (`cacheDir/exo-player`) | LRU, 250 MB default (`cache_size` setting) |
| 2 | Resolved stream cache (memory) | ext `StreamMediaCache` | `Streamable.Media` (sources + headers) | `videoId\|type\|height\|spoof\|potokenKind:session` | 25 min TTL, hard expiry | no | LRU 64 entries + single-flight per key |
| 3 | Resolved stream cache (disk) | ext `StreamCacheStore` behind #2 | same as #2 | same as #2 | ≤ TTL, **never revived past `expiresAt`** | `filesDir/stream-cache.json` (host-private, atomic tmp+rename) | LRU snapshot ≤ 64, debounced 500 ms write |
| 4 | Metadata/track caches | host `Cached` (FileKache 50 MB) | `MediaState.Loaded`, `Streamable.Media` fallback, feeds | `media-{ext}-{id}-state`, `media-{ext}-{track}-{streamable}` | no TTL — **fallback-only**: extension call always runs first, cache used only on error | disk | FileKache LRU |
| 5 | PO token cache | ext `AndroidPoTokenProvider` / `ExternalPoTokenProvider` | minted tokens | content binding = video ID | ≤ 6 h (android) / ≤ 30 min (external) | **memory only, by design** | per-binding map; `invalidate()` on 403/logout; engine rebuilt on expiry |
| 6 | HTTP connection pool | ext `StreamHttpClient` (OkHttp) + Ktor client | TLS/HTTP2 connections | host | 5 min keep-alive | — | pool size 8 |
| 7 | Signature solver | ext `YouTubeSignatureDeobfuscator` | base.js + Rhino scope per player version | player JS URL | until player version changes | — | one scope at a time (races serialize through it) |

## Correctness rules

- **Expired stream URLs are never valid.** Layer 2 checks `expiresAt <= now`
  on every read (memory) and every load (disk). Disk entries are stored with
  their absolute expiry and the loader drops expired ones — a stale file can
  never resurrect a dead URL.
- **Metadata ≠ audio ≠ URLs.** The byte cache (#1) holds audio keyed by URL;
  re-resolution (#2/#3) may produce a *different* URL for the same video
  (signature/token refresh) — that is fine, it is a cache miss in #1 and a
  fresh network fetch under the new URL. Metadata caches (#4) never satisfy a
  playback request on their own.
- **Identity/format keys.** The resolver key includes videoId, requested type
  (audio/video/background), target height, the spoof-client setting and the
  session (`visitor|userId`). Any change of account, spoof setting or token
  provider changes the key — no cross-account or cross-configuration reuse.
- **No secrets in ordinary caches.** Cookies and auth headers live in the auth
  manager and are attached per-request; PO tokens stay in memory; the disk
  stream cache stores only signed, IP-bound CDN URLs in the host's private
  storage. `streamCache.clear()` is invoked on account switch, PO-provider
  mode/URL change, and error-driven invalidation is per-videoId prefix.
- **Single-flight.** Concurrent requests for the same key (play + prefetch +
  preload) share one resolution via `getOrResolve`; failures are never cached.
- **Bounded.** Every layer has an explicit size bound (64 entries / 500 ms
  debounced writes / 250 MB / 50 MB) — no unbounded growth on long sessions.

## Cross-restart behaviour

`StreamMediaCache(store = FileStreamCacheStore(filesDir/stream-cache.json))`:
the first `get`/`put` after construction lazily loads the persisted snapshot
so the first tap after an app restart is a cache hit when within the URL
validity window (≤ 25 min). The host byte cache (#1) independently survives
restarts, so replay stays instant regardless.
