# PO Token Root Cause Analysis

**Symptom under investigation:** restricted track (`hc0ZDaAZQT0`) fails to play with **HTTP 403 Forbidden**. Suspected cause: missing YouTube **PO Token**.
**Revision audited:** `a728fba` (`main`)
**Status:** analysis only. No production code changed.

---

## 1. Verdict up front

| Question | Answer | Confidence |
|---|---|---|
| Does the repo use a PO Token today? | **No.** No token code, setting, or `pot=` construction anywhere on `main`. | **Certain** (verified by `git grep`) |
| Is a PO Token required to play some YouTube streams? | **Yes**, for direct HTTPS URLs served to WEB / WEB_REMIX / MWEB / WEB_CREATOR clients. | **High** |
| Is a missing PO Token the cause of *this* 403? | **Probably**, but only for the web-client lane and only *after* the track resolves. Not confirmed without an authenticated capture. | **Medium — probable, not proven** |
| Can a PO Token unlock `hc0ZDaAZQT0`? | **No.** That video is age-gated. Signed out it returns zero formats; a token cannot substitute for a login. | **High** |
| Exact failing code | `YouTubeStreamResolver.extractMediaFromFormatResponse()` → `probePlayable()` guard at line 966 (audio) / 878 & 895 (video), fed by `EchoVideoEndpoint.tryWebRemix()` (`EchoVideoEndpoint.kt:168`) | **Verified location** |
| Is the literal error "A valid PO Token is required" from this code? | **No.** This code emits `Audio stream failed health check (HTTP 403 Forbidden)`. | **Certain** |

**Short version:** there are **two independent gates**, and they are routinely conflated.

1. **Authorization gate (age / sign-in).** No formats are returned at all. A PO Token is irrelevant.
2. **Integrity gate (PO Token).** Formats are returned, but the direct URL is rejected by the CDN with 403 until a valid `pot=` is appended.

`hc0ZDaAZQT0` hits gate 1 when signed out. The reported 403 hits gate 2, which means the reproduction that produced it had formats — i.e. it was at least partially authenticated, or it used a lane that returns formats but unsigned URLs (WEB_REMIX).

---

## 2. Reproduction evidence (unauthenticated, read-only)

Harness: `scratch/diagnostics/probe-player.ps1` — no cookies, query strings stripped, only status/counts recorded.

Command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scratch\diagnostics\probe-player.ps1 `
  -VideoId hc0ZDaAZQT0 -Clients "visionos,web_remix,tv,ios,mweb"
```

### 2.1 Age-gated video `hc0ZDaAZQT0`

| Client | Result | `streamingData` |
|---|---|---|
| VISIONOS 1.02 | `LOGIN_REQUIRED` — "Sign in to confirm your age" | **absent** |
| WEB_REMIX 1.20260928 | `LOGIN_REQUIRED` — "Sign in to confirm your age" | **absent** |
| TVHTML5 7.20230405 | `LOGIN_REQUIRED` — "Sign in to confirm your age" | **absent** |
| TVHTML5 7.20260707.07.00 | `UNPLAYABLE` — "The page needs to be reloaded." | **absent** |
| TVHTML5 5.20260707 | `UNPLAYABLE` — "The page needs to be reloaded." | **absent** |
| IOS 19.34.2 | `HTTP 400` — "Precondition check failed" | n/a |
| MWEB | `HTTP 400` — "Origin doesn't match Host for XD3" | n/a |

**Reading:** signed out, this track cannot produce a single format. The cascade has nothing to probe, so it never reaches the 403 guard. A PO Token cannot change this outcome; only an authenticated session (or a client that genuinely does not enforce the age gate) can. TVHTML5 needs a session even for normal tracks, so it is gated behind cookie presence in the implemented cascade.

### 2.2 Control video `dQw4w9WgXcQ`

| Client | Result | URL shape | CDN probe |
|---|---|---|---|
| VISIONOS | `OK`, 19 formats | direct (`googlevideo.com/...`) | **HTTP 200** |
| WEB / WEB_REMIX | `OK`, formats present | `signatureCipher` / ciphered | not directly playable without decipher + `pot` |
| TVHTML5 7.20260707 / 5.20260707 | `UNPLAYABLE` — "The page needs to be reloaded." | n/a | n/a |

**Reading:** the resolver's "direct URL" strategy is sound on VISIONOS. On web clients the same response is either ciphered or unsigned-by-token, and the URL-only health check treats that as a dead end.

### 2.3 What was *not* measured

Everything requiring credentials:

- Whether an authenticated VISIONOS/TV request returns formats for `hc0ZDaAZQT0`.
- Whether an authenticated WEB_REMIX URL with a valid `pot=` returns 200.
- The actual CDN 403 response body (does it mention PO Token?).

These are the three experiments that would move confidence from **probable** to **confirmed**. They are specified in `IMPLEMENTATION_ROADMAP.md` Phase 0–1 and must be run by the developer with local-only credentials.

---

## 3. The two gates, precisely

### Gate 1 — Authorization (this track, signed out)

`EchoVideoEndpoint.getSpoofedVideo()` (`EchoVideoEndpoint.kt:76`) treats a response as usable only if it carries playable formats; a `LOGIN_REQUIRED` response without `streamingData` becomes `null` at line 140-ish and the resolver reports:

