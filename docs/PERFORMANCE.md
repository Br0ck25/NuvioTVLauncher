# Performance

Nuvio One ships with a baseline profile, so Android precompiles the hot code paths when the app is installed or updated. This is the same mechanism the Play Store uses and it needs no setup from the user.

## What ships with the app

| Piece | What it does |
|---|---|
| `app/src/main/baseline-prof.txt` | ART profile rules for the Home screen, navigation, the player, settings, OneTube, Music, Live TV and Media3 playback. |
| `app/src/main/generated/baselineProfiles/startup-prof.txt` | The launch path, precompiled at install. |
| `androidx.profileinstaller` | Installs the profile into ART after the APK is installed. |
| `assets/updater_dm/*.dm` | The in-app updater passes the profile to the package installer, so an update keeps the compiled state. |

The compiled profile is stored in the APK as `assets/dexopt/baseline.prof` and `baseline.profm`. The platform limit for the compiled profile is 1.5 MB; the current build is about 60 KB, so the profile stays well inside it.

## What the profile covers

- The upstream journeys: Home, fast scroll, detail, player, settings and library.
- OneTube (`com.nuvio.tv.youtube`), Music (`com.nuvio.tv.music`), Live TV (`com.nuvio.tv.livetv`) and the shared player (`com.nuvio.tv.core.player`).
- The Music engine (`com.music.bitchord`) and the YouTube engine (`com.liskovsoft.youtubeapi`, `mediaserviceinterfaces`, `sharedutils`, `googlecommon`).
- The shared Compose components, the sync layer and the Media3 ExoPlayer, common, datasource, TS and MP4 extractor packages used by Live TV, OneTube and Music.

## Optional: full AOT compilation

The baseline profile compiles the methods that are listed in it. A full `speed` compilation compiles every method in the APK. It is stronger, but it only affects the device where it runs and has to be repeated after every update.

If you sideload the APK and want that extra step:

```sh
# macOS / Linux
scripts/adb-speed-compile.sh app.onetube.tv

# Windows PowerShell
.\scripts\adb-speed-compile.ps1 -Package app.onetube.tv
```

Or run the commands directly:

```sh
adb shell cmd package compile -m speed -f app.onetube.tv

# Older devices:
adb shell pm compile -m speed -f app.onetube.tv
```

Use `app.onetube.tv.test` for a test build or `com.nuvio.livetv` for a debug build.

## Verify the compiled state

```sh
adb shell dumpsys package dexopt | grep -A 1 app.onetube.tv
```

The entry should say `status=speed` after a full compile, or `status=speed-profile` after an install that used the baseline profile.

## Why the app cannot run this itself

`pm compile` is a shell/root command. A normal app cannot request a full compilation of its own package on a stock TV, and running shell commands from the app would be blocked. The baseline profile is the part that can be shipped by default; the ADB command is the manual extra step for users who want it.

## Measuring a change

The repository has a macrobenchmark gate:

```sh
scripts/perf_baseline.sh metrics
```

It compares every journey with the baseline profile applied and without compilation, and writes the summary to `baselineprofile/build/outputs/perf-metrics.md`. See `baselineprofile/README.md` for the device requirements.
