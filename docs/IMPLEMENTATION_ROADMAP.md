# Implementation Roadmap

**Revision:** `a728fba` (`main`)
**Status:** Phase 0, Phase 2, and Phase 3 implemented on 2026-11-19. Phase 1 (authenticated confirmation) and device playback verification are still pending; see the status block below.

---

## 0. Implementation status (2026-11-19)

Done:

- New `TV_CURRENT` (7.20260707.07.00) and `TV_DOWNGRADED` (5.20260707) profiles; MWEB origin fixed.
- Audio/video cascades now prefer token-free clients and only try web clients when a provider is configured.
- `PoTokenProvider` seam, `ExternalPoTokenProvider` (bgutil-compatible), `SettingsPoTokenProvider`, settings entries, per-session cache keys, token invalidation on 403/logout, and URL redaction in logs.
- Unit tests: `StreamTokenPolicyTest` (6), `StreamMediaCacheTest` (6), `SpoofClientTest` non-network (4) — all pass.
- Integration: `SpoofClientTest.testAutoResolutionReturnsPlayableUrl` passes (normal track still resolves and plays without a token).

Measured during implementation (unauthenticated probe):

| Client | `dQw4w9WgXcQ` | Consequence |
|---|---|---|
| `visionos` | OK, 19 formats, CDN 200 | remains the anonymous fast path |
| `tv_current` | `UNPLAYABLE` — "The page needs to be reloaded." | TV is gated behind a session cookie in the cascade |
| `tv_downgraded` | `UNPLAYABLE` — same | same |
| `web_remix` | OK, 19 formats, first audio **ciphered** | token-required lane, only used when a provider is on |

Not verified: authenticated TV playback, a real minted token returning 200, and `hc0ZDaAZQT0` playback. Those need the developer's local account (Phase 1).

Each phase is independently shippable and independently revertible. Do not start a phase until its predecessor's acceptance criteria are met.

---

## Phase 0 — Baseline, harness, and secret hygiene

**Goal:** make the failure measurable and keep credentials out of git.

| ID | Task | Files | Acceptance |
|---|---|---|---|
| 0.1 | Ignore the local credential file | `.gitignore` | `.env.local` listed; `git status` never shows it |
| 0.2 | Auth-capable diagnostic probe | `scratch/diagnostics/probe-player.ps1` | `-EnvFile` loads `ECHO_YT_COOKIE`; value never printed; script parses |
| 0.3 | Record unauthenticated baseline | this doc §6 | statuses for `hc0ZDaAZQT0` and `dQw4w9WgXcQ` on 5 clients |
| 0.4 | Confirm no token code exists | — | `git grep` for token symbols returns nothing on `main` |

### 0.1 Local credential file (developer action, never committed)

```powershell
# .env.local  (repo root, ignored by git)
ECHO_YT_COOKIE=<paste your own cookie header here>
ECHO_YT_VISITOR=<optional visitorData, if you have it>
```

Rules:

- Use **your own** account. The pasted cookie blob from the bug report is compromised and must not be used.
- Revoke the exposed sessions first: Google Account → Security → Your devices.
- Never paste the file contents into chat, an issue, a commit, or a log.
- Delete the file when testing is done.

### 0.2 Commands

```powershell
# Unauthenticated baseline
powershell -NoProfile -ExecutionPolicy Bypass -File .\scratch\diagnostics\probe-player.ps1 `
  -VideoId hc0ZDaAZQT0 -Clients "visionos,web_remix,tv,ios,mweb"

# Authenticated (local only)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scratch\diagnostics\probe-player.ps1 `
  -VideoId hc0ZDaAZQT0 -Clients "tv,visionos" -EnvFile .\.env.local

