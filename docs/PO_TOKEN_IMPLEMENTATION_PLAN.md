# PO Token Implementation Plan

**Revision:** `a728fba` (`main`) — updated 2026-11-19 with the on-device architecture (§10), researched in `PO_TOKEN_SERVERLESS_RESEARCH.md`
**Constraint:** no new dependencies, no mandatory paid service, no hardcoded/committed tokens, no shared tokens between users.
**Status:** §1–9 implemented on `main` (provider seam + external bgutil provider + token-free client chain). §10 is the approved-scope proposal for the on-device provider.

---

## 1. Design principles

1. **Token-free first.** If a client can stream without a PO Token, prefer it. A token layer is added only where it measurably unlocks playback.
2. **One seam, many sources.** The resolver must not know whether a token comes from an external server, a future on-device minter, or nowhere.
3. **Per-visitor binding.** A token is valid only for the `visitorData` it was minted for. Tokens are never persisted to disk, never logged, never sent to another user.
4. **Fail open to the next lane.** A missing or failed token degrades to the existing cascade; it must not break ordinary playback.
5. **No secret in the repo.** Provider URL is user configuration; the token is runtime memory only.

---

## 2. Options considered

### Option A — External HTTP provider (`bgutil-ytdlp-pot-provider`)

A sidecar server (Node.js or Docker) exposes `POST /get_pot` and returns a token bound to `content_binding` (= `visitorData`).

| Pros | Cons |
|---|---|
| Proven, actively maintained, matches yt-dlp's own approach | Requires the user to run a server reachable from the phone |
| No new Gradle dependency (plain OkHttp + JSON) | Adds an operational failure mode (server down → silent fallback) |
| Works for every web client, including WEB_REMIX | Token bound to visitor; server must be reachable on LAN/VPN |
| Can be self-hosted at $0 | Not viable for a "just works" consumer default |

**Verdict:** good optional provider, bad default.

### Option B — Provider adapter interface (seam only)

Define `PoTokenProvider` with `OffPoTokenProvider` as the default. No token source ships enabled. The resolver asks the provider only for clients that need a token, and tolerates `null`.

| Pros | Cons |
|---|---|
| Zero behavioural change until a provider is configured | Does not by itself fix any 403 |
| Small, testable, no dependency | Still needs a real source eventually |
| Lets A, C, or a future on-device minter plug in without touching the resolver | |
| Keeps tokens out of the extraction cascade's control flow | |

**Verdict:** required architecture, not a standalone fix.

### Option C — Configurable external service (user-supplied URL)

Same contract as A, but the URL is a setting. Effectively **B + A's transport**, with no bundled service and no default endpoint.

| Pros | Cons |
|---|---|
| User controls hosting and trust boundary | Same "server must exist" problem as A |
| No vendor lock-in, no shared public endpoint | Slightly more settings UI |
| Works with bgutil or any compatible minter | Cannot be validated automatically beyond `/ping` |

**Verdict:** this is how A should be exposed. Bundle as one deliverable (B seam + C transport), default `off`.

### Option D — Token-free client chain (TVHTML5 / VISIONOS)

Route playback through clients that do not require a GVS PO Token for direct HTTPS URLs.

Evidence and caveats:

- yt-dlp's current client policy has **no GVS PO Token requirement for `visionos` and `tv`**; WEB/WEB_REMIX/MWEB/WEB_CREATOR do require one for HTTPS/DASH. **[High confidence, external policy]**
- VISIONOS already works today for the control video: 19 formats, direct URLs, **CDN 200** **[Verified]**.
- TVHTML5 is the only token-free client that is also **cookie-compatible**, which matters for age-gated content. The prior branch's evidence points to `TVHTML5_DOWNGRADED (5.20260707)` → `TVHTML5_CURRENT (7.20260707.07.00)` as the working cookie chain. **[Unverified on `main`]**
- **Measured on `main`:** both TV versions answer `UNPLAYABLE — "The page needs to be reloaded."` when called **without** a session, so the implemented cascade only tries TV when a cookie is present. Signed-in behaviour still needs Phase 1 confirmation. **[Verified, unauthenticated]**
- TV/VISIONOS do not solve the age gate by themselves; they only become useful **after** a valid session.

