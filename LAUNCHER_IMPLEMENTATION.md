# Automatic Home support — implementation notes

See [pre-change audit](LAUNCHER_AUDIT.md) and [test/release status](LAUNCHER_TEST_REPORT.md).

## Startup, with no new screen

- Existing HOME/DEFAULT registration, app-icon aliases and singleTask identity are preserved.
- On a fresh, normal MAIN launch, MainActivity waits until resumed with window focus. Restored Activity sessions, content launches and deep links do not trigger an automatic request.
- `LauncherCapabilities` observes manufacturer/model/Android release/SDK, ROLE_HOME availability and holder state, the actual HOME resolver, Home settings discoverability, Accessibility authorization and current service binding.
- If native HOME is already selected, nothing is requested. An existing Accessibility authorization also suppresses an automatic prompt (even if the service is temporarily disconnected). A held role with a different resolver is recorded as a mismatch, not advertised as working native HOME.
- If eligible, the existing Android ROLE_HOME confirmation interface is requested **once per installation**. The attempted flag is committed before request creation/launch, so cancellation, failure or process death cannot cause a prompt loop. No automatic Home-settings or Accessibility-settings redirects occur.
- Existing Apps/Default Launcher settings actions remain available for deliberate retries. Clicking an already active default-Home action no longer opens redundant settings. Android's actual resolver, not RESULT_OK, determines success.
- On older firmware without ROLE_HOME, native HOME intent resolution and existing manual system Home selection still work where Android provides them. A settings handler being discoverable does not prove its UI permits a third-party default.

## Capability and restriction observations

`LauncherCapabilities.detect` is a snapshot, not a certification API. `HomeCapabilityPolicy` chooses:

1. NATIVE when the HOME resolver selects this package.
2. ACCESSIBILITY only when the exact service is authorized, live/configured, and its fallback remains opted in.
3. UNAVAILABLE otherwise. Ordinary app use remains available.

Restriction evidence is NONE_OBSERVED, ROLE_RESOLVER_MISMATCH, REQUEST_FAILED or UNKNOWN. A canceled dialog, unavailable role, sideloaded APK or manufacturer name alone does **not** prove restricted firmware. The snapshot stays local; no device/accessibility telemetry is added.

The runtime bound flag starts false in a new process, becomes true after service configuration, and resets on unbind/destroy. Process death naturally removes it. Binding means operational connection, **not proof that firmware delivers reserved HOME keys**. Android decides whether/when to restart an authorized service; Nuvio cannot promise recovery from a platform force-stop.

## Home dispatch and fallback

Existing gesture classification and profile/navigation code are unchanged: immediate single-Home, 120 ms duplicate floor, 450 ms double window, Home scroll reset and applicable profile switcher. Existing profile PIN/selection/remember-last-profile, ambient and inactivity/playback exemptions are preserved.

When native HOME is selected, Accessibility does not intercept the key or redirect launcher windows. Raw Activity keys still use the existing classifier where firmware delivers them.

When fallback is authorized:

- HOME DOWN/repeated DOWN are consumed without navigation; only uncanceled UP forwards a press.
- `HomeFallbackPolicy` drops duplicate/stale releases before starting an Activity. It is not another single/double classifier. Physical event uptime is preserved for MainActivity's existing classifier.
- While MainActivity is resumed, `HomeForegroundDelivery` invokes its existing press handler directly, avoiding startActivity/recreation entirely. Pause/destroy detach the callback; owner identity prevents an old instance detaching a replacement.
- Otherwise the existing explicit tagged NEW_TASK/CLEAR_TOP/SINGLE_TOP request reaches the singleTask activity.
- Firmware that withholds HOME keys can fall back to **actual declared HOME activities in the selected resolver's package**, not a fixed Google/TiVo package guess. This preserves support for discoverable OEM launchers without inventing untested manufacturer rules.
- Only matching Activity window transitions qualify, not all windows/dialogs in a launcher package. A window episode is latched, with a 400 ms post-launch guard. Duplicate events cannot turn into fake presses merely by waiting out a timer. Own/other app Activity windows rearm it; transient panels do not. Launcher events arriving while Nuvio is resumed are ignored.
- Window fallback is intrinsically weaker than native HOME/key filtering: it cannot reliably distinguish deliberately opening the selected launcher from pressing HOME, or reconstruct every physical double tap. Firmware hiding the launcher Activity identity cannot be safely guessed. See restrictions/test matrix.

## Respecting a later change of default

Once Nuvio has observed itself selected as native HOME, that observation is persisted. If the user later chooses the original/another launcher, an already-authorized service must **not** silently take HOME back. Fallback remains inactive in that case, including after process/service restart. Selecting the existing Home override action explicitly opts back into fallback; this only updates Nuvio's preference and opens Android settings, never grants Accessibility authorization. The service still passes through native HOME while Nuvio is selected.

Existing UI now distinguishes authorized-but-inactive from an active fallback, and refreshes when live service binding changes. No additional configuration screen or repeated instruction surface was added.

## Permissions and APK distribution

- No new permission, dependency, root, ADB, Play Store or Google Play Services requirement.
- Accessibility authorization stays with Android. Exact ComponentName parsing replaces simple-name substring matching; Android's global Accessibility enabled switch must also be on.
- The service only needs key/window-state metadata. Removed interactive-window/unimportant-view flags and window-content retrieval capability; no accessibility nodes or screen contents are read.
- Manifest adds visibility of ACTION_HOME_SETTINGS for capability observation, not permission to change a default.
- Removed hard-coded release signing password defaults. Supply existing NUVIO_RELEASE_STORE_FILE / KEY_ALIAS / KEY_PASSWORD / STORE_PASSWORD through environment or ignored local.properties. Do not put keys or passwords into source control.
- Release version is now 1.1.3 / 1084, increased from the audited baseline 1.1.2 / 1083. App ID, aliases and production signing identity are unchanged. Publisher must use the existing certificate for in-place updates and allocate a higher versionCode for subsequent releases. A newly signed/debug-signed APK is not an update for existing production installs.
- Global cleartext remains intentionally unchanged: current MediaBrowser servers default to HTTP, user addon/repository/badge URLs explicitly support HTTP, playback accepts arbitrary stream URLs, and device integrations use LAN/loopback HTTP. A fixed-domain allowlist cannot preserve that contract. HTTPS validation/trust anchors are not weakened. A future network-policy redesign requires separate product/testing scope.
- Existing preference-controlled Sentry initialization/privacy behavior remains unchanged. No new reporting backend or mandatory crash telemetry.

## Maintainer release gate

On a provisioned Android build host, run:

```sh
PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests -v
./gradlew testFullDebugUnitTest
./gradlew connectedFullDebugAndroidTest
./gradlew assembleFullRelease
```

Use the repository's existing CI/release secret provisioning, Android SDK 36 and configured toolchain. Verify every distributed ABI APK's certificate against the previous release and test in-place update (not uninstall/reinstall). Do not publish a test/debug certificate as a production update. None of these developer commands are end-user setup requirements.

A successful full APK build and both hardware sign-offs are still required; this patch is **not a certified final release**.
