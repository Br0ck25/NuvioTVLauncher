# Making Nuvio TV Launcher faster: findings and plan

Measured 2026-11-14 on a Chromecast with Google TV (4 cores, 2 GB RAM, 32-bit userland / armeabi-v7a, Android 14)
running 1.2.3-beta.6. Numbers are single runs on one device: treat them as direction, not benchmarks.

## What was measured

### 1. The app is not compiled on the device (biggest finding)

`dumpsys package dexopt` showed Nuvio at `status=verify, reason=install`: nothing is ahead-of-time compiled, so the
launcher runs interpreted/JIT until Android's idle maintenance compiles it (which a TV that is rarely idle and charging
may take a long time to do). The baseline profile in the APK is therefore not being used.

Forcing a profile-guided compile (`cmd package compile -m speed-profile -f com.nuvio.tv.launcher`) on the same build:

| | verify (as installed) | speed-profile |
|---|---|---|
| Cold start to first screen (`am start -W`, 4 runs) | 2,203 / 1,916 / 1,949 / 1,941 ms | 1,205 / 1,152 / 1,140 / 1,117 ms (about 40% faster) |
| Home scroll, janky frames | 41.3% | 10.2% to 10.8% (two runs) |
| 90th / 99th percentile frame | 150 ms / 300 ms | 42 to 44 ms / 85 to 89 ms |
| Slow UI thread frames | 108 of 281 | 44 to 53 of about 560 to 600 |

Why it happens: `UpdateViewModel.installUpdateOrRequestPermission` (and the system-installer path before it) installs
with `withDexMetadata = false`, and sideloads and adb installs never carry the profile either. The idle auto-install
path does pass the dex metadata, so only that path compiles at install time.

### 2. Even compiled, frames are slow

With the compile in place the median frame is 34 to 36 ms (about 28 fps), with a GPU median of 16 ms:
- "Slow issue draw commands": 44 frames, "Slow UI thread": 44 to 53, "High input latency": about 1,000 events.
- Memory after a short browse: total PSS 400 MB (Native heap 99 MB, Graphics 148 MB, Java heap 72 MB) on a device with about
  550 MB available, so GC and low-memory kills are likely under load. Load average was about 2.9 on 4 cores.
- `ImageLoader` uses `allowHardware(false)`, so every image has a heap copy and a GPU texture copy.

### 3. Perfetto traces (Chromecast, speed-profile compiled, Home scroll, about 13 s each)

The display runs at 3840x2160 @ 59.94 Hz; the app draws a 1920x1080 surface, so the frame budget is 16.7 ms.

| | Accessibility service ON (as users have it) | Accessibility service OFF |
|---|---|---|
| Main thread running | 8.8 s | 5.4 s |
| Total app CPU | 24.1 s | 20.2 s |
| Longest main-thread frame | 64 ms | 47 ms |
| Frames over 16 ms / over 33 ms | 126 / 11 | 146 / 8 |
| RenderThread CPU | 5.3 s | 5.7 s |
| Background GC (concurrent mark-compact) | 4 GCs, 2.7 s total, longest 946 ms | 2 GCs, 1.8 s total, longest 1,045 ms |

What the traces show:
- **Accessibility tax.** With the Home-override service enabled, Compose spends about 3.3 s of main-thread time (about 38%) on
  accessibility semantics (`getAllUncoveredSemanticsNodesToIntObjectMap`, `checkForSemanticsChanges`,
  `sendAccessibilitySemanticsStructureChangeEvents`); these slices do not exist with the service off. The service config is
  already minimal (`typeWindowStateChanged`, no window-content retrieval), so Compose is doing the work anyway.