| Pros | Cons |
|---|---|
| No new dependency, no external service, $0 | Cannot play age-gated tracks without login |
| Works offline / on-device | TV client IDs/versions rot; needs a version policy |
| Fixes the WEB_REMIX 403 class by avoiding web clients entirely | Does not help if YouTube starts requiring tokens on TV too |
| Simple to test with the existing probe | Audio quality/format differences per client |

**Verdict:** best default. It removes the *need* for a token on the common path.

---

## 3. Recommendation

**Do D first, build B as the seam, expose A through C as an opt-in.**

```
                 ┌─────────────────────────────┐
   resolver ────▶│  PoTokenProvider (B)        │
                 │  ├─ OffPoTokenProvider      │  default
                 │  └─ ExternalHttpProvider(C) │  opt-in, user URL
                 └─────────────────────────────┘
   primary lane: TVHTML5 (cookie) → VISIONOS (no cookie)   (D)
   fallback:     existing cascade, unchanged
```

Rationale:

- D fixes the reported class of failure (web-client 403) without asking the user to run anything.
- B costs ~150 lines and prevents a second refactor when a provider is added.
- A/C remain available for users who need WEB_REMIX-only features or when TV/VISIONOS regress.
- Nothing about D or B requires a paid service, a shared token, or a committed secret.

**Do not** make the WebView BotGuard minter (`test` branch, `YouTubePotProvider.kt`, 673 lines) part of the baseline. It depends on `android.webkit` and reflection into `ActivityThread.currentApplication`, has no guaranteed `Context` in the extension sandbox, and is the highest-maintenance option. Keep it as a future third provider behind B if on-device minting becomes mandatory.

---

## 4. Target architecture

### 4.1 New files

```
ext/src/main/java/dev/brahmkshatriya/echo/extension/streaming/
  PoTokenProvider.kt        interface + OffPoTokenProvider
  ExternalPoTokenProvider.kt  HTTP transport (OkHttp + kotlinx.serialization, both already present)
  StreamTokenPolicy.kt      client → requires-token mapping + token append helper
```

### 4.2 Interface

```kotlin
interface PoTokenProvider {
    val kind: String                                   // "off" | "external"
    suspend fun token(contentBinding: String?): String?
    fun invalidate()
}

object OffPoTokenProvider : PoTokenProvider {
    override val kind = "off"
    override suspend fun token(contentBinding: String?) = null
    override fun invalidate() = Unit
}
```

Notes:

- `contentBinding` is the **same** `visitorData` string used to build the player request. If it is null/blank, return `null` — never mint against a guessed binding.
- Cache in memory only: `Map<binding, (token, expiresAt)>` with a conservative TTL (≤30 min), cleared by `invalidate()`.
- `invalidate()` is called on logout, account switch, visitor change, and settings change.

### 4.3 Transport contract (Option C)

```
POST {base}/get_pot
  {"content_binding": "<visitorData>"}
→ 200 {"poToken": "...", "contentBinding": "...", "expiresAt": "..."}
GET {base}/ping
```

- 2 s connect / 5 s read timeout; failure ⇒ `null`, never an exception to the resolver.
- Do **not** send an `Origin` header (bgutil rejects it).
- Accept `poToken` and `po_token` keys defensively.
- Never log the response body.

### 4.4 Policy

| Client | Direct HTTPS needs `pot`? | Behaviour |
|---|---|---|
| VISIONOS | no | never ask for a token |
| TVHTML5 | no (per current policy) | never ask; verify in Phase 2 |
| WEB_REMIX | **yes** | ask; if `null`, **skip the lane** instead of burning a probe |
| WEB / MWEB / WEB_CREATOR | **yes** | ask; if `null`, skip |

