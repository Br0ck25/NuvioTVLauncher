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

**Nuvio TV Launcher** is an independent fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV). It adds Android TV home-screen launcher support, an Apps screen for your installed apps, a shortcut to Android Settings, and an option to make the Home button open the app. With the launcher accessibility service enabled, HOME always returns to the top of Home, and double-tapping HOME opens the profile switcher.

This project is **not affiliated with, endorsed by, or supported by** the Nuvio team or NuvioMedia. Nuvio and its logo belong to their respective owners. Please report problems with this fork in this repository, not to the original project.

Account sign-in and QR-code login rely on the original project's hosted services, which this fork does not have access to. Choose **Continue without account** to use the app.

The website and support links above belong to the original Nuvio project.

## Get Nuvio TV Launcher

- [Android TV APK](https://github.com/Br0ck25/NuvioTVLauncher/releases)

## Build from source

```bash
git clone https://github.com/Br0ck25/NuvioTVLauncher.git
cd NuvioTVLauncher
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