- **Idle is fine.** An 8 s idle trace on Home had zero frames: nothing animates continuously. All cost is while focus moves.
- **Rendering is the limit, not the main thread.** With the service off the main thread is mostly asleep, yet the median frame
  is still about 36 ms. The RenderThread spends about 9 ms per frame drawing and waits about 3 s in total in
  `dequeueBuffer` and `eglSwapBuffers`; GPU median 16 ms. About 90% of janky frames are "Buffer Stuffing" (the GPU/compositor
  pipeline backing up), about 15% also "App Deadline Missed". Per frame that is roughly 35 textured quads, 14 rect fills and
  13 rounded-rect fills at 1080p: a fill-rate and overdraw problem on a small GPU, which SurfaceFlinger then scales to 4K
  (about 3 s of its own CPU).
- **GC.** Android 14's compacting GC ran 2 to 4 times in the window, each occupying a core for 0.5 to 1 s. Allocation churn
  (Compose, plus decoded bitmaps kept on the heap because `allowHardware(false)`) is the likely cause.
- **Long prefetch chunks.** `compose:lazy:prefetch:*` slices reach 45 to 101 ms on the main thread, a main source of the long
  frames.

### 4. Already done well (keep)

`NuvioApplication` already defers Sentry, channel sync and the accessibility watchdog to a background thread, builds
Hilt services lazily, sizes Coil's memory cache from RAM, and has `StartupTiming` marks.

## Plan, in order

1. **Compile at install (small, high value).** Change the banner install in `UpdateViewModel` to use the session installer
   with `withDexMetadata = true` (the receiver already retries without it on failure). Verify on the Chromecast with
   `dumpsys package dexopt` after an in-app update. Note the first update that benefits is the one installed *by* the
   new version. For existing installs, `cmd package compile -m speed-profile -f` once is the workaround (done on the test
   Chromecast on 2026-11-14).
2. **Make sure the baseline profile is current and used.** Check the generated `baseline-prof.txt` covers Home, Live TV row
   and the Apps page on a 32-bit device (profiles are per ABI), and regenerate it in CI for each release.
3. **Cut the accessibility tax (traced, about 38% of main-thread time).** (a) Check what the Compose version in use does when
   any accessibility service is enabled and whether a newer Compose BOM avoids it; (b) hide purely decorative nodes on
   Home from the semantics tree (backdrops, gradients, card art) so the tree Compose walks is much smaller; (c) keep
   the service config minimal. Re-trace with the service on and compare to the table above.
4. **Cut GPU work per frame (the frame-rate limit).** Use the GPU overdraw view and a Perfetto GPU slice trace to find full-screen
   layers and stacked gradients behind the rows; merge or remove layers, avoid re-drawing the hero backdrop on every focus
   step, and use cheaper shapes for cards. Target: frame median under 16.7 ms on the Chromecast.
5. **Trim allocation and GC.** Try `allowHardware(true)` for card images (one copy instead of two, pixels off the heap),
   request images at display size, and lower the number of rows kept composed. Compare GC count and pause time.
6. **Smaller prefetch chunks.** Limit nested `lazy` prefetch on Home so a single frame never composes a whole row (45 to
   101 ms slices today).
7. **Keep work off the first frame.** Use the existing `StartupTiming` output to list what still runs before the first
   frame after process start (the code comment says about 15 s after a boot), and move anything the Home takeover does not
   need to after first draw.
8. **Guard against regressions.** Add a Macrobenchmark (cold start and Home scroll jank) to CI so a release cannot quietly
   lose the profile or add a slow frame path.

Suggested order by measured payoff: 1 (compile) first, then 3 and 4 together (the two biggest per-frame costs), then 5 to 8.

## How to re-measure

```
adb shell dumpsys package dexopt | grep -A2 com.nuvio.tv.launcher        # status should be speed-profile
adb shell am force-stop com.nuvio.tv.launcher; adb shell am start -W -n com.nuvio.tv.launcher/com.nuvio.tv.MainActivity
adb shell dumpsys gfxinfo com.nuvio.tv.launcher reset   # then scroll Home, then:
adb shell dumpsys gfxinfo com.nuvio.tv.launcher         # Janky frames and percentiles
```
