# Streaming Architecture Audit

**Repository:** `echo-youtube-extension` (working copy `E:\echo\echo-youtube-extension-old`)
**Revision audited:** `a728fba` (`main`, working tree clean except untracked `scratch/`)
**Date:** 2026-11-19
**Status:** read-only audit. No production file was modified.

---

## 1. Scope and method

### In scope

- How a track reaches a playable URL: entry point → `TrackLoader` → `YouTubeStreamResolver` → Innertube endpoints → CDN.
- How authentication (cookies, `Authorization`, visitor data) flows into those requests.
- Where a YouTube **PO Token** (`pot=`) would have to be injected, and whether anything already does it.
- Why restricted tracks fail with 403.

### Out of scope (deliberately untouched)

- OAuth, playlist, search, and browsing code paths.
- Any change to working streaming behaviour.
- Using the pasted Google cookie blob. It is treated as compromised and was never read, echoed, or stored. See §7.

### Method

1. Static reading of every file on the streaming path.
2. `git grep` across the repo for PO Token symbols.
3. Unauthenticated, read-only Innertube `player` probes via `scratch/diagnostics/probe-player.ps1` (no cookies, query strings stripped before printing).
4. Cross-check against ytm-kt 0.4.3 dependency sources and yt-dlp's published PO Token policy.

### Evidence labels used below

- **[VERIFIED]** — directly observed in this repo or in a live probe during this audit.
- **[INFERRED]** — follows from code + platform behaviour but was not executed here.
- **[UNVERIFIED]** — plausible, requires credentials or a device to confirm.

---

## 2. Call graph

```
Echo app
  └─ YoutubeExtension                       ext/.../YoutubeExtension.kt:72
       ├─ setSettings(settings)             :127     → syncActiveCookie()
       ├─ syncActiveCookie(cookie)          :136
       └─ TrackClient / PlaybackClient implementation
            └─ TrackLoader                  ext/.../providers/playback/TrackLoader.kt:12
                 ├─ loadTrackDetails()      :19
                 ├─ prefetchAudio()         :40
                 └─ loadStreamableMedia()   :57
                      └─ YouTubeStreamResolver
                           ext/.../streaming/YouTubeStreamResolver.kt:22
                           ├─ resolveStreamable()        :239  (audio/video)
                           │    └─ StreamMediaCache.getOrResolve()  :248
                           │         └─ resolveStreamableInternal() :342
                           ├─ resolveBackground()        :262
                           │    └─ resolveBackgroundInternal()     :277
                           └─ extraction cascade (see §4)
```

`Streamable.Media` is the contract returned to Echo (`dev/brahmkshatriya/echo/common/models/Streamable.kt`). A playable result is either:

- `Streamable.Media.Server(sources, merged)` — a list of `Source.Http` (each carries its own `OkHttp` `Request`, so per-source headers are possible), or
- `Streamable.Media.Background(request)` — a single request for background video.

Both carry a prepared `Request`, which means any future header/token injection belongs **inside source construction**, not in the player.

---

## 3. Authentication and identity

### 3.1 Where cookies live

| Concern | Location |
|---|---|
| Active cookie string | `YouTubeAuthManager.activeCookie` (`getActiveCookie()`, line 49) |
| Auth headers (cookie + `Authorization`) | `YouTubeAuthManager.getFreshAuthHeaders(origin)` line 57 |
| Cookie push into NewPipe | `RealNewPipeVideoFormatsEndpoint.globalCookie` line 29, `cookieUpdateListener` line 32 |
| Cookie sync from settings | `YoutubeExtension.syncActiveCookie()` line 136 |
| SAPISIDHASH + merge | `utils/CookieParser.kt` |

**[VERIFIED]** `getFreshAuthHeaders()` builds `Cookie:` plus a SAPISIDHASH `Authorization` header for a given origin, and refreshes the hash each call.

### 3.2 How each extractor authenticates

| Path | Cookie source | Notes |
|---|---|---|
| `trySpoofedExtractor` → `EchoVideoEndpoint.getSpoofedVideo` | `RealNewPipeVideoFormatsEndpoint.globalCookie` / active cookie | Client context comes from `SpoofClient` |
| `tryRealNewPipeExtractor` | `globalCookie` via downloader interceptor | NewPipe does its own deciphering |
| `tryYtmKtEndpoint` | ytm-kt's `ApiAuthenticationState` | **Filters the cookie header to `__Secure-*` names only** |
| `tryYouTubeMusicApi` | ytm-kt API instance + visitor id | Same filtered jar |
| `tryRestrictedVideoExtractor` | `getRestrictedVideo(id)` (cookie param optional) | WEB_CREATOR / TV lanes |

