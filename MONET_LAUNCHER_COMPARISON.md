# Monet Launcher vs Nuvio TV Launcher: observed differences

Tested 2026-11-14 on a Chromecast with Google TV (Android 14, 32-bit userland).
Monet Launcher 1.1.1 (`com.klevico.monet`, the official release from github.com/Klevico/Monet-Launcher,
signed by Klevico, the same developer as its Play Store listing) against Nuvio TV Launcher 1.2.3-beta.6.
Everything below was observed on the device; where something was not tried it says so.

## Setup and "default launcher"

| | Monet | Nuvio |
|---|---|---|
| First run | 7-step wizard: welcome, default launcher, accessibility, TV listings, location, Bluetooth, done. Every permission step has a "Later" button. | Profile picker ("Who's watching?") first, then Home. Setup of the Home override is in Settings → Default launcher. |
| Default launcher | Wizard opens Android's "Default home app" picker. Selecting Monet made it the `android.app.role.HOME` holder. | Was the HOME role holder before the test. |
| Home button with the role alone | **Still opened Google TV** on this Chromecast. The firmware ignores the role for the Home key. Monet's own wizard warns it may not work on some TV models. | Same limitation. |
| Home button override | Optional Monet accessibility service (not enabled in this test, see below). | Accessibility service plus the app-ops "restricted settings" allow and WRITE_SECURE_SETTINGS. Home opened Nuvio. |
| Both installed, Nuvio's override on | Home opened Nuvio even though Monet held the Home role. | |

Other Android 14 facts seen on the way:
- Nuvio's watchdog re-enabled its accessibility service within seconds of it being deleted, as designed. To test Monet
  alone, Nuvio's WRITE_SECURE_SETTINGS grant had to be revoked temporarily (and was granted back afterwards).
- With Nuvio's service off and Monet as the role holder, Home opened **Google TV**, not Monet.
- Monet declares WRITE_SECURE_SETTINGS in its manifest; it was not granted.

## Permissions Monet asked for (all declined or skipped)

- Accessibility service: its disclosure says it sees which app is open and which buttons and keys are pressed, can tap where
  the mouse cursor points, take screenshots when asked, and block apps in kids profiles. Declined: this goes beyond
  "default launcher" and is the user's call.
- TV listings (live channels / continue watching / recommendations), approximate location (weather), nearby devices
  (Bluetooth panel). Skipped with "Later".
- Because of that, Monet's Home showed a "Permission required. Press to continue." banner, and its Continue Watching row was
  not populated. So Monet's Continue Watching was not compared against Nuvio's.

## Home screen

- **Monet:** a favorites row (empty, with a "+" tile), then a grid of all installed apps as large rounded tiles, with a
  Wi-Fi / profile / settings pill and the clock at the top right. Background is a "Monet wallpaper" with a frosted
  transparency style.
- **Nuvio:** a hero for the focused item, Continue Watching, Upcoming (calendar), catalog rows, and an Apps row; the
  side menu holds Home, Discover, Search, Library, Apps, Calendar, Live TV, Sports and Settings.
- Monet is an app launcher first; Nuvio is a content launcher (its own catalogs, Live TV, sports, calendar) with an apps row.

## Monet's settings (from its in-app settings panel)

- Appearance: Designs, Background, Transparency, Colors, Font.
- Home screen: Status bar, Rows & widgets, Apps, Animations & effects.
- General: Profiles (locked), Parental controls (locked), Features & buttons, Language & region, Setup & backup.
- About: Update.
- "Unlock Premium" is shown, and the Profiles and Parental controls entries carry a lock badge. Some of Monet is paid.
- Hold OK for options; Back jumps to the top; Settings via the gear at the top.

## Measurements (single run each, 12 s after launch, so treat as rough)

| | Monet | Nuvio |
|---|---|---|
| Cold start (`am start -W`) | 516 ms | 2,444 ms (opens on the profile picker) |
| Total PSS | 140.7 MB | 174.4 MB |
| Java heap (PSS) | 6.3 MB | 35.2 MB |
| APK (release download) | 19.4 MB (arm64 + armeabi-v7a + x86 in one APK) | 68.8 MB (armeabi-v7a), 70.9 MB (arm64) |

## Not tested

- Monet with its accessibility service enabled (Home / standby / button mapping over other apps).
- Monet's Continue Watching, live channels and weather (needed permissions that were declined).
- Behaviour after an update, Monet's Premium features, parental controls.

## State left on the Chromecast

- Monet installed and set as the Android default home app (HOME role).
- Nuvio's Home override restored (accessibility service enabled, restricted-settings allow, WRITE_SECURE_SETTINGS granted).
