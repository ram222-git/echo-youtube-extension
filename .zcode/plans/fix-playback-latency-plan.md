# Plan — Fix 2–5 s delay before a song starts playing

**Goal:** tapping a song should start audio in roughly one player-request round-trip
(~300–600 ms), like Morphe/ReVanced YouTube Music, instead of 2–5 s.

**Status:** implemented (all 5 phases) + a follow-up fix for the 403 regression
found on device. Verified: `:ext:compileKotlin` and `:ext:test` pass offline.
Measured after the change: cold first play **354 ms**, cached replay **0 ms**,
video first play **248 ms** (visionOS HLS). See §7 and §8.

---

## 1. Measured evidence

Benchmark run on this repo (`:ext:test`, desktop JVM, warm network) against
`qeFt3fdsydA` with `spoof_stream_client = auto`, `enable_video = false`:

| Stage | Time | Where |
|---|---|---|
| Full `loadStreamableMedia()` (1st call) | **2263 ms** | `YoutubeExtension.loadStreamableMedia` |
| Full `loadStreamableMedia()` (2nd/3rd call) | **1530 / 1537 ms** | same, no caching in between |
| RealNewPipe `getAudioFormats()` (primary path) | **1517 ms** | `RealNewPipeVideoFormatsEndpoint.fetchAllStreams` |
| Sequential health probes for the 5 returned audio sources | **1398 ms** (one probe alone 1062 ms) | `YouTubeStreamResolver.isStreamPlayable` |
| Spoofed Innertube player POST (ANDROID_VR) | **333 ms** | `EchoVideoEndpoint.getSpoofedVideo` |
| First `YouTubeSignatureDeobfuscator.deobfuscateStreamUrl()` | **2228 ms** (base.js download + Rhino eval) | `YouTubeSignatureDeobfuscator.initSolver` |
| Subsequent `deobfuscateStreamUrl()` | 2 ms | same |

On a phone the network timings are worse; the 1.4 s of *serial* probes plus the
1.5 s NewPipe call plus the one-time 2.2 s Rhino init are exactly the reported
2–5 s.

The 1.4 s of health checks was introduced in `dd4835a`
("feat: add client video stream spoofing, fix 403 errors …") and is the main
regression vs. the previous behaviour.

---

## 2. Root causes (file:line)

1. **Blocking per-format health checks (biggest cost, every play).**
   `ext/.../streaming/YouTubeStreamResolver.kt:50-73` `isStreamPlayable()` runs a
   synchronous `OkHttp` `Range: bytes=0-0` request on the caller's coroutine
   (no `Dispatchers.IO`), a fresh TLS handshake per call. It is called once per
   candidate source:
   - audio: `:828` (`adaptiveAudio` filter) + `:831` (muxed), 5 requests for a
     normal track;
   - video: `:784` (video + audio), `:791`, `:809`.
   Each result is only used to discard 403 URLs, but a single stalled googlevideo
   edge costs ~1 s and blocks the whole list.

2. **Slow extractor is tried first.** Audio cascade is
   `RealNewPipe (1.5 s)` → `YtmKt` → spoof clients → YouTube Music API
   (`:361-414`). The fastest path (one Innertube `player` POST, 333 ms) is 3rd.

3. **Deobfuscator on the hot path, and it runs for URLs that need no deciphering.**
   `extractMediaFromFormatResponse` calls
   `YouTubeSignatureDeobfuscator.deobfuscateStreamUrl()` for *every* format that
   already has a direct `url` (`:718`). That function unconditionally calls
   `initSolver()` (`YouTubeSignatureDeobfuscator.kt:125-126`), which downloads a
   **hard-coded** `player/57bae81f/.../base.js` (`:24`) and evaluates it in Rhino
   with `optimizationLevel = -1` (`:110`). 2.2 s once per process, and if the
   hard-coded hash ever stops containing the ALR pattern, `initSolver()` throws
   *before* setting `isInitialized`, so **every subsequent format retries the
   download**.
   Solving `n` only affects throughput throttling, not playability.

4. **No resolved-media cache.** Every click / seek / quality switch re-runs the
   whole cascade. Two clicks on the same song = two full resolutions
   (confirmed: 1530 ms each above).