**[VERIFIED]** In ytm-kt 0.4.3, `ApiAuthenticationState` drops every cookie whose name does not start with `__Secure-`. `SID`, `HSID`, `SSID`, `APISID`, `SAPISID`, `LOGIN_INFO` never reach the request. `__Secure-3PSID` may still be sent, but without `LOGIN_INFO` the account binding is unreliable.

**Consequence:** the ytm-kt lanes should be treated as **effectively anonymous**, regardless of whether the user is signed in. **[INFERRED, high confidence]**

### 3.3 Client profiles

`SpoofClient` (`streaming/SpoofClient.kt`) defines five profiles:

| id | clientName | clientVersion | Origin | Cookie-compatible? |
|---|---|---|---|---|
| `visionos` | VISIONOS | 1.02 | youtube.com | no (app client) |
| `ios` | IOS | 19.34.2 | youtube.com | no (app client) |
| `tv` | TVHTML5 | 7.20230405.08.01 | youtube.com | yes (web-ish) |
| `mweb` | MWEB | 2.20240901.00.00 | m.youtube.com | yes |
| `web_remix` | WEB_REMIX | 1.20260928.13.00 | music.youtube.com | yes |

Only `visionos`/`ios`/`tv` are referenced by the resolver cascade today.

Measured defects in two profiles **[VERIFIED by live probe]**:

- **IOS `19.34.2`** → every request returns `HTTP 400` ("Precondition check failed"). The lane is dead, not merely restricted.
- **MWEB** sends `Origin: https://m.youtube.com` to `www.youtube.com` → `HTTP 400` ("Origin doesn't match Host for XD3"). The lane is dead.
- **WEB_REMIX** returns formats, but its direct URLs require `&pot=`; without it the CDN answers 403.

### 3.4 Visitor identity

`YouTubeAuthManager.ensureVisitorId()` (line 41) and `api.visitor_id` supply `visitorData`. Visitor data is the **content binding** a PO Token must be minted against. Any future token must be bound to the same visitor that produced the player response — a token from another visitor (or another user) is invalid and must never be reused.

---

## 4. Extraction cascade

### 4.1 Audio (`resolveStreamableInternal`, else-branch at line 434)

Order **[VERIFIED]**:

1. `VISONOS` — `trySpoofedExtractor` (line 439)
2. `RealNewPipe` — `tryRealNewPipeExtractor` (line 450), Opus/AAC with JS deciphering
3. Other spoofed clients, in order `IOS → WEB_REMIX → TV` (lines 459–473)
4. `YtmKt` (line 477)
5. `YouTube Music API` (line 487)
6. `Restricted` extractor — `tryRestrictedVideoExtractor` (line 497)

The first `ExtractionResult.Success` wins. Failures are accumulated into `errorReasons` and surfaced in one exception (line 506).

### 4.2 Video (if-branch at line ~365)

1. Explicit `spoof_stream_client` setting, if any (line 358)
2. `VISONOS` (line 372)
3. `IOS → TV` (lines 392–403)
4. `YtmKt` (line 407)
5. `YouTube Music API` (line 417)
6. `Restricted` extractor (line 427)

### 4.3 Things the cascade does *not* do

- It does not distinguish **"age gate / sign-in required"** from **"bot check"** from **"missing integrity token"**. Every non-success becomes an opaque `ExtractionResult.Error(reason)` string.
- It does not attach a PO Token to any request.
- It does not retry a client *with* a token after that client failed *without* one.

**[VERIFIED]** `git grep -n "po_token|poToken|pot=|serviceIntegrityDimensions"` returns **no matches** on `main`. There is no token code, no settings key, and no seam.

---

## 5. Format → playable source, and the 403 point

`extractMediaFromFormatResponse()` (line 770) is where a player response becomes sources.