# Secret-leak guard
git status --short
git grep -n -I -E "SID=|SAPISID|__Secure-3PSID|Authorization:" -- . ':!docs'
```

**Exit criteria:** baseline table filled; no secret in `git status`; probe parse check passes.

**Rollback:** delete the harness changes; no production impact.

---

## Phase 1 — Confirmation gate (decide before building)

**Goal:** turn "probable" into "confirmed", or stop and re-scope.

| ID | Experiment | Method | Pass condition |
|---|---|---|---|
| 1.1 | Classify the reported error | Reproduce in-app, capture exact string | Either `no streaming data` (gate 1) or `health check (HTTP 403 Forbidden)` (gate 2) |
| 1.2 | Auth vs age gate | Authenticated probe, `tv` + `visionos`, `hc0ZDaAZQT0` | Formats returned ⇒ gate 1 is auth-only |
| 1.3 | Token vs no token | Same web-client URL, with and without an externally minted `pot` bound to the same `visitorData` | Without ⇒ 403; with ⇒ 200 |
| 1.4 | 403 body | Record the sanitized CDN body (no query strings) | Body mentions integrity/token, or not |

For 1.3, run `bgutil-ytdlp-pot-provider` **manually** (Docker or Node) on the dev machine. This is an experiment, not a shipped dependency.

**Decision gate:**

- If 1.3 shows no difference → **do not build a token layer**; investigate signature/expiry instead.
- If 1.2 shows no formats even authenticated → **do not claim a token fixes the video**; the remaining problem is account/region/client policy.
- If 1.3 confirms the token and 1.2 confirms auth → proceed with Phase 2 + 3.

**Exit criteria:** written result in `PO_TOKEN_ROOT_CAUSE.md` §2.3, with confidence updated.

**Rollback:** none (read-only).

---

## Phase 2 — Token-free client chain (Option D)

**Goal:** play ordinary and signed-in tracks without any token infrastructure.

| ID | Task | File | Acceptance |
|---|---|---|---|
| 2.1 | Add cookie-compatible TVHTML5 profiles (`5.20260707` downgraded, `7.20260707.07.00` current) | `streaming/SpoofClient.kt` | Profiles selectable by id; no removal of existing `tv` |
| 2.2 | Reorder audio cascade: `visionos` → `tv` → `realnewpipe` → rest | `streaming/YouTubeStreamResolver.kt` (~line 459) | Signed-in requests reach TV before anonymous lanes |
| 2.3 | Remove or fix dead lanes (`ios` 400, `mweb` origin mismatch) | `SpoofClient.kt` / resolver cascade | No request is spent on a lane that always 400s |
| 2.4 | Ensure the active cookie reaches TV requests | `endpoints/EchoVideoEndpoint.kt` | Request carries `Cookie` + correct `Origin`/`X-Origin` |
| 2.5 | Skip token-required web lanes when no provider is configured | resolver | WEB_REMIX is not probed when `potoken_mode=off` |

### Acceptance criteria

- [ ] `dQw4w9WgXcQ` plays with `potoken_mode=off` (no regression).
- [ ] `hc0ZDaAZQT0` plays **when signed in**; signed out it fails with a clear auth message, not a generic 403.
- [ ] A normal playlist plays end-to-end for ≥10 tracks without a manual retry.
- [ ] No new dependency in `build.gradle.kts`.
- [ ] `git diff` touches only the four files above.

### Commands

```powershell
./gradlew :ext:assembleDebug
./gradlew :ext:testDebugUnitTest        # if tests exist; otherwise note "no test task"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scratch\diagnostics\probe-player.ps1 `
  -VideoId hc0ZDaAZQT0 -Clients "tv,visionos" -EnvFile .\.env.local