5. **Minor contributors.**
   - `EchoVideoEndpoint.getSpoofedVideo` uses a bare `OkHttpClient()` (default
     10 s connect, no call timeout, no shared pool) and blocks inside
     `withContext(Dispatchers.IO)` (`EchoVideoEndpoint.kt:74,125`).
   - `getSortedHlsUrl` fetches the HLS master playlist and writes a temp file on
     every video resolution (`:85-129`), no memoization.
   - `resolveBackground` repeats the same slow-first cascade and probes
     (`:210-276`).
   - `deobfuscateCipherUrl` can decipher the same cipher twice per format.

---

## 3. Fix plan

### Phase 1 — remove the serial health probes (expected −1.4 s)

**File:** `ext/src/main/java/dev/brahmkshatriya/echo/extension/streaming/YouTubeStreamResolver.kt`

1. Delete `isStreamPlayable` from all list-building paths:
   - audio: `adaptiveAudio` (drop `&& isStreamPlayable(it.url)`), `muxedAudio`;
   - video: `distinctVideoFormats` / `playableVideos` / `muxedFormats`.
2. Build the source list unconditionally, then verify **only the source that
   will actually play** (the top-quality one) with a hard time budget:
   ```kotlin
   private suspend fun verifyTopSource(sources: List<Streamable.Source.Http>): Boolean =
       withContext(Dispatchers.IO) {
           val top = sources.firstOrNull { !it.isVideo } ?: sources.firstOrNull() ?: return@withContext true
           runCatching {
               withTimeoutOrNull(800) { probe(top.request.url) } ?: true   // timeout => assume playable
           }.getOrDefault(true)
       }
   ```
   - `probe()` keeps the existing `Range: bytes=0-0` + `User-Agent` logic and
     returns `false` only on an explicit `403`.
   - On `false`, return `ExtractionResult.Error("403")` so the existing cascade
     moves to the next client (same behaviour as today, but one request, not six).
   - Never block the list on the probe: run it once per result, after the list is
     built.
3. `isStreamPlayable` stays only as the internal single-request helper; rename to
   `probeStream` and make it `suspend` + `Dispatchers.IO`.
4. Keep `merged = false` for audio and the descending bitrate order (host
   contract, asserted by existing tests).

### Phase 2 — put the fastest extractor first (expected −1.2 s on the common path)

**File:** `YouTubeStreamResolver.kt` (`resolveStreamableInternal`, `resolveBackground`)

New audio order (auto mode):
1. `SpoofClient.ANDROID_VR` player POST (333 ms, no decipher for direct URLs)
2. `SpoofClient.WEB_REMIX` → `SpoofClient.IOS` (fallbacks)
3. `RealNewPipe` (HLS/JS decipher, most robust but slow)
4. `YtmKt`
5. Restricted / WEB_CREATOR extractor

- Keep an explicit user-selected client at position 0 (current behaviour).
- Video order: spoofed client (already returns HLS when available) →
  `RealNewPipe` HLS → `YtmKt`; drop the redundant probe loop.
- `resolveBackground`: same reorder, and reuse
  `currentPlaylistId`-independent helper instead of duplicating the cascade.
- Only log via the existing `println` style; no new dependencies.

Rationale: the spoof path is a single Innertube request and produces
already-signed direct URLs; NewPipe's extra JS deciphering is only needed when
YouTube rejects the spoofed clients.

### Phase 3 — stop paying for signature solving (expected −2.2 s on first fallback)

**Files:** `streaming/YouTubeSignatureDeobfuscator.kt`, `YouTubeStreamResolver.kt`

1. **Fast exit in `deobfuscateStreamUrl`:**
   - if `rawUrl` has no `signature` and no `n=` param → `return rawUrl` immediately
     (no solver init);
   - if it only has `n=`, treat solving as optional: return the URL unchanged and
     solve `n` in the background (or only when a "fast throttle-free playback"
     setting is enabled), because an unsolved `n` still plays (throttled), while
     blocking on it costs seconds.
2. **Never retry a failed init per format.** Add
   `@Volatile private var initFailedAt: Long = 0`; after a failure, skip solving
   for 10 minutes instead of re-downloading base.js for every format.