1. If `streamingData.hlsManifestUrl`/`dashManifestUrl` exists and this is a video request, it returns a sorted HLS source (line 785). HLS is **not** subject to the same `pot=` requirement as direct HTTPS URLs on web clients.
2. Otherwise it concatenates `formats + adaptiveFormats` (line 793).
3. Each format is resolved to a URL: plain `url` → `YouTubeSignatureDeobfuscator`, or `signatureCipher`/`cipher` → `deobfuscateCipherUrl` (lines 808–829).
4. Audio builds the full quality list **without probing each one** (line 922).
5. The **first** source is probed before the whole result is accepted:
   - video with a target height → `probeAllPlayable(video, audio)` (line 878), error at 880
   - video quality list → `probePlayable(sources.first())` (line 895), error at 897
   - muxed fallback → `probePlayable` (line 905)
   - audio → `probePlayable(sources.first())` (line 966), error at 968

### The exact failure surface

```
ext/.../streaming/YouTubeStreamResolver.kt:966
    if (!probePlayable(sources.first().request.url)) {
        return ExtractionResult.Error("Audio stream failed health check (HTTP 403 Forbidden)")
    }
```

**Important:** the string *"A valid PO Token is required"* does **not** exist anywhere in this repository **[VERIFIED]**. If a user or log reported that sentence, it came from YouTube's response body, a player UI, or another tool — not from this code. This code reports `Audio stream failed health check (HTTP 403 Forbidden)`.

### Probe implementation

- `probeStream(url)` line 62 — short-timeout GET/HEAD, no cookies attached beyond the URL itself.
- `probePlayable(url)` line 84 — IO dispatcher, ~800 ms budget, timeout/network error → `false`.
- Because the probe only sees the URL, **any credential that isn't already baked into the URL is invisible to it**. That is exactly the `pot=` situation: the token is a query parameter appended at resolve time, and a missing one shows up as 403.

### Caching interaction

`StreamMediaCache` (TTL + LRU + single-flight) keys on `videoId|type|height|spoofSetting` (line 245). On failure the resolver invalidates the matching prefix (line 253), so a fixed extraction will not be masked by a poisoned cache. A successful but soon-to-expire `pot` URL **will** be cached for the TTL, which matters for any token rollout (see roadmap Task 3.4).

---

## 6. Error handling and observability

- All extractor failures collapse into one `Exception` with newline-joined reasons (line 506–511). The UI sees a generic failure; the per-client reason exists only in logs.
- Logging is `println`-based and includes full URLs in several places (e.g. line 538, 787). **URLs can contain `pot`, `sig`, `expire`, and `ip` parameters.** Any token work should stop printing raw URLs, or at least redact `pot`/`sig`.
- There is no metric or counter for "client X failed with status Y". Diagnosis currently requires reading logcat.

---

## 7. Credential handling during this audit

- The pasted Google cookie blob is compromised by definition (it was shared in plaintext). It was **not** used, echoed, stored, or committed.
- All live probes were unauthenticated and read-only. Only `videoId`, `clientName`, `status`, format counts, and HTTP codes were recorded; query strings were stripped.
- No test bypassed age verification, subscription, or DRM. An age gate was observed, not circumvented.

The developer must revoke the exposed sessions in Google Account → Security → Your devices, then populate a **local, untracked** credential file for authenticated testing (§`IMPLEMENTATION_ROADMAP.md`, Phase 0).

---

## 8. Findings summary

| # | Finding | Evidence | Impact |
|---|---|---|---|
| A1 | No PO Token code exists on `main` | `git grep` empty | Web-client formats cannot be played when YouTube demands a token |
| A2 | WEB_REMIX formats need `pot=`; probe sees 403 | live probe + code line 966 | Silent fallback to a worse lane, then generic failure |
| A3 | IOS 19.34.2 lane is dead (HTTP 400) | live probe | Wasted request on every cascade |
| A4 | MWEB origin mismatch → HTTP 400 | live probe | Lane unusable as written |
| A5 | ytm-kt drops non-`__Secure-*` cookies | ytm-kt 0.4.3 source | Signed-in state is not actually used on those lanes |
| A6 | Age-gated video has no formats when signed out | live probe (`LOGIN_REQUIRED`) | No token can fix this; needs auth |
| A7 | Probes only validate the URL, not headers | lines 62–99 | Header-based fixes would be invisible to health checks |
| A8 | Raw URLs are logged | lines 538, 787 etc. | Token/signature leakage into logcat |

**Bottom line:** the architecture is a wide, opaque fallback cascade with a URL-only health check. It has no integrity-token layer and no way to tell "needs a token" from "needs a login". Those are the two gaps the next two documents address.
