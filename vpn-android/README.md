# Android playback VPN

Nuvio One ships a built-in WireGuard VPN for playback. Open **Settings > VPN** to import a
profile, choose what it protects, and connect. No OpenVPN engine is built in this fork.

- **Off** uses the ordinary connection.
- **HTTP streams only** protects remote HTTP media. HTTPS stays direct.
- **HTTP + HTTPS streams** protects both playback protocols. HTTPS keeps its original TLS session.
- **Full app** uses Android VPN consent and restricts the VPN interface to the Nuvio package.

Matching playback waits for authentication and a tunneled HTTPS probe. Media requests,
redirects, byte ranges, subtitles and preloads follow the selected mode. Full app protection
starts while Nuvio is visible, and resumes on foreground startup after the active Nuvio profile
is known. An explicit VPN settings change may request Android consent. Passive startup and
resume only connect with consent already granted. This is foreground activation, not Android
always-on VPN.

## Profiles

Import any standard WireGuard `.conf`, including NordVPN's manual WireGuard configuration files.
There are two ways to add one:

1. **Add from phone** (recommended): Settings > VPN > Add from phone shows a QR code and a
   one-time address on the home network. Scan it with a phone on the same Wi-Fi, choose the
   `.conf` file and tap **Send to TV**. The private key is never placed in the QR code.
2. **WireGuard .conf file**: choose a file already on the TV with the system file picker.

The TV validates the profile, encrypts it with the Android Keystore and stores it on the device.
Imported profiles are not selected or connected automatically.

Setup Sync can carry VPN profiles to another TV. The portable backup contains the configurations,
the selected profile and the protection scope; the destination validates and re-encrypts them with
its own key. Older backups without a VPN entry keep the local setup.

## Integration

- `PlaybackVpn.kt` gates player startup and wraps the player's HTTP data sources.
- Live TV, Multiview and Stalker paths use `VpnHttpClient` / `VpnDataSources` so HTTP-only mode
  covers channel zapping, HLS pieces and portal playback.
- `VpnProfilePhoneServer.kt` serves the one-time phone page while the QR dialog is open. Nothing
  leaves the home network.
- Native HTTP/HTTPS players receive the local proxy through `applyVpnProxy`.

## Building

Use JDK 21, Go 1.27.3, NDK `29.0.14206865` and SDK CMake 3.22.1. Set `NUVIO_GO` when Go is not
on `PATH`:

```bash
NUVIO_GO=/path/to/go ./gradlew :app:assembleFullDebug
```

The Go module pins WireGuard and its private IP stack and produces
`libnuvio-http-vpn.so` for each ABI. The WireGuard tunnel AAR supplies `libwg-go.so`. Dependency
notices ship in the APK.

## Verification

```bash
./gradlew :vpn-core:check
cd vpn-android/native && go test ./...
bash vpn-android/ci/check-profiles.sh
```

The profile check downloads the pinned WireGuard parser, exercises the real production parser
and does not need a live VPN. Runtime checks still need a real WireGuard endpoint: import a
profile, connect in HTTP-only and Full app modes, and confirm the tunneled probe succeeds before
playback starts.