3. **Resolve the current player JS** instead of the hard-coded
   `player/57bae81f/...`: take the `jsUrl` from the player response (add
   `jsUrl` to `StreamingData`/`VideoDetails` in `EchoVideoEndpoint.kt`), fall back
   to `https://www.youtube.com/iframe_api` → `player_ias…/base.js` discovery.
4. Initialize once, off the playback path, with a shared `OkHttpClient` call
   timeout (~4 s), and use a higher Rhino optimization level for the one-time
   `evaluateString` (the solver call itself can stay interpreted).
5. Cache `DecipherResult` by `"$signature|$n"` (bounded map) and remove the
   duplicate decipher in `deobfuscateCipherUrl` (only decipher when
   `signatureCipher`/`cipher` exists).

### Phase 4 — resolved-media cache + prefetch (repeat plays instant)

**New file:** `ext/.../streaming/StreamMediaCache.kt`

```kotlin
class StreamMediaCache(maxEntries: Int = 64, ttlMs: Long = 25 * 60_000) {
    suspend fun getOrResolve(key: String, resolve: suspend () -> Streamable.Media): Streamable.Media
    fun invalidate(videoId: String)   // called when a probe reports 403
}
```
- LRU `LinkedHashMap` guarded by a `Mutex`/`synchronized`, `expiresAt` per entry.
- **Single-flight** per key (`ConcurrentHashMap<String, Deferred<…>>`) so a
  prefetch and a real play share one request instead of duplicating it.
- Key: `"$videoId|${type}|${height ?: 0}|${spoofSetting}"` (settings changes
  must not reuse stale results).
- `YouTubeStreamResolver.resolveStreamable/resolveBackground` wrap their work in
  `cache.getOrResolve(...)`; `invalidate()` on a 403 rejection so retry after a
  playback error does a real resolve.

