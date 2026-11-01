<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Original Nuvio website](https://nuvio.tv) · [GitHub releases](https://github.com/Br0ck25/NuvioTVLauncher/releases) · [Support the original Nuvio](https://nuvio.tv/support)

</div>

## About this project

**Nuvio TV Launcher** is an independent fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV). It adds Android TV home-screen launcher support, an Apps screen for your installed apps, a shortcut to Android Settings, and an option to make the Home button open the app. When Nuvio handles the Home button through the native default-Home setting or the optional launcher accessibility service, pressing **Home** returns to the top of Home, and double-tapping **Home** opens the profile switcher (when multiple profiles or a PIN-protected profile are available). TV firmware may reserve some Home-button gestures for the system. After 30 minutes without input (and when the TV is turned on) Nuvio returns to the profile picker so the next person picks their own profile; this can be turned off in Settings → Advanced.

This project is **not affiliated with, endorsed by, or supported by** the Nuvio team or NuvioMedia. Nuvio and its logo belong to their respective owners. Please report problems with this fork in this repository, not to the original project.

Account sign-in and QR-code login rely on the original project's hosted services, which this fork does not have access to. Choose **Continue without account** to use the app.

The website and support links above belong to the original Nuvio project.

## Get Nuvio TV Launcher

- [Android TV APK](https://github.com/Br0ck25/NuvioTVLauncher/releases)
- **AFTV Downloader code:** `2057622`
- [aftv.news/2057622](aftv.news/2057622)

## Set Nuvio as your default launcher

### Recommended: install with Tellyscope

Install the **release APK** using an ADB-based installer such as [Tellyscope](https://github.com/man15h/tellyscope). After connecting Tellyscope to your TV and installing the APK, open Nuvio and select **Settings → Default launcher → Make Nuvio the Home app**, then confirm the system dialog. On supported devices, no additional restricted-settings unlock or manual ADB commands are needed.

Installation does **not** automatically select Nuvio as Home. Some TV firmware blocks third-party default launchers regardless of the installer. If native Home selection works, the accessibility service is optional.

### Installed with AFTV Downloader?

First try **Settings → Default launcher → Make Nuvio the Home app** in Nuvio. If it works, you are done. If the TV blocks the change, or the accessibility switch is greyed out, restricted, or turns itself off, use the ADB setup below. Choose the connection method your TV actually provides; Android version alone does not determine which one is available.

**Before you start:**

- Download and extract Google's [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on your computer. Open PowerShell or Terminal in its `platform-tools` folder.
- Put the computer and TV on the **same trusted Wi-Fi network**, without guest-network/client isolation.
- The commands below target the **release APK**, whose package is `com.nuvio.tv` and Home activity is `com.nuvio.tv/.MainActivity`. The full debug APK uses `com.nuviodebug.com`; do not accidentally configure the debug copy instead of your release installation.
- Commands use `./adb`. In Windows PowerShell, `./adb.exe` or `.\adb.exe` can also be used.
- Only authorize computers you trust. Menu names and available ADB commands vary by TV and firmware, especially on Fire TV.

#### Method A: secure pairing / dynamic PIN

Use this method if the TV offers **Wireless debugging → Pair device with pairing code**, as on supported Android 11+ devices, including some Chromecast with Google TV 4K/HD firmware.

**1. Enable Developer options and debugging**

1. On the TV, go to **Settings → System → About**.
2. Highlight **OS Build** or **Android TV OS Build** and press the center remote button **7 times**, until **“You are now a developer!”** appears.
3. Go back to **Settings → System → Developer options**.
4. Turn on **Wireless debugging**, and **USB debugging** if your TV requires it. Accept any confirmation prompts.

**2. Pair the computer and TV**

1. Enter the **Wireless debugging** submenu and select **Pair device with pairing code**.
2. Keep the popup open. Note its **6-digit pairing code** and **IP address with pairing port**, for example `192.168.68.27:34197`.
3. Run this on the computer, replacing the placeholder with the address shown on the TV:

   ```powershell
   ./adb pair IP_ADDRESS:PAIRING_PORT
   ```

4. Enter the 6-digit code when prompted. Wait for **Successfully paired**.

**3. Find the active connection**

```powershell
./adb devices
```

Copy the exact identifier of the TV whose status is **device**. It may look like `adb-23151HFDD55EMF-orAumo._adb-tls-connect._tcp` or `IP_ADDRESS:CONNECTION_PORT`.

If pairing succeeded but the TV is not listed, return to the main **Wireless debugging** screen and use the **IP address and connection port** shown there:

```powershell
./adb connect IP_ADDRESS:CONNECTION_PORT
./adb devices
```

**The connection port is usually different from the pairing port.** Replace `YOUR_DEVICE_STRING` in the following commands with the identifier from `adb devices`.

**4. Unlock restricted settings and select Nuvio as Home**

Only if Android is blocking Nuvio's accessibility settings, allow access to restricted settings:

```powershell
./adb -s YOUR_DEVICE_STRING shell appops set com.nuvio.tv ACCESS_RESTRICTED_SETTINGS allow
```

`allow` is the named equivalent of app-op mode `0`. Older Android versions and some TV firmware do not support this app-op; if it reports an unknown operation, it is not an available unlock method on that device.

Set the release app as the default Home activity:

```powershell
./adb -s YOUR_DEVICE_STRING shell cmd package set-home-activity com.nuvio.tv/.MainActivity
```

**5. Finish on the TV**

Press **Home** to verify Nuvio opens. If the native Home setting works, no accessibility service is required.

If your TV still needs the accessibility override, open **Settings → System → Accessibility** (or **Device Preferences → Accessibility**) and enable Nuvio. You can also open this screen from **Nuvio → Settings → Default launcher → Home Button Override (Accessibility)**. Unlocking restricted settings only allows you to enable the service; it does not turn the service on itself.

#### Method B: traditional ADB without a pairing PIN

Use this method if the TV provides traditional **ADB debugging** / **Network debugging** with an on-screen authorization prompt instead of a pairing code. This is common on older Android TV devices and supported Fire TV firmware. Port `5555` is common but is not guaranteed to be available on every device.

**1. Enable Developer options and network debugging**

1. Go to **Settings → Device Preferences** (or **System**) **→ About** and press **Build** **7 times**. On Fire TV, go to **Settings → My Fire TV → About** and press the device name 7 times.
2. In **Developer options**, turn on **USB debugging** or **ADB debugging**, plus **Network debugging** if it is listed separately.
3. Find the TV's IP address under **Settings → Network & Internet**, or **My Fire TV → About → Network** on Fire TV.

**2. Connect directly**

```powershell
./adb connect IP_ADDRESS:5555
```

Look at the TV immediately and accept **Allow USB/Network debugging?** Only select **Always allow from this computer** if you trust the computer.

Verify the connection:

```powershell
./adb devices
```

The TV should appear as `IP_ADDRESS:5555` with status **device**. If it is **unauthorized**, accept the TV's authorization prompt. If this firmware does not expose network ADB, this method cannot be used.

**3. Unlock restricted settings and select Nuvio as Home**

If **exactly one device** is connected, you can omit `-s`. Otherwise, add `-s IP_ADDRESS:5555` after `./adb` in each command.

Only if restricted settings are present and blocking accessibility, run:

```powershell
./adb shell appops set com.nuvio.tv ACCESS_RESTRICTED_SETTINGS allow
```

Skip this unlock command on devices without restricted settings. An **unknown operation** response means the firmware does not support this app-op.

Set the default Home activity:

```powershell
./adb shell cmd package set-home-activity com.nuvio.tv/.MainActivity
```

Some Fire TV and other vendor firmware blocks replacement Home apps or this command. A successful install or ADB connection does not guarantee the TV will honor a third-party Home selection. Do not disable or uninstall the stock launcher to work around a failed command.

**4. Finish on the TV**

Press **Home** to verify Nuvio opens. If needed, open the TV's **Accessibility** settings and enable the Nuvio launcher service, or use **Nuvio → Settings → Default launcher → Home Button Override (Accessibility)**.

### Accessibility and final checks

- The release app's actual accessibility component is **`com.nuvio.tv/com.nuvio.tv.core.launcher.LauncherAccessibilityService`**, not `com.nuvio.tv/.AccessibilityService`.
- Prefer enabling the service in the TV UI. Do **not** blindly run `settings put secure enabled_accessibility_services ...`: that setting replaces the entire enabled-service list and can disable TalkBack or other accessibility services. The UI preserves those services.
- Test **Home** from another app and from a screen inside Nuvio. Test **double-Home** with multiple profiles or a PIN-protected profile. Some firmware reserves double-Home for a system action.
- To return to the original launcher, select it in the TV's **Default apps / Home app** settings and turn off Nuvio's accessibility override if enabled.
- When finished, disconnect your ADB session and turn off network/wireless debugging if you do not need it. Do not expose ADB ports to the internet.

## Build from source

```bash
git clone https://github.com/Br0ck25/NuvioTVLauncher.git
cd NuvioTVLauncher
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
