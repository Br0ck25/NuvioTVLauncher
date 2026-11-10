<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Original Nuvio website](https://nuvio.tv) · [Fork releases](https://github.com/Br0ck25/NuvioTVLauncher/releases) · [Support the original Nuvio](https://nuvio.tv/support)

</div>

## What this fork is

**NuvioTVLauncher** is a fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) with exactly one
addition: **Nuvio can be set up as your TV's Home app from your phone**, with no computer and no typed
ADB commands. Everything else tracks upstream Nuvio TV, and a scheduled workflow merges upstream
`dev` back in.

It works the way [FLauncher](https://gitlab.com/flauncher/flauncher) and
[ArcLauncher](https://github.com/meddouribadis/arclauncher) work: the app declares
`MAIN`/`HOME`/`DEFAULT` on a single-task activity, and Android then dispatches the Home button to it
once you select it. **Nuvio does not intercept the Home key, does not run an accessibility service,
does not add a boot receiver, and does not disable anything on your TV unless you explicitly ask it
to.** If you never run the setup, the app behaves exactly like upstream Nuvio TV.

Details: [SETUP.md](./SETUP.md).

## Get NuvioTVLauncher

- [Android TV APK](https://github.com/Br0ck25/NuvioTVLauncher/releases) — `full` flavor, needed for the phone setup
- Upstream Nuvio TV: [Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app) · [releases](https://github.com/NuvioMedia/NuvioTV/releases/latest)

The fork uses its own application ID (`com.nuvio.tv.launcher`), so it installs side by side with
upstream Nuvio TV (`com.nuvio.tv`) and its updates come from this repository.

## Set Nuvio as your Home app

Three ways, easiest first. All three end with the same thing: Android's Home preference points at
Nuvio, and the Home button opens it.

### 1. On the TV

**Settings → Default launcher → Make Nuvio the Home app**, then confirm the system dialog. If your TV
does not offer that dialog, try **System Home settings** on the same screen.

### 2. From your phone (no computer)

Needs the `full` APK, Android 11 or newer, and a phone on the **same Wi-Fi network** as the TV.

1. On the TV, open **Settings → System → About** and press **Build** 7 times to unlock Developer
   options.
2. In **Developer options**, turn on **Wireless debugging**, then choose **Pair device with pairing
   code**. Keep that popup open — its port and code stop existing when it closes.
3. In Nuvio, open **Settings → Default launcher → Set up without a computer**. A phone address such
   as `http://192.168.1.50:8091` is shown; Nuvio keeps running in the background while the dialog is
   open.
4. On the phone, open that address, enter the pairing port and the 6-digit code, pick what the run
   should do, and tap **Pair and set up**.

Nuvio then pairs with the TV's own Wireless debugging service over loopback and runs the same
commands a computer would:

- `pm grant … WRITE_SECURE_SETTINGS` and `appops set … ACCESS_RESTRICTED_SETTINGS allow`
- `cmd package set-home-activity --user 0 com.nuvio.tv.launcher/com.nuvio.tv.MainActivity`
- re-resolves Home to **verify** the switch actually took, and **rolls the TV back** to its previous
  launcher when it did not
- only if you tick it: `pm disable-user` for a **known** stock launcher (plus Google TV's setup
  wizard, which otherwise switches the launcher straight back), and the exact `adb` recovery commands
  are reported so you can undo it from a computer

You can also choose **Permissions only**, which applies the grants and leaves the Home app alone.
Already paired this TV once? Leave the port and code empty and it just reconnects. When it is done you
can turn Wireless debugging off.

### 3. Manual ADB from a computer

Use this only if the TV has no Wireless debugging option (some Fire TV and older devices). The release
package is `com.nuvio.tv.launcher`; the debug build is `com.nuvio.tv.launcher.debug`.

```bash
./adb pair IP_ADDRESS:PAIRING_PORT        # then enter the 6-digit code
./adb connect IP_ADDRESS:CONNECTION_PORT  # usually a different port than the pairing one
./adb -s YOUR_DEVICE shell pm grant com.nuvio.tv.launcher android.permission.WRITE_SECURE_SETTINGS
./adb -s YOUR_DEVICE shell appops set com.nuvio.tv.launcher ACCESS_RESTRICTED_SETTINGS allow
./adb -s YOUR_DEVICE shell cmd package set-home-activity --user 0 com.nuvio.tv.launcher/com.nuvio.tv.MainActivity

# check where Home ended up
./adb -s YOUR_DEVICE shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

If the TV refuses to move Home, the fallback the other TV launchers document is to disable the stock
launcher so Android shows its picker on the next Home press — at your own risk, and never disable
anything you cannot re-enable from a computer:

```bash
./adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
./adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # re-enables the launcher otherwise

# put them back
./adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
./adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
```

### Going back to your original launcher

Select it in the TV's **Default apps / Home app** settings, or from a computer:

```bash
./adb shell cmd package set-home-activity --user 0 $(./adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME com.google.android.apps.tv.launcherx | tail -n 1)
```

Then uninstall Nuvio if you do not want it any more. Turn Wireless debugging and network ADB off when
you are done, and never expose ADB ports to the internet.

## Build from source

```bash
git clone https://github.com/Br0ck25/NuvioTVLauncher.git
cd NuvioTVLauncher
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires
Android Studio, a JDK, and the Android SDK. Debug builds sign with the debug keystore when no release
keystore is present; release signing reads `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`,
`NUVIO_RELEASE_KEY_PASSWORD` and `NUVIO_RELEASE_STORE_PASSWORD` from the environment or an ignored
`local.properties` — no signing password is stored in this repository.

Before a release, run:

```bash
./gradlew testFullDebugUnitTest
./gradlew assembleFullRelease
```

## License

[GNU General Public License v3.0](./LICENSE)
