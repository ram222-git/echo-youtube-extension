# Instant Playback — Benchmarks

## What is measured

All timestamps use a monotonic clock. The authoritative measurement is
**tap → audible output**, not tap → READY: `firstAudibleOutput` fires from
Media3's `AnalyticsListener.onAudioPositionAdvancing`, which only happens when
the audio sink's position actually starts moving — READY alone proves nothing
about audibility.

| Scenario | Measured as |
|---|---|
| First-ever song playback | `playTap` → `firstAudibleOutput` (cold caches) |
| Same song replay | `playTap` → `firstAudibleOutput` (after a full play + stop) |
| Automatic next song | `mediaItemTransition reason=auto` → `firstAudibleOutput` |
| Manual skip | `mediaItemTransition reason=seek` → `firstAudibleOutput` |
| Rapid skip | last `playTap`/`reason=seek` → `firstAudibleOutput` for the latest selected song |

## Where the marks come from

Everything is one `[EchoBench]` line per event in logcat (both the host app and
the extension log with the same tag; durations within each line are
process-internal spans, so they are exact regardless of origin):

| Mark | Source | Meaning |
|---|---|---|
| `playTap` | host `PlayerCallback.playItem` | user action received by the player service |
| `streamResolved v=… took=… cache=hit/resolved/joined` | ext resolver | stream URL resolution complete (cache outcome) |
| `tokenReady kind=… binding=… took=…` | ext `AndroidPoTokenProvider` | PO token minted (video-bound; length only, never the value) |
| `mediaSourcePrepared media=… took=…` | host `StreamableMediaSource` | loadTrack + loadStreamableMedia finished for an item |
| `playerReady media=…` | host `BenchmarkListener` | ExoPlayer reached READY |
| `firstAudibleOutput media=…` | host `BenchmarkListener` | AudioTrack position advancing — **audible** |
| `mediaItemTransition reason=… media=…` | host `BenchmarkListener` | auto vs seek transitions |

Privacy: no cookies, no PO-token values, no full stream URLs in these logs
(resolver logs redact URLs as before).

## How to capture

```bash
adb logcat -c
adb logcat -s "" | grep -F "[EchoBench]"   # or: adb logcat | grep "[EchoBench]"
```

One scenario run = clear caches per the test matrix (`INSTANT_PLAYBACK_TEST_REPORT.md`),
perform the action, then read the `firstAudibleOutput took=…` span for the
target media.

## Baseline vs target (to be filled from the device run)

Record each row before/after the optimization build:

| Scenario | Before (ms) | After (ms) | Target |
|---|---|---:|---:|
| First-time playback | _pending device run_ | | < 1000 ideal / ≤ 2000 max |
| Cached replay | _pending_ | | < 300 |
| Automatic next song | _pending_ | | < 500 |
| Manual skip (preloaded) | _pending_ | | < 500 |
| Play/pause UI response | immediate (no measurable hop) | | immediate |

Static-code expectation (audit §2): the before-implementation chain pays two
serial extension RTs + a 2500 ms buffer target; after the changes an uncached
first play pays max(RT₁, RT₂) + 750 ms buffer target, and preloaded skips pay
~0 network.

## Noise control

- Warm up once before measuring (first launch always pays DNS/TLS/WebView).
- Take 3 runs per scenario, report the median.
- Wi-Fi vs mobile data runs are reported separately.
- Rapid-skip runs are annotated with the transition sequence (A→B→C etc.).