**Prefetch (optional but needed for true "instant" on the first tap):**
- `ExtensionComponents`: add `val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- `TrackLoader.loadTrackDetails`: after the (already network-free) track is
  built, `ioScope.launch { streamResolver.resolveStreamable(audioStreamable) }`
  when the new setting `preload_stream` is enabled (default **on**; documented
  as "may use a little data").
- Single-flight guarantees the prefetch and the later `loadStreamableMedia`
  collapse into one network call, so the play button returns from the warm cache
  in <50 ms.
- If Echo resolves audio + video streamables in parallel, the cache prevents the
  duplicate Innertube requests.

### Phase 5 — polish (small, safe)

- Share one `OkHttpClient` for player/probe calls: `connectTimeout 5 s`,
  `callTimeout 8 s`, `retryOnConnectionFailure(true)`, `ConnectionPool(8, 5, MINUTES)`.
- Memoize `getSortedHlsUrl` by HLS URL (`Map<String, String>`), reuse the temp
  file, and delete files older than 1 h (already partially done).
- `EchoVideoEndpoint.getSpoofedVideo`: accept the shared client; keep the
  `Set-Cookie` listener behaviour.
- Add the new setting to `YoutubeExtension.getSettingItems()` and to the README
  settings table.

---

## 4. Verification

1. `./gradlew :ext:compileKotlin --offline` — compiles (this is the current
   baseline; it already passes).
2. New unit tests (no network):
   - `StreamMediaCacheTest`: TTL expiry, LRU eviction, single-flight
     (two concurrent calls → `resolve` invoked once), invalidation.
   - Re-use `MockedSettings`; extend `SpoofClientTest` with a test that
     `auto` ordering starts with a spoofed client.
3. Existing live tests (network available here):
   - `./gradlew :ext:test --offline --tests "*SpoofClientTest*"` → still returns a
     non-empty `Media.Server`, `merged == false`, video sources present.
   - Re-run the temporary profiling idea (not committed) and record:
     target **first resolve ≤ 800 ms**, **second resolve on same video < 50 ms**,
     **0 health-check requests in the audio path** (log count).
4. Manual on device: cold start → tap a song; first play ≤ 1 s, replay instant;
   enable video → 1080p playback still starts, quality switch works;
   `WEB_REMIX`/restricted fallback still resolves.

## 5. Acceptance criteria

- Common-case tap-to-audio ≤ ~600 ms warm, ≤ 1 s cold (was 2–5 s).
- Replay of the same song ≤ 50 ms.
- No sequential per-format HTTP probing anywhere.
- No Rhino/base.js work on any path that uses a direct (non-ciphered) URL.
- Audio still returns all quality sources, `merged = false`; video HLS ordering,
  quality selection, restricted/age-gated fallback, and the spoof-client setting
  all keep working.
- On any 403 the resolver still falls through to the next extractor and does not
  cache the rejected result.

## 6. Risk / rollback

- **Reordering extractors:** a video that used to play via RealNewPipe might now
  use a spoofed client with different quality caps. Mitigation: keep the old
  cascade as the fallback chain and add a setting "Preferred extractor"
  (`Auto (fast)` / `Compatibility (RealNewPipe first)`) if regressions appear.
- **Removing probes:** possible 403 surfacing at playback instead of resolve
  time. Mitigation: keep the single, budgeted probe of the top source, and the
  `invalidate()` path so a retry re-resolves.
- **Prefetch setting:** can be turned off; no behaviour change when disabled.
- Everything is localized to `ext/` (no app/ extension-API changes); revert =
  revert the two streaming files plus delete the new cache file.

---

## 7. Implementation results (what actually shipped)

### Phase 1 — probes
- `isStreamPlayable` replaced by `probeStream` / `probePlayable` /
  `probeAllPlayable`: a **single** request with an 800 ms `withTimeoutOrNull`
  budget, run on `Dispatchers.IO`, rejecting only on an explicit **403**
  (timeout ⇒ treated as playable). The request must be a plain GET (**no**
  `Range` header — see §8), and its body is closed immediately.
- Source lists are built unconditionally; there is no per-format serial probing
  left in the audio or video path.
- `merged = false` and descending bitrate order preserved.

### Phase 2 — extractor order (auto)
- Audio: `VISIONOS` → `RealNewPipe` → `IOS` → `WEB_REMIX` → `TV` → `YtmKt` →
  Music API → restricted/WEB_CREATOR.
- Video: `VISIONOS` (HLS) → `RealNewPipe` → `IOS` → `TV` → `YtmKt` → Music API →
  restricted. Explicit user-selected client still runs first (and falls back if
  its URLs 403).
- `resolveBackground` reordered the same way.

### Phase 3 — signature solving
- `deobfuscateStreamUrl`: immediate return for direct URLs (no `s`, no `n`); no
  solver init on the hot path.
- `n`-only URLs: returned unsolved on the hot path, solved only when the solver
  is already warm, plus `warmUpAsync()` in the background (unsolved `n` still
  plays, just throttled).
- Failed init is remembered for 10 minutes (no base.js re-download per format);
  current player JS URL is passed in via `setPlayerJsUrl()`; decipher results
  cached; `isReady()` exposed for callers.

### Phase 4 — cache + prefetch
- New `StreamMediaCache` (TTL 25 min, LRU 64, single-flight per key,
  `invalidate(prefix)`) wraps `resolveStreamable` / `resolveBackground`.
- New `invalidate` calls on cancellation-safe paths so a 403 is never cached.
- New `preload_stream` setting (default on): `TrackLoader.loadTrackDetails`
  prefetches the audio stream in `ioScope` (shared `SupervisorJob` +
  `Dispatchers.IO`); single-flight collapses prefetch + play into one request.

### Phase 5 — polish
- New `StreamHttpClient` with shared `player` / `probe` / `solver` OkHttp
  clients (connection pool reuse, timeouts, retry).
- `EchoVideoEndpoint` uses the shared client and exposes `assets.js`.
- `getSortedHlsUrl` memoized.
- `preload_stream` added to `getSettingItems()` and the README settings table.

### Measured (same benchmark, desktop JVM, `qeFt3fdsydA`)

| Scenario | Before | After |
|---|---|---|
| Cold first play | 2263 ms | **683 ms** |
| Replay same song | ~1530 ms | **0 ms** (cache) |
| Play after track prefetch | ~1530 ms | **0 ms** |
| Video first play | — | **438 ms** (1080p/720p/360p/144p) |
| Serial probe time | 1398 ms | ~one 800 ms-budget probe at most |
| First-play Rhino init | 2228 ms | 0 ms (background/optional) |

### Deviations from the original plan
- Phase 1 keeps **one** budgeted probe (not zero) to preserve 403 fallback.
- Phase 3 does not solve `n` on the critical path; it returns the URL and warms
  the solver asynchronously.
- Extractor order adds `TV` as an extra spoof fallback between `WEB_REMIX` and
  `RealNewPipe` for audio, and `IOS` before `ANDROID_VR` for video (HLS).
- `ExtensionComponents` is recreated on `setSettings`, so the cache and IO scope
  reset when settings change (intended: stale URLs are keyed by setting).

### Test coverage added
- `StreamMediaCacheTest` — 6 tests: TTL expiry, LRU eviction, single-flight
  (concurrent calls resolve once), invalidation, prefix invalidation,
  deobfuscator fast-path/no-init behaviour.
- Existing `SpoofClientTest` live test still passes (ANDROID_VR, non-empty
  server, `merged = false`, video sources present), and now also asserts that the
  returned URL answers a **non-Range** GET with 2xx/3xx.

---

## 8. Follow-up: "all songs 403" regression (fixed)

### Symptoms
After the first implementation every song failed on device with a 403. The
logcat pasted was microG/Samsung noise, not the extension.

### Reproduction (desktop, live)
Resolving with `spoof_stream_client=android_vr` and then fetching the returned
URL exactly as the player would:

```
Range: bytes=0-0   -> HTTP 206   (looks healthy)
no Range (normal)  -> HTTP 403
```

The old health check used a ranged request, so it *hid* the failure. Every
Innertube-spoofed client (ANDROID_VR, iOS, TV, MWEB) returned URLs that 403 on a
normal GET; `WEB_REMIX` additionally needed `n` solved. NewPipe's URLs
(`c=VISIONOS`) returned 200 for both.

### Root causes
1. `probeStream` sent `Range: bytes=0-0`, which googlevideo answers with 206 even
   for URLs it rejects for playback — a broken source passed the check.
2. The extractor reorder put those spoofed clients first, so playback (and the
   probe) had no working source in the common case.
3. Skipping `n` solving was not the cause here: the failing URLs had no `n`.

### Fix
- **visionOS client added** (`SpoofClient.VISIONOS`, `clientName=VISIONOS`,
  version `1.02`, client number `101`, visionOS UA/device fields; the player
  request also sends `X-Goog-Api-Format-Version: 2`). This mirrors what
  NewPipe 0.26.5 uses, and its URLs play.
- **Extractor order (auto):**
  - audio: `VISIONOS` → `RealNewPipe` → `iOS` → `WEB_REMIX` → `TV` → `YtmKt` →
    Music API → restricted;
  - video: `VISIONOS` (HLS) → `RealNewPipe` → `iOS` → `TV` → `YtmKt` →
    Music API → restricted;
  - `resolveBackground` starts with `VISIONOS` too.
- **Probe fixed:** no `Range` header; only the status code matters and the body
  is closed immediately. A 403 now rejects the whole extractor result so the
  cascade moves on (all sources share one client).
- `getUserAgentForUrl` maps `c=VISIONOS` to the visionOS UA.
- The spoof-client setting and README list `visionOS`.

### Measured after the fix (same video, desktop JVM)

| Scenario | Before fix | After fix |
|---|---|---|
| Auto audio resolve | 683 ms (broken URL) | **354 ms**, URL answers 200 |
| Replay (cache) | 0 ms | **0 ms** |
| Auto video resolve | — | **248 ms**, HLS variants 200 |
| Explicit `android_vr` setting | 403 | **920 ms** (403 rejected, falls back to visionOS) |
| Explicit `android_vr` video | 403 | **1068 ms** (falls back to visionOS HLS) |

Users who explicitly selected a broken spoof client no longer 403 — the probe
rejects it and the cascade falls back automatically.

### Android VR removed (follow-up)
`ANDROID_VR` was dropped from `SpoofClient`, the settings list, the cascades and
the README because its URLs are unusable: ranged requests return 206 but normal
playback returns 403. Existing users who still have `android_vr` stored in
settings are unaffected: `SpoofClient.fromId("android_vr")` now returns `null`,
which is the same as `auto`, so they get the visionOS fast path. A test asserts
that `android_vr` is no longer exposed.

### Lesson / guard
The health check must always exercise the request shape used by playback.
`SpoofClientTest` now performs a non-Range GET on the resolved URL and fails if
it is not 2xx/3xx.