```

### Rollback

Set `spoof_stream_client=visionos` (existing setting) → previous behaviour. No data migration.

---

## Phase 3 — Token seam + optional external provider (Options B/C/A)

**Goal:** make WEB_REMIX usable *only* when the user explicitly configures a minter; change nothing by default.

| ID | Task | Files | Acceptance |
|---|---|---|---|
| 3.1 | `PoTokenProvider` + `OffPoTokenProvider` | `streaming/PoTokenProvider.kt` (new) | Default is `off`; resolver behaves exactly as Phase 2 |
| 3.2 | Client policy + safe `pot` append | `streaming/StreamTokenPolicy.kt` (new) | Existing `pot` replaced, other params preserved, URL-encoded |
| 3.3 | External HTTP provider | `streaming/ExternalPoTokenProvider.kt` (new) | 2s/5s timeouts; failure ⇒ `null`; no `Origin` header |
| 3.4 | Resolver wiring + single retry on 403 | `YouTubeStreamResolver.kt` | Token applied before probing; one re-mint, then fall through |
| 3.5 | Cache safety | `StreamMediaCache.kt` / resolver | Cache cleared on logout/visitor change; no cross-visitor reuse |
| 3.6 | Settings | `YoutubeExtension.kt` | `potoken_mode` / `potoken_url`, advanced only |
| 3.7 | Log redaction | resolver + endpoints | No `pot`/`sig`/`Cookie`/`Authorization`/`visitorData` in logs |
| 3.8 | *(optional)* player-level token | `EchoVideoEndpoint.kt` | `serviceIntegrityDimensions.poToken` only if 1.3 shows player requests are bot-checked |

### Acceptance criteria

- [ ] With `off`: identical behaviour to Phase 2, byte-for-byte on the request path.
- [ ] With `external` + reachable provider: the 1.3 URL returns 200 through the app.
- [ ] With `external` + provider down: playback still succeeds via TV/VISIONOS; no crash, no hang >5s.
- [ ] Two accounts never share a token: logging out and in re-mints against the new `visitorData`.
- [ ] No token string in logcat, crash reports, cache files, or `git grep`.

### Commands

```powershell
./gradlew :ext:assembleDebug
git grep -n -I -E "poToken|pot=|serviceIntegrityDimensions" -- ext/src
# expect: only the new provider/policy files, never a literal token
```

### Rollback

`potoken_mode=off` + `spoof_stream_client=visionos`. Provider code stays dormant.

---

## Phase 4 — Hardening and observability

| ID | Task | Acceptance |
|---|---|---|
| 4.1 | Per-lane counters in logs: client, playability status, HTTP code | One line per lane, no URLs |
| 4.2 | Runbook for "needs login" vs "needs token" vs "dead client" | Triages from the error string alone |
| 4.3 | Regression sweep: 30 tracks (normal, music video, live, age-gated, unavailable) | Table in this doc with pass/fail |
| 4.4 | Token expiry behaviour: force a stale token | One re-mint, then graceful fallback |

---

## Phase 5 — On-device Android provider (Option E, researched 2026-11-19)

**Goal:** generate PO Tokens on the phone itself (no PC, no BGUtil server, no API), via a headless WebView, behind the existing `PoTokenProvider` seam. Full research: `PO_TOKEN_SERVERLESS_RESEARCH.md`; contract: `PO_TOKEN_IMPLEMENTATION_PLAN.md` §10.

**Status (2026-11-19): 5.1–5.6 DONE — device-verified.** On-device minting + restricted playback verified on a real device (Echo nightly v3.0.725, Samsung, Android 15/16): restricted track `hc0ZDaAZQT0` signed-in plays with `potoken_mode=android`, no PC/server/API; normal playback unaffected. 63 unit tests pass; `:app:assembleDebug` succeeds; shadow jar verified free of android framework classes and credential material.

**Acceptance criteria (all met):**
- [x] No PC/server/API required when `potoken_mode=android`.
- [x] Restricted authorized track plays signed-in with an on-device-minted token.
- [x] Normal playback unchanged; external provider still available as optional fallback.
- [x] JVM unit suite green; no token/cookie material in logs, cache, or git.

**Field fixes during verification (same day):**
1. A stale `Set<String>` under `potoken_mode` (old experimental build defined it as `SettingMultipleChoice`) crashed every playback and the settings screen — healed by `utils/SettingsCompat.kt` (defensive read + rewrite as String), tested by `SettingsCompatTest` with a strict-typed SharedPreferences mock.
2. The WebView was created off-main in the port (the `test`-branch prototype used `Dispatchers.Main`) — Android threw on construction and the provider latched off (`onDeviceBroken=true`). Fixed: WebView creation posted to the main looper; boot **timeouts** now retry instead of latching (only deterministic unavailability latches).
3. Restricted-song unlock upgraded per Metrolist/ArchiveTune/LastWave analysis (`docs/PO_TOKEN_SERVERLESS_RESEARCH.md` §9): the video-bound token is now also sent in the web-client **player request body** (`serviceIntegrityDimensions.poToken`) — this resolves the age-gated player request's `400 Precondition check failed` — and player requests use a live `signatureTimestamp` from the bundled NewPipe Extractor (fallback 20773).
4. `potoken_mode` default flipped to **On-device** after device verification; `off`/`external` remain selectable.

**Acceptance criteria**

- [ ] No PC/server/API required when `potoken_mode=android` and a working WebView exists.
- [ ] Restricted authorized track plays signed-in with an on-device-minted token (HTTP 200 + audible playback).
- [ ] Normal playback unchanged with mode `off`; external provider still works when explicitly configured.
- [ ] JVM unit suite passes; no token/cookie material in logs, cache, or git.

**Rollback:** `potoken_mode=off`.

---

## 5. Test matrix

Run every row on a physical device (or an emulator with a real network). Record the exact error string.

| # | Scenario | Auth | Provider | Expected |
|---|---|---|---|---|
| T1 | Normal track, VISIONOS | no | off | plays |
| T2 | Normal track, TVHTML5 | no | off | plays (if TV allows anonymous) |
| T3 | Age-gated `hc0ZDaAZQT0` | no | off | fails, message mentions sign-in, **not** a token |
| T4 | Age-gated `hc0ZDaAZQT0` | yes | off | plays via TV/VISIONOS |
| T5 | Normal track, WEB_REMIX | yes | off | lane skipped; playback via another lane |
| T6 | Normal track, WEB_REMIX | yes | external | 200 on first probe |
| T7 | Token bound to a different visitor | yes | external (wrong binding) | 403 → invalidate → fallback, no crash |
| T8 | Provider unreachable | yes | external | ≤5s delay, then fallback |
| T9 | Logout → login as another account | yes | external | cache cleared, new token minted |
| T10 | Expired/cached media URL | yes | any | cache invalidated on failure, retry succeeds |
| T11 | Playlist of 10 mixed tracks | yes | off | all play or fail with a classified reason |
| T12 | Secret scan | — | — | no cookie/token in `git status`, logs, or docs |

### Evidence to attach to the PR

- [ ] Before/after logcat snippet with query strings stripped.
- [ ] Probe output for `hc0ZDaAZQT0` in the four configurations (T3–T6).
- [ ] `git diff --stat` showing no unrelated files.
- [ ] Confirmation that `.env.local` is untracked.

---

## 6. Effort, risk, and cost

| Phase | Effort | Risk | Blast radius |
|---|---|---|---|
| 0 | 0.5 day | none | `scratch/`, `.gitignore` |
| 1 | 0.5–1 day | none (read-only) | none |
| 2 | 1–2 days | **medium** — cascade reorder can regress normal playback | resolver + client profiles |
| 3 | 2–3 days | medium — token wiring, cache identity | resolver + new files + settings |
| 4 | 1 day | low | logs/docs |

**Total:** ~5–7 developer-days, no new dependency, **$0 mandatory cost**.

- External service is **optional**. If used, it is a user-hosted `bgutil` instance; no shared endpoint, no paid API.
- If Phase 1 shows the token is not the cause, Phase 3 is dropped and the total falls to ~2–3 days.

---

## 7. Approval checkpoints

1. **After Phase 1** — confirm or reject the token hypothesis. No production code before this.
2. **After Phase 2** — decide whether token-free playback is sufficient. If yes, Phase 3 becomes optional.
3. **Before merging Phase 3** — review the diff for secret handling and log redaction.

**Current state:** Phase 0 is effectively complete (harness + `.gitignore`); everything else is awaiting approval.
