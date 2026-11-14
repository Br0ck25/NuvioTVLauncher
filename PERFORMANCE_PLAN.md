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

### 3. Already done well (keep)

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
3. **Profile the rendering with a trace, not guesses.** Capture a Perfetto trace of Home scrolling on the Chromecast and
   look at the main thread, RenderThread and GPU. Suspects to confirm or rule out: full-screen backdrop plus gradient
   overlays redrawn on every focus change, the focused-hero crossfade, image sizes (decode at card size, not source size),
   and recomposition of whole rows when focus moves.
4. **Trim memory.** Try `allowHardware(true)` for card images on the Chromecast (one copy instead of two), request images
   at display size, and cap how many rows keep decoded images. Compare PSS and jank before and after.
5. **Keep work off the first frame.** Use the existing `StartupTiming` output to list what still runs before the first
   frame after process start (the code comment says about 15 s after a boot), and move anything the Home takeover does not
   need to after first draw.
6. **Guard against regressions.** Add a Macrobenchmark (cold start and Home scroll jank) to CI so a release cannot quietly
   lose the profile or add a slow frame path.

## How to re-measure

```
adb shell dumpsys package dexopt | grep -A2 com.nuvio.tv.launcher        # status should be speed-profile
adb shell am force-stop com.nuvio.tv.launcher; adb shell am start -W -n com.nuvio.tv.launcher/com.nuvio.tv.MainActivity
adb shell dumpsys gfxinfo com.nuvio.tv.launcher reset   # then scroll Home, then:
adb shell dumpsys gfxinfo com.nuvio.tv.launcher         # Janky frames and percentiles
```
