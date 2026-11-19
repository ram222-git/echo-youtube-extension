# Serverless (On-Device) PO Token Research

**Date:** 2026-11-19
**Revision audited:** `a728fba` (`main`) + `test` branch (`ddfef90`) + `scratch/` experiments
**Status:** research complete; **Option C implemented and DEVICE-VERIFIED the same day.** Restricted track `hc0ZDaAZQT0` plays signed-in on a real device with `potoken_mode=android` — no PC, no server, no API. Normal playback unaffected. `potoken_mode` default is now On-device.
**Question:** can Echo generate YouTube PO Tokens **on the Android device**, with no PC, no BGUtil server, and no third-party API?

---

## 1. Verdict up front

| Question | Answer | Confidence |
|---|---|---|
| Is on-device PO token generation feasible at all? | **Yes** — via a headless Android `WebView`. | **High** (three independent proofs, incl. this repo's own device run) |
| Is it feasible in pure Kotlin/JVM or a shimmed JS engine (Rhino/QuickJS)? | **No** — BotGuard rejects shimmed environments (null integrity token, measured on this repo). | **High** (empirical, `test` branch) |
| Is the protocol current (Nov 2026)? | **Yes** — the same WAA/JNN protocol (`jnn-pa.googleapis.com`, request key `O43z0dpjhgX20SCx4KAo`) minted successfully on PC on 2026-11-19 (`scratch/pot-provider/mint-out.txt`). | **High** |
| Has anyone shipped this in production on Android? | **Yes** — NewPipe 0.27.6 (Feb 2025) `PoTokenWebView`; also LibreTube, OuterTune, PipePipe, ArchiveTune, SmartTube. | **Certain** |
| Remaining risk | Device WebView version/availability variance; YouTube protocol rot. Both degrade gracefully to today's behaviour. | — |

**Recommended approach:** Option C — headless WebView minter, exposed through the **existing** `PoTokenProvider` seam as a new `AndroidPoTokenProvider`, with the external BGUtil provider kept as an optional fallback. Zero new Gradle dependencies.

This repo has a **prior, device-validated implementation** of exactly this design on the `test` branch (`streaming/YouTubePotProvider.kt`, 673 lines, commit `ddfef90`). Its header records the decisive experimental finding:

> "the BotGuard attestation is rejected when the interpreter runs in a shimmed JS engine (verified empirically — the server returns only a null integrity token). A real browser engine yields a valid integrity token, and the minted token lifts googlevideo's 403 range caps, unlocking ALL audio/video qualities for age-restricted videos."

That is a real device observation: WebView → valid integrity token → minted token → googlevideo 200.

---

## 2. Background: what a PO Token is and what mints it

### 2.1 The two gates (see `PO_TOKEN_ROOT_CAUSE.md`)

1. **Authorization gate** (age/account): no formats returned. Fixed by login, never by a token.
2. **Integrity gate** (PO Token): formats returned, but `googlevideo.com` answers **403** unless the stream URL carries `pot=<token>`.

This project only needs to solve gate 2, for the **WEB_REMIX** (`web_music`) lane, used when a track requires a signed-in web client.

### 2.2 Which clients need a GVS PO Token (yt-dlp Po Token Guide, current)

| Client | GVS `pot` required? |
|---|---|
| `web`, `mweb`, `web_music` (WEB_REMIX), `web_creator`, `tv_simply` | **Yes** |
| `android`, `ios` | GVS *or* player token |
| `tv`, `web_embedded`, `android_vr` | **No** |
| Everyone with YouTube Premium | Not enforced (signed-in premium bypasses) |
| HLS live streams | No (except `ios`) |

Matches this repo's `StreamTokenPolicy.kt` (`WEB`, `WEB_REMIX`, `MWEB`, `WEB_CREATOR` → token-required; visionOS/TVHTML5 → token-free). The repo's token-free TV/VISIONOS lanes remain the primary path; the token only unlocks the authenticated WEB_REMIX lane for age-restricted/account-locked tracks.

### 2.3 Binding and TTL

- GVS tokens are **bound to the video ID** ("Most PO Tokens (such as for web GVS/Player) are bound to the video ID, so a new token is required for each video" — yt-dlp Po Token Guide). This matches the implemented `PoTokenProvider.token(videoId)` contract.
- Player-request tokens (bound to `visitorData`) are a separate mechanism (`serviceIntegrityDimensions.poToken`) — **not needed** today; the same minter can produce them later if YouTube starts bot-checking player calls.
- TTL: "possibly as short as 12 hours" (yt-dlp wiki). Measured on this repo's PC mint: **`ttlSeconds: 43200` (12 h)**. The `test` branch conservatively used 6 h for cached tokens and rebuilt the minter at ~12 h.

### 2.4 The minting protocol (WEB BotGuard → "WAA"/JNN)

All four steps are HTTP + JS; no native Google libraries involved:

1. **Challenge** — `POST https://www.youtube.com/api/jnn/v1/Create` with `[requestKey]` (`O43z0dpjhgX20SCx4KAo`, `Content-Type: application/json+protobuf`, `x-goog-api-key: AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw`). Response is descrambled (`byte + 97`) into `{program, globalName, interpreterJavascript}`. The **interpreter JS ships inline** in the response — no second fetch needed.
2. **Run BotGuard** — `new Function(interpreterJs)()` installs the obfuscated VM (`window[globalName]`), then `vm.a(program, vmFunctionsCallback, …)`; poll for `asyncSnapshotFunction`; call it with `webPoSignalOutput` array. This is the only step that needs a **real browser environment**.
3. **Integrity token** — `POST …/Waa/GenerateIT` (bgutils-js builds `https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1/Waa/GenerateIT`; the `www.youtube.com/api/jnn/v1/GenerateIT` proxy path is equivalent) with `[requestKey, botguardResponse]` → `[integrityTokenBase64, estimatedTtlSecs, mintRefreshThreshold, websafeFallback]`.
4. **Mint** — feed the integrity token to `webPoSignalOutput[0]` to get the minter function; `minter(videoIdBytes)` → websafe-base64 **PO Token** (≈700 chars).

bgutils-js 4.0.3 (LuanRT, MIT) implements exactly this; the newer homepage-`window.ytAtN` / `/youtubei/v1/att/get` challenge source is an alternative entry to the same VM. **No cookies, no account, no visitor data are required for GVS minting** — the flow is anonymous.

---

## 3. Evidence: this repo's own experiments

| Artifact | What it proves | Where |
|---|---|---|
| `test` branch `YouTubePotProvider.kt` (673 lines) | Headless WebView minting state machine, Kotlin-driven HTTP, reflection Context, engine reuse, renderer-crash handling, JVM-safe no-op | `git show test:ext/.../streaming/YouTubePotProvider.kt` |
| Same file, header comment | **Shimmed JS engines are rejected** (null integrity token); WebView mint succeeded and a minted token lifted googlevideo 403s on device | commit `ddfef90` |
| `PotMintTest.kt` (test branch) | End-to-end mint + URL probe harness; JVM gracefully returns `null` | `git show test:ext/src/test/.../PotMintTest.kt` |
| `scratch/pot-provider/mint.mjs` (Nov 2026) | Current protocol validity: bgutils-js 4.0.3 + jsdom minted **ok:true, tokenLength 700, ttl 43200 s** on PC | `scratch/pot-provider/` |
| `scratch/pot-provider/server.mjs` | The external BGUtil-compatible lane used for the one authenticated end-to-end pass recorded in the task | `scratch/pot-provider/` |
| Current `main` implementation | Provider seam, video-ID binding, single retry, redaction, token-free client chain all already in place | `streaming/PoTokenProvider.kt`, `StreamTokenPolicy.kt`, `YouTubeStreamResolver.kt` |

---

## 4. Option comparison

| Approach | External server | Android support | Complexity | Recommendation |
|---|---|---|---|---|
| Native Kotlin (re-implement BotGuard) | None | **Infeasible** | Extreme | **Reject** |
| Embedded JS engine (Rhino / QuickJS + DOM shims) | None | **Rejected empirically** | Very high | **Reject** |
| Embedded Node.js (nodejs-mobile + bgutils-js/jsdom) | None | Possible but heavy | High | **Reject** (fallback only if WebView proves unreliable) |
| **Headless Android WebView** | **None** | **Proven** (this repo + 5 production apps) | Medium | **Adopt** |
| Existing Android library (Maven artifact) | Depends on library | No maintained artifact exists | — | Not available |
| External HTTP provider (bgutil) — status quo | **Yes** (user-hosted) | Works | Low | **Keep as optional fallback** |

### Option A — Native Kotlin/JVM

There is no Kotlin/JVM implementation of BotGuard anywhere. The BotGuard VM is a **changing, obfuscated JavaScript program** delivered per-challenge; "porting" it means maintaining a hostile interpreter forever. Nobody in the ecosystem has done it; yt-dlp, Invidious, NewPipe all run the VM in a JS engine instead. **Reject.**

### Option B — Embedded JS engine (Rhino is already bundled; QuickJS available)

Two independent kill reasons:

1. **Empirical (this repo):** the `test` branch verified that the attestation server returns **only a null integrity token** when the BotGuard interpreter runs in a shimmed JS engine. The VM actively fingerprints its host environment; jsdom-style fakes pass on Node (that's how bgutils-js works on PC) but nobody has built an environment shim good enough for Android JS engines — and the shim would need to keep pace with YouTube's environment probes.
2. **Ecosystem:** `inv-sig-helper` (Invidious) uses embedded QuickJS **only for signature deciphering**; its PO-token path historically required real browsers (the project later moved to Invidious Companion). No maintained QuickJS/Rhino PO-token implementation exists for the current protocol.

Additionally, Rhino (already in the project for `base.js` deciphering) is slow for the BotGuard VM and has ES-feature gaps in obfuscated code. QuickJS would need a from-scratch DOM/browser shim (canvas, navigator, performance, XHR…) — exactly the "do not assume executing JavaScript is sufficient" trap the task warned about. **Reject.**

### Option C — Headless Android WebView ✅

The WebView **is** a real Chromium browser environment, so BotGuard runs unmodified, exactly as it does on youtube.com. This is now the standard on-device pattern on Android:

| Project | Mechanism | Status |
|---|---|---|
| **NewPipe** 0.27.6+ | `PoTokenWebView` (Stypox): hidden WebView, `@JavascriptInterface` bridge, `jnn-pa.googleapis.com` requests | **Production since Feb 2025** |
| **LibreTube / OuterTune / PipePipe** | WebView PO token generator (PipePipe: headless WebView, network loads disabled, local JS only) | Production |
| **ArchiveTune** | WebView BotGuard ("web client PO Token" toggle in Account settings) | Production |
| **SmartTube** | `PoTokenWebView`/`PoTokenWebView2` ports | Production |
| **This repo (`test` branch)** | `YouTubePotProvider` — same design, device-validated | Prototype |

Properties of the WebView design (as prototyped on `test`):

- **All HTTP is driven from Kotlin (OkHttp), not from the WebView.** The WebView only executes the VM and the minter. No cookies are configured on the OkHttp clients used for `Create`/`GenerateIT`, so **Google session cookies never touch minting traffic**. The WebView loads a static bootstrap HTML via `loadDataWithBaseURL` (baseURL `youtube.com` so the VM sees a plausible origin); remote page loads can be blocked (`blockNetworkLoads`).
- **Context acquisition** — the Echo extension API (`echo.common`) exposes **no `Context`**; the `test` branch resolves it by reflection (`ActivityThread.currentApplication` → `AppGlobals.getInitialApplication` fallback). This worked inside Echo's process. Risk: reflectively-fragile, but it degrades to "provider unavailable → token-free lanes" rather than crashing, and can be augmented by calling `setContext()` if a host ever provides one.
- **Threading** — WebView must be created and used on the main thread (`Dispatchers.Main` in the prototype). Minting happens during track load (foreground); the renderer can be killed when the app is backgrounded → handled via `onRenderProcessGone` → lazy re-create.
- **Resource cost** — one WebView ≈ 30–80 MB in the renderer while the engine is alive; closed on demand (`destroy()`), reused for the integrity-token lifetime (~12 h). Cold boot measured budget 8 s, warm mint 4 s (timeouts in the prototype; typical warm mint is well under 1 s in browser-like environments).
- **WebView version dependence** — BotGuard targets modern Chromium; very old WebView providers (Android 5/6 with frozen WebView, rare) may fail. Every failure path returns `null` → the resolver falls through to token-free clients; PipePipe's WebView-version complaint is the known trade-off of this approach.

### Option D — Existing libraries / nodejs-mobile

- **No Maven library** publishes Android PO-token generation (checked: `bgutils-js` is JS; `rustypipe-botguard` is Rust/server-side; `bgutil-ytdlp-pot-provider` is a Node server; NewPipe's code is app-embedded **and GPLv3 — must not be copied** into this MIT/Apache-licensed codebase; the `test` branch implementation was written per the published protocol instead).
- **nodejs-mobile** (real Node inside Android) could run bgutils-js + jsdom unchanged, but: last release Oct 2024 (dormant), +~25 MB APK per ABI, +100 MB-class memory for Node+jsdom, and jsdom already needed a canvas stub warning on PC. Heavier and less maintained than Option C for strictly worse fidelity. **Reject** (document as contingency).

### External provider (status quo) — keep

`SettingsPoTokenProvider` + `ExternalPoTokenProvider` (bgutil-compatible `/get_pot`) stay exactly as-is, as an **optional** fallback. Nothing in this research requires removing or weakening them.

---

## 5. Security assessment (Option C)

| Requirement | How the design satisfies it |
|---|---|
| Never transmit Google cookies to a token service | Minting HTTP is Kotlin/OkHttp with no cookie jar; the WebView never navigates to youtube.com and can run with `blockNetworkLoads = true` |
| Tokens never leave the device | Minted in-process; in-memory cache only; never logged (existing redaction applies) |
| No credentials in repo | Nothing new to store; `setContext` accepts an injected context, reflection only *reads* |
| No bypassing of access controls | The token makes **authorized** web-client playback work; age gate still requires login; DRM/premium content untouched |
| JS executed in the WebView | Only the bootstrap HTML bundled in the extension and the challenge interpreter served by Google's own attestation endpoints (same code Chrome runs); no third-party scripts |
| Renderer isolation | BotGuard runs in the WebView renderer process, sandboxed by Android/Chromium; bridge surface is one `@JavascriptInterface` object with four callbacks, no filesystem/device access |

Residual risks (accepted, mitigated): reflection-based Context lookup may fail on exotic OEMs → provider reports unavailable; a compromised/old WebView provider could theoretically mishandle the attestation page — same exposure as any app that uses WebView at all.

---

## 6. Performance and battery

| Metric | Expectation | Basis |
|---|---|---|
| First-token (cold: WebView boot + VM + mint) | ~2–8 s, once per ~12 h | Prototype timeouts; browser-VM timings |
| Subsequent tokens (warm minter) | ~0.1–4 s per video ID, concurrent-safe (single mint at a time) | Prototype; NewPipe behaviour |
| Steady-state memory | +30–80 MB renderer while engine alive; ~0 after `destroy()` | WebView renderer typical |
| Battery | Negligible: headless, no rendering, no polling; engine torn down when idle | Design |
| Impact on normal songs | **None** — visionOS/TV lanes never request tokens; the provider is consulted only for token-required lanes | Existing resolver policy |

---

## 7. Limitations (honest list)

1. **Needs a device WebView** — unavailable/frozen WebView ⇒ provider silently unavailable (current behaviour preserved).
2. **Protocol rot** — request key, `jnn` endpoints, and the VM change over time (they have before; e.g. the newer `att/get` challenge source). Same maintenance exposure as bgutil/yt-dlp; fixes are small (URL/key/descramble constants) once upstream documents them.
3. **Age gate is not a token problem** — signed-out users still cannot play age-restricted tracks; the token only completes the signed-in web-client lane.
4. **Player-request tokens** (visitor-bound) are out of scope; if YouTube starts bot-checking player calls, the same minter extends.
5. **JVM unit tests can never mint** (no Android) — provider must be unit-tested as a no-op and validated on device (Phase 4).
6. **Prior prototype is on a branch** — the `test` branch code predates the current `PoTokenProvider` seam; Phase 3 must port it into the seam (not re-merge the branch), preserving all `main` fixes since `ddfef90`.

---

## 9. Cross-validation against production Android clients (2026-11-19)

Post-implementation, the on-device design was cross-checked against three shipping YouTube Music clients (sources analyzed locally; all GPL-3.0 — behavior replicated, code not copied):

| Aspect | Metrolist | ArchiveTune | LastWave | Echo extension (adopted) |
|---|---|---|---|---|
| Minting engine | Headless WebView, `api/jnn/v1/Create`+`GenerateIT`, key `O43z0dpjhgX20SCx4KAo`, descramble +97 | same | same | same |
| WebView thread | Main | Main | Main | **Main (fixed after device log showed latch-off)** |
| GVS `&pot=` binding | video ID | video ID | visitorData | video ID |
| Player body `serviceIntegrityDimensions.poToken` | visitor-bound (restricted retry) | **video-bound** | **video-bound** | **video-bound (adopted)** |
| Restricted-song client | WEB_REMIX + cookies + SAPISIDHASH | WEB_CREATOR + cookies | WEB_REMIX + cookies | WEB_REMIX/WEB_CREATOR + cookies |
| Extras | live `signatureTimestamp`, `racyCheckOk/contentCheckOk` | live `signatureTimestamp` | live `signatureTimestamp` | **live timestamp + checks adopted** |
| Engine lifecycle | reuse ~12 h, recreate on expiry/failure | reuse, per-video LRU 200 | reuse + 3-failure circuit breaker | reuse ~12 h, warm-failure rebuild once |

Net findings adopted: (1) the video-bound token must also go into the **player request body** for age-gated content (resolves `400 Precondition check failed`); (2) live `signatureTimestamp` instead of a hardcoded one; (3) `racyCheckOk`/`contentCheckOk` confirmed already present. The three apps disagree on binding direction (Metrolist vs LastWave are opposites) — YouTube evidently accepts video-bound tokens on both surfaces when the session is authenticated; Echo's combination (video-bound on both) matches ArchiveTune and was device-verified.

A controlled binding experiment (`ext/src/test/.../RestrictedBindingDiagnosticTest.kt`, requires credentials + local minter) additionally showed the CDN was, at experiment time, serving this track without any `pot` at all — enforcement is adaptive; the token layer remains necessary as enforcement tightens and for account-locked content.

## 10. Sources

- yt-dlp wiki — Po Token Guide (client matrix, video-ID binding, TTL): https://github.com/yt-dlp/yt-dlp/wiki/Po-Token-Guide
- bgutils-js 4.0.3 (LuanRT, MIT; WAA endpoints, request key): https://github.com/LuanRT/bgutils-js — local copy at `scratch/pot-provider/node_modules/bgutils-js`
- bgutil-ytdlp-pot-provider (Brainicism; external server used as the optional fallback): https://github.com/Brainicism/bgutil-ytdlp-pot-provider
- NewPipe 0.27.6 release notes (WebView PoToken shipped): https://newpipe.net — implementation `PoTokenWebView` (author Stypox), call path visible in https://github.com/TeamNewPipe/NewPipe/issues/11999
- PipePipe issue on headless WebView token generation: https://github.com/InfinityLoop1308/PipePipe/issues/2688
- inv-sig-helper (QuickJS used for decipher only; PO tokens needed real browser): https://github.com/iv-org/inv_sig_helper
- nodejs-mobile (last release Oct 2024): https://github.com/nodejs-mobile/nodejs-mobile
- rustypipe-botguard (Rust, server-side): https://github.com/ThetaDev/rustypipe-botguard
- This repo: `test` branch `YouTubePotProvider.kt` + `PotMintTest.kt` (device evidence); `scratch/pot-provider/mint.mjs` + `mint-out.txt` (protocol currency, 2026-11-19)

**Recommendation:** proceed to Phase 2 architecture — port the `test`-branch WebView minter behind the existing `PoTokenProvider` seam as `AndroidPoTokenProvider`, chain `android → external → off`, keep every failure path a silent no-op for token-free playback.
