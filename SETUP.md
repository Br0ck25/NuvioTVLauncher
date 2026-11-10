# Home-app setup

How this fork makes Nuvio the TV's Home app, and why it is done this way.

## What a TV launcher actually has to do

An Android TV launcher is an ordinary activity that Android is willing to send `HOME` to. Two things
are required, and nothing else:

1. **Declare it.** The activity carries an intent filter with `android.intent.action.MAIN` plus the
   `android.intent.category.HOME` and `android.intent.category.DEFAULT` categories, and runs in a
   stable task (`launchMode="singleTask"`). That is what makes it appear in *Settings → Apps → Default
   apps → Home app*, and what `cmd package set-home-activity` is allowed to point at.
2. **Be selected.** Either the user picks it in the system Home-app dialog / Home settings, or the
   Home preference is written with `cmd package set-home-activity --user 0 <pkg>/<activity>`.

After that, the system dispatches every Home press to the selected activity. The app never sees the
key event and does not need to.

### How FLauncher and ArcLauncher do it

[ArcLauncher](https://github.com/meddouribadis/arclauncher) is a fork of LTvLauncher, which is a fork
of [FLauncher](https://gitlab.com/flauncher/flauncher); all three use the same mechanism.

- FLauncher declares one filter carrying `MAIN`, `DEFAULT`, `HOME`, `LEANBACK_LAUNCHER`,
  `LAUNCHER_APP` and `LAUNCHER` on a `singleTask` `MainActivity`.
- ArcLauncher splits it in two: a `MAIN`/`HOME`/`DEFAULT` filter for Home, and a separate
  `MAIN`/`LEANBACK_LAUNCHER` filter so the app is still listed in the stock launcher's app grid. It
  adds `stateNotNeeded`, `clearTaskOnLaunch` and `excludeFromRecents`, which are launcher conveniences
  (fast cold start, always land on the top screen), not requirements.
- Neither ships an accessibility service for the normal case. ArcLauncher's descendant builds do carry
  one, but strictly opt-in and only for remotes that send `KEYCODE_ALL_APPS` instead of `KEYCODE_HOME`
  (some Xiaomi firmware).
- Their only "am I the launcher?" check is `resolveActivity(MAIN + HOME)` compared against their own
  package — no `RoleManager`, no manufacturer tables, no telemetry.
- Both READMEs document the same two setup routes: remap the Home button with a third-party app
  (Button Mapper), or `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` **and**
  `com.google.android.tungsten.setupwraith` (the Google TV setup wizard re-enables the stock launcher
  otherwise), after which the next Home press shows Android's picker.
- ArcLauncher's install tooling adds the parts that matter for safety: ask the TV which package owns
  Home instead of guessing, refuse to disable anything that is not a known stock launcher, start the
  new launcher and confirm it really came up, re-resolve Home afterwards, and **roll back** — re-enable
  the stock launcher and reset its Home activity — when the handover did not take, because a TV whose
  new Home will not start can only be rescued over ADB.

## What this fork does

Upstream Nuvio TV is a media app, not a launcher. This fork adds the smallest change that makes it
selectable as one, plus a way to perform the selection without a computer.

### Manifest

- `MainActivity` declares `MAIN`/`HOME`/`DEFAULT` in its own intent filter and runs as `singleTask`.
- The six selectable app icons are `activity-alias` entries targeting `MainActivity`, not subclasses
  of it. Subclasses would each own a task (so Home could land on a different instance than the icon
  the user launched), and switching icons would leave the recorded Home preference pointing at a
  component that is now disabled. The alias names are unchanged, so existing shortcuts keep working.
- `<queries>` declares `android.settings.HOME_SETTINGS` and `MAIN`/`HOME` so the app can open the
  system Home settings and read which app owns Home on Android 11+.
- The `full` flavor declares `WRITE_SECURE_SETTINGS`, which is only ever granted by the one-time
  on-device setup below. The `playstore` flavor does not declare it and does not ship the setup.
- There is no accessibility service, no `BIND_ACCESSIBILITY_SERVICE`, no key-event filtering, no boot
  receiver and no notification listener. `HomeSetupManifestTest` fails the build if one appears.

### Selection

`core/setup/HomeSetup.kt` — three ways in, all of them Android's own:

- `createHomeRoleRequest()` builds the system `ROLE_HOME` confirmation intent (Android 10+). The
  caller launches it for a result; success is decided by re-reading the HOME resolver afterwards,
  because some firmware reports success without changing anything.
- `openHomeSettings()` opens the system Home settings for firmware that suppresses the role dialog.
- `isDefaultHome()` reads the actual resolver. That is the only status the UI reports.

Nothing is requested automatically at startup. The user has to open **Settings → Default launcher**.

Two side effects of the manifest change are worth knowing about, because they are the only ways this
fork behaves differently from upstream when the setup is *not* used:

- `singleTask` means a Home press while Nuvio is already open reuses its task instead of stacking a
  second copy of the activity. Nothing is reset on top of it — no scroll reset, no forced profile
  switch, no screen change.
- Declaring `HOME` makes Nuvio appear in the system Home-app list. It is never selected by itself:
  Android only changes the Home preference when the user confirms it, or when the setup below writes
  it explicitly.

### On-device setup

`core/setup/LocalAdbSetup.kt` (contract) and `full/.../LoopbackAdbSetup.kt` (implementation) pair with
the TV's **own** Wireless debugging daemon over `127.0.0.1` using `libadb-android`, so the app can run
the shell commands a computer would. An RSA identity is generated once and kept in
`noBackupFilesDir`, which is what makes "already paired — just reconnect" work on later runs.

`core/setup/PhoneSetupSession.kt` serves a small web page on the TV's LAN address (port 8091, next
free port on conflict) so the pairing code can be typed on a phone. That is the point of the whole
flow: the TV's *Pair device with pairing code* popup closes as soon as Settings is left, so its code
cannot be entered on the TV itself. The page and the TV dialog drive the same session, so only one run
can be active. While the dialog is open, `ExternalPlaybackKeepAliveService` keeps the process alive —
otherwise the TV can freeze Nuvio in the background and the phone's request hangs.

The page offers two modes:

| Mode | What it runs |
| --- | --- |
| **Make Nuvio the Home app** | grants → `cmd package set-home-activity --user 0 <ours>` → `cmd package compile -m speed -f` → re-resolve Home → optional stock-launcher disable |
| **Permissions only** | grants, and reports who owns Home without changing it |

Grants are `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` (verified by re-checking the
permission, since the command prints nothing) and a best-effort
`appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`.

### Verification and rollback

`core/setup/HomeHandover.kt` is pure Kotlin so the ordering can be unit tested
(`HomeHandoverTest`):

1. **Ask, don't guess.** `cmd package resolve-activity --brief -a android.intent.action.MAIN
   -c android.intent.category.HOME` names the current owner. The result is classified as ours, a known
   stock launcher, another launcher, Android's chooser (`android`), Google TV's setup wizard, or
   nothing.
2. **Switch.** `cmd package set-home-activity --user 0 <pkg>/<activity>`. Always `--user 0`: the Home
   preference is per-user.
3. **Verify.** Resolve Home again. Only the resolver's answer counts, never the exit status.
4. **Roll back** when it did not take: `pm enable --user 0 <previous>` first (a disabled package
   cannot be the Home activity), then point Home back at the recorded component. If even that fails,
   the error text carries the exact `adb` commands to run from a computer.
5. **Optional, explicit, and last:** `pm disable-user --user 0` for the stock launcher, only when the
   user ticked it, only for a package in `knownStockLaunchers`, and only after Home already resolves
   to Nuvio — so a failed handover still leaves the TV with a working Home. Google TV's
   `com.google.android.tungsten.setupwraith` is disabled alongside it because it re-enables the stock
   launcher. The setup wizard is never itself a disable target: on a TV that currently resolves Home
   to the wizard, disabling it can leave no Home at all.
6. **Report.** The result names the component Home now resolves to, which packages were disabled, and
   the `adb` commands that restore the stock launcher.

### Deliberately absent

- No accessibility service, so no key interception, no `WRITE_SECURE_SETTINGS`-driven re-enabling of
  anything, and no watchdog fighting the system or the user.
- No automatic role prompt at startup and no post-update nagging; the request only happens when the
  user opens the settings row.
- No boot receiver, no notification listener, no apps grid, no remote-button remapping.
- No manufacturer/model inference. Firmware that refuses a third-party Home app is reported as "Home
  still resolves to X", not guessed at in advance.