Implementation shape:

```kotlin
internal fun SpoofClient.requiresStreamToken(): Boolean = when (this) {
    SpoofClient.WEB_REMIX -> true
    else -> false            // visionos/tv/ios/mweb handled explicitly after Phase 2
}

internal fun String.withPoToken(token: String?): String {
    if (token.isNullOrBlank()) return this
    val uri = toHttpUrl()
    if (uri.queryParameter("pot") != null) return this   // never duplicate
    return uri.newBuilder().addQueryParameter("pot", token).build().toString()
}
```

Do **not** string-concatenate `&pot=`: URLs may already contain `pot` (replace, don't append), and `pot` must be URL-encoded.

### 4.5 Resolver changes

`extractMediaFromFormatResponse()` currently receives no client identity. Add it:

```kotlin
private suspend fun extractMediaFromFormatResponse(
    videoId: String,
    videoResponse: YoutubeFormatResponse,
    isVideoRequest: Boolean,
    targetHeight: Int? = null,
    isMusicOrigin: Boolean = false,
    client: SpoofClient? = null,          // NEW
    visitorData: String? = null,          // NEW, same value used for the player call
): ExtractionResult
```

Then, immediately before building sources:

```kotlin
val token = if (client?.requiresStreamToken() == true && poTokenProvider.kind != "off") {
    poTokenProvider.token(visitorData)
} else null

if (client?.requiresStreamToken() == true && token == null) {
    return ExtractionResult.Error("${client.displayName} needs a PO Token; no provider configured")
}
```

and apply `url.withPoToken(token)` to every format URL. Keep the existing probe guard; on a 403 **with** a token, call `invalidate()`, re-mint once, re-probe once, then fall through to the next lane.

Cache interaction:

- Include a non-reversible visitor fingerprint (or a generation counter bumped by `invalidate()`) in `StreamMediaCache` keys, and clear the cache on logout/account switch. Otherwise a URL minted for visitor A can be served to visitor B.
- Never cache a token **string** in `StreamMediaCache`; only cache the resolved media, as today.

### 4.6 Settings

| Key | Values | Default | Visible |
|---|---|---|---|
| `potoken_mode` | `off` / `external` | `off` | advanced only |
| `potoken_url` | URL string | empty | advanced only, when mode = external |

Do not add a free-text token field. Users must not paste tokens: a pasted token is bound to someone else's visitor and is a credential leak.

### 4.7 Logging hygiene

- Replace raw URL logging with a redactor that strips `pot`, `sig`, `lsig`, `n`, `expire`, `ip`, `ei`, `ns`.
- Never log `Authorization`, `Cookie`, `visitorData`, or a token, even at debug level.
- Error messages may say "needs a PO Token" but must not include the token or the binding.

---

## 5. Changes required, by file

| File | Change | Risk |
|---|---|---|
| `streaming/PoTokenProvider.kt` *(new)* | Interface + `OffPoTokenProvider` | none |
| `streaming/ExternalPoTokenProvider.kt` *(new)* | HTTP transport, in-memory cache, `invalidate` | low |
| `streaming/StreamTokenPolicy.kt` *(new)* | `requiresStreamToken`, `withPoToken` | low |
| `streaming/YouTubeStreamResolver.kt` | Pass client + visitor into `extractMediaFromFormatResponse`; apply token; skip token-required lanes when `off`; invalidate+retry once | **medium** — touches the extraction cascade |
| `endpoints/EchoVideoEndpoint.kt` | Thread the `visitorData` used for the player call back to the caller | low |
| `YoutubeExtension.kt` | Register settings, construct provider, `invalidate()` on auth changes | low |
| `auth/YouTubeAuthManager.kt` | Expose the current visitor id (read-only) | low |
| `RealNewPipeVideoFormatsEndpoint.kt` | Clear token/cache on cookie update | low |

No change to `Streamable`, `TrackLoader`, OAuth, playlists, search, or browsing.

---

## 6. Rollout and rollback

**Rollout**

1. Ship the seam + `off` default (no behaviour change).
2. Ship the token-free client chain (flag `spoof_stream_client` already exists; no new flag needed).
3. Ship the external provider behind the advanced setting.

**Rollback**

- Set `potoken_mode=off` → token code is a no-op.
- Set `spoof_stream_client` back to `visionos` → unchanged legacy behaviour.
- No migration, no stored state, no schema.

---

## 7. Cost and dependency analysis

| Item | Cost | Dependency |
|---|---|---|
| Token-free chain (D) | $0 | none |
| Provider seam (B) | $0 | none (uses existing OkHttp + kotlinx.serialization) |
| bgutil self-hosted (A/C) | $0 software; user supplies a host (PC, NAS, Raspberry Pi) | external process, optional |
| Hosted bgutil | optional, user's choice; **not required** | none in-app |
| WebView BotGuard | $0 | `android.webkit`, fragile reflection; **not baseline** |

No new Gradle coordinates. No paid API. No shared infrastructure.

---

## 8. Why not the alternatives

- **Hardcode a public token / share one token:** violates the binding to `visitorData`, is a credential, and breaks on rotation. Rejected.
- **Mint on every request:** unnecessary CDN load and latency; tokens are cacheable per visitor for their TTL.
- **Put the token in a global header:** `pot` is a per-URL query parameter for GVS; headers are not equivalent. (Player-level tokens use `serviceIntegrityDimensions.poToken` in the request body — a different mechanism, only needed if player requests themselves get bot-checked. Out of scope for Phase 2/3.)
- **Proxy all media through a server:** shifts cost and privacy, and is not needed for the observed failure. Rejected.

---

## 9. Success criteria

- Ordinary tracks play with `potoken_mode=off` (no regression).
- `hc0ZDaAZQT0` plays **only** when signed in — and the fix is attributed to auth, not to a token.
- A web-client track that 403s today returns 200 when a valid provider is configured, with the same `visitorData` binding.
- No token, cookie, `Authorization`, or `visitorData` appears in logs or crash reports.
- Turning the provider off restores the previous behaviour exactly.

---

## 10. Option E — on-device Android provider (WebView minter)

Researched in `PO_TOKEN_SERVERLESS_RESEARCH.md` (verdict: feasible, proven on this repo's `test` branch and in production by NewPipe 0.27.6+). This section is the implementation contract.

**Status (2026-11-19): §10.1–§10.6 complete — DEVICE-VERIFIED.** Restricted-track playback with the on-device minter confirmed on a real device; default flipped to `android`. Post-verification upgrades from the Metrolist/ArchiveTune/LastWave analysis: the video-bound token is also sent in the web-client player request body (`serviceIntegrityDimensions.poToken`, wired via `EchoVideoEndpoint`), and player requests use a live `signatureTimestamp` (NewPipe Extractor) instead of a hardcoded value.

### 10.1 Architecture

```text
YouTubeStreamResolver            (unchanged cascade; token lanes only when a token is obtainable)
        |
        v
SettingsPoTokenProvider          (unchanged class name; becomes a chain)
        |
        v
PoTokenProvider interface        (existing — kind: "off" | "android" | "external")
        |
        +---- AndroidPoTokenProvider   NEW  headless WebView minter (ported from `test`
        |                              branch YouTubePotProvider, reworked for the seam)
        +---- ExternalPoTokenProvider  existing, untouched, optional fallback
        +---- OffPoTokenProvider       existing, default until the device path is proven
```

No new Gradle dependencies. No `PoTokenManager` class is introduced: `SettingsPoTokenProvider` already reads settings per call and already handles selection/invalidation — adding a parallel manager would duplicate it.

### 10.2 Components

**`AndroidPoTokenProvider`** (`streaming/AndroidPoTokenProvider.kt`, new — port of `test`-branch `YouTubePotProvider.kt`):

- Headless WebView created via reflection-resolved app `Context` (`ActivityThread.currentApplication` → `AppGlobals.getInitialApplication`), or an injected context if ever available. No context ⇒ provider reports unavailable, never throws.
- Kotlin-driven state machine: bootstrap HTML via `loadDataWithBaseURL("https://www.youtube.com")` → `api/jnn/v1/Create` (OkHttp, descramble +97) → `evaluateJavascript(runBotGuard)` → `GenerateIT` → `evaluateJavascript(createPoTokenMinter)` → per-video `mint(videoId)` via JS bridge.
- **All HTTP from OkHttp with no cookie jar; WebView loads no remote pages** (`blockNetworkLoads = true`; falls back to `false` only if the VM requires a fetch — verify on device).
- Engine reuse for the integrity-token lifetime (~12 h); conservative token cache TTL 6 h; single mint at a time (mutex); concurrent duplicate requests coalesce.
- Failure policy: every failure returns `null`; renderer-gone ⇒ engine rebuild once, then a permanent-broken flag for the session (no per-track retry storm). `invalidate()` clears the token cache and destroys the engine.
- Availability signal: `kind` returns `"off"` when permanently broken **and** no external URL is configured, so the resolver never burns a request on the WEB_REMIX lane without a token source.

**`SettingsPoTokenProvider`** (modify, ~20 lines):

- `potoken_mode` gains value `android` (and becomes the recommended setting once device-proven; default stays `off` until then).
- Chain: `android` mode ⇒ try `AndroidPoTokenProvider`, then external (if URL set); `external` mode ⇒ external only (today's behaviour preserved exactly); `off` ⇒ off.
- `invalidate()` cascades to both.

**Resolver / lifecycle** (existing behaviour kept):

- Token lanes, single 403 retry with `invalidate()`, video-ID binding, `sessionSuffix()` cache isolation, redaction — all already correct; no resolver changes expected beyond none.
- Account switch/logout: resolver's `sessionKey` already isolates cached media; `invalidate()` on auth changes clears token caches (wire-up exists for the external provider; extend to the chain).

### 10.3 Security contract (mandatory)

- No cookies, `Authorization`, or account data in minting traffic (verified by design: OkHttp without cookie jar; WebView blocked from network).
- Tokens stay in process memory; never logged, never persisted. Log lines may carry token **length** only (existing pattern).
- The bridge surface is one `@JavascriptInterface` object with four callbacks; the WebView loads only the bundled bootstrap HTML and Google's own attestation interpreter. No third-party scripts, no remote navigation.
- No bypassing of age/DRM/subscription controls — this only completes authorized signed-in web-client playback.

### 10.4 Performance contract

| Metric | Budget |
|---|---|
| Cold engine boot (once / ~12 h) | ≤ 8 s, lazy (first token-required track only) |
| Warm mint | ≤ 4 s hard timeout; typically < 1 s |
| Memory | +30–80 MB renderer while engine alive; destroyed on demand |
| Normal songs | Unaffected — token-free lanes never consult the provider |

### 10.5 Testing

- **Unit (JVM):** chain selection (`off`/`android`/`external`), android-unavailable ⇒ null without affecting external fallback, cache expiry, video-ID binding isolation, invalidate-on-403 semantics, concurrent-mint coalescing (WebView abstracted behind an interface; JVM tests use a fake).
- **Integration (JVM, credential-gated, existing pattern):** extend `AuthenticatedPlaybackTest` with an `ECHO_YT_POT=android` switch.
- **Device (mandatory for acceptance):** install APK, verify — provider init result, mint duration, token length, probe HTTP 200, actual audio playback, restricted track `hc0ZDaAZQT0` signed-in, normal tracks with mode `off` unchanged, logout/login cache clearing. Record Android version + WebView version. No credentials in logs.

### 10.6 Rollout / rollback

- Ship with default `potoken_mode=off` (zero behaviour change). User opts into `android`.
- Flip the default to `android` only after device verification; `off`/`external` remain selectable forever.
- Rollback: set mode `off`; provider code becomes dormant.