```
"SpoofClientExtractor: <client> returned no response or playable formats"
→ "Player response contains no streaming data"
```

Notice: **this is not a 403.** If the user's report is literally `403 Forbidden`, they were past gate 1. That is the single most useful diagnostic split in this investigation.

### Gate 2 — Integrity (PO Token)

For web clients, YouTube returns formats whose URLs must carry a valid `pot` query parameter bound to the requesting visitor/session. Without it, `googlevideo.com` answers **403** even though the URL is otherwise correctly signed and unexpired.

The resolver builds those URLs at lines 808–829, then immediately probes the first one:

```kotlin
// YouTubeStreamResolver.kt:966
if (!probePlayable(sources.first().request.url)) {
    return ExtractionResult.Error("Audio stream failed health check (HTTP 403 Forbidden)")
}
```

So a missing `pot=` surfaces as a **generic 403 at the health check**, not as a token-required message. That is why the failure looked like a generic stream problem instead of an auth/integrity problem.

### Why one token cannot fix both

| | Gate 1 (age) | Gate 2 (integrity) |
|---|---|---|
| Trigger | No session / no age attestation | Valid session, web client, no `pot` |
| Response | `LOGIN_REQUIRED`, zero formats | Formats present, CDN 403 |
| Fixed by login | **Yes** | No (still needs token) |
| Fixed by PO Token | **No** | **Yes** |
| Current code path | cascade exhausts, generic error | line 966 / 878 / 895 |

---

## 4. Why the current architecture cannot self-diagnose

1. **Opaque errors.** `ExtractionResult.Error(reason)` collects strings and throws one joined exception (line 506). "Needs login" and "needs token" are indistinguishable to the UI.
2. **URL-only probing.** `probePlayable` (line 84) checks a URL, not the request context. Any future header-based credential is invisible to it, and a `pot` that should have been appended simply isn't there.
3. **No per-client policy.** WEB_REMIX is tried as a normal fallback (line 461) even though its URLs are known to need a token. It can never succeed in that lane; it just burns a request and poisons the error list.
4. **Dead lanes in the chain.** IOS (`19.34.2`) and MWEB both return HTTP 400 for structural reasons (stale version / origin mismatch). They are still in the cascade.
5. **Cipher vs token confusion.** `YouTubeSignatureDeobfuscator` fixes `sig`/`n`; it does nothing for `pot`. A successfully deciphered URL can still 403.

---

## 5. Auth status of the current code

| Claim | Status |
|---|---|
| The extension supports cookie login via WebView | **Verified** (`YoutubeExtension.setLoginUser`, `syncActiveCookie`) |
| Auth headers include `Cookie` + SAPISIDHASH `Authorization` | **Verified** (`YouTubeAuthManager.getFreshAuthHeaders`, line 57) |
| `trySpoofedExtractor` receives the active cookie | **Verified** (via `getSpoofedVideo` and `globalCookie`) |
| ytm-kt lanes actually use the account | **Probable no** — ytm-kt 0.4.3 filters cookies to `__Secure-*`, dropping `SID`/`SAPISID`/`LOGIN_INFO` |
| Any lane mints or appends a PO Token | **No** |

This matters because "signed in" in the UI does **not** imply "every extractor is authenticated". The cascade can silently fall from an authenticated lane into an anonymous one and then fail for a completely different reason.

---

## 6. Root cause statement

> The 403 is produced at `YouTubeStreamResolver.kt:966` (and the equivalent video guards at 878/895) when the first URL of a resolved format list is rejected by the CDN. On web clients (WEB_REMIX in particular) that rejection is **most plausibly** caused by the absence of a `pot` query parameter, because this codebase never mints or appends one and YouTube's current policy requires a GVS PO Token for direct HTTPS playback on WEB/WEB_REMIX/MWEB/WEB_CREATOR.
>
> Separately, `hc0ZDaAZQT0` is age-gated and returns **no formats at all** when unauthenticated, so it cannot be fixed by a PO Token. It requires a valid signed-in session (or a client/region where the age gate is not enforced — none of the tested clients qualified).

**Confidence:** the code location and the absence of token support are **certain**. The causal link "this specific 403 = missing PO Token" is **probable (medium-high)** and becomes **confirmed** only after the authenticated experiments in the roadmap produce a token-free 403 and a token-bearing 200 on the same URL.

---

## 7. Recommended next actions (no code yet)

1. **Reproduce with classification.** Capture the exact error string. `...no streaming data` ⇒ gate 1. `...health check (HTTP 403 Forbidden)` ⇒ gate 2. Do not conflate them in the bug report.
2. **Authenticated capture (developer, local-only).** Confirm whether a signed-in VISIONOS/TV request returns formats for `hc0ZDaAZQT0`. If yes, Gate 1 is solvable by auth alone.
3. **Token experiment.** For a non-age-gated web-client response, compare CDN status with and without an externally minted `pot` bound to the same `visitorData`. Same URL, one variable.
4. **Only then** choose an implementation (see `PO_TOKEN_IMPLEMENTATION_PLAN.md`, which recommends a token-free client chain first, token seam second).

Until step 3 passes, do not describe PO Token support as "the fix". It is one of two fixes, and it is the second one.
