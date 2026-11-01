# Launcher validation and remaining release gates

Date: 2026-11-01. Branch: `arena/4ae52f04-nuviotvlauncher`.

**Status: provisional implementation; release build and hardware certification blocked. No release APK produced.**

## Automated execution

| Check | Before modifications | After modifications |
|---|---|---|
| Existing Python release-tool suite | 21 passed | 21 passed |
| Full `testFullDebugUnitTest` | Blocked: no Java initially | Blocked: Gradle distribution download fails after isolated Java installation |
| Focused launcher JVM tests | Initial full Gradle baseline blocked | **31 passed**, details below |
| Full `assembleFullRelease` | Not a baseline requirement | Attempted; blocked at Gradle distribution download, no APK |
| Android instrumentation / Compose / all layouts | No connected Android runtime | Not run |
| Hardware tests (both target devices) | No devices attached | Not run |
| XML parsing / diff whitespace | Source inspection | Passed |

### Focused tests actually executed

Used isolated, untracked tooling under `/home/user/.cache/launcher-tools`: Java runtime 17.0.9 (jdk4py), Kotlin compiler 2.1.20 obtained through npm, Android API 35 stub jar from Sable/android-platforms via GitHub, compiler-bundled coroutines, and **JUnit 4.10** plus Hamcrest 1.3 from upstream Hamcrest/JUnit repositories. The project's normal Gradle test dependency remains JUnit 4.13.2 and no project dependency was changed.

Compiled all `core/launcher/*.kt` sources against Android stubs with temporary MainActivity (plain Activity) and RequiresApi annotation placeholders. This checks launcher helper/service Kotlin and Android API references, **not the real MainActivity, Compose screens, manifest merging, R8 or full application**. The placeholders were only external test tooling and were not added to application sources. No Android framework calls were executed on the JVM.

JUnitCore executed:

- `HomeKeyGestureTest`: 6 existing tests — boundaries, immediate single, double, duplicate/stale events.
- `LauncherManifestTest`: 5 tests — existing shared task/alias identities plus HOME/DEFAULT/MAIN/export and protected, least-data Accessibility config.
- `HomeCapabilityPolicyTest`: 4 tests — native precedence, enabled vs bound state, no automatic prompt for existing/unsupported choices, one-shot prompt policy.
- `HomeFallbackPolicyTest`: 7 tests — DOWN/repeated DOWN/canceled UP, release deduplication, rapid presses, launcher window latching/debounce, fresh service guard and Activity gesture continuity.
- `HomeForegroundDeliveryTest`: 4 tests — direct resumed delivery, detach on pause/destroy, replacement owner safety, shared double-Home classification path.
- `LauncherCapabilitiesTest`: 5 tests — brand-independent policy, resolver/role mismatch, request failure evidence, disconnected authorization, respecting a changed native default.

Result: `OK (31 tests)`. This is focused policy/data/XML coverage, **not a full Android regression pass or a live profile-picker/navigation test**.

The existing MockK-backed `LauncherHomeRequestTest` and `LauncherSetupTest` remain in place but could not be executed through Gradle here. New `LauncherCapabilityPlatformTest` instrumentation checks compare snapshot observations to actual Android role/resolver/settings state, reject other-package service-name matches, verify discovered HOME components, and verify persistent native-selection/fallback preferences using an isolated preference namespace. These four instrumentation tests are **not run**.

### Build blocker

Initial wrapper attempt failed because JAVA_HOME/java were missing. An isolated Java runtime was subsequently provisioned without changing project dependencies. Both Android tests and the release build then fail downloading:

`https://services.gradle.org/distributions/gradle-8.13-bin.zip`

with `SSLHandshakeException: Remote host terminated the handshake`. This host and required Google/Maven/SDK repositories are outside the sandbox network allowlist. No Gradle distribution, Android SDK or production keystore is provisioned. The build did not reach Android compilation/signing. Do not treat focused Kotlin compilation as a successful APK build.

## Physical device test report

Neither Chromecast with Google TV 4K nor TiVo Stream 4K is connected or accessible to this sandbox. Firmware versions and build fingerprints are **unknown**. No hardware outcome may be inferred from source, policy tests or compilation.

Before sign-off record for **each physical unit**: manufacturer/model, firmware/build fingerprint, Android release/SDK, APK versionCode/SHA-256/certificate fingerprint, role availability/held state, selected HOME component/candidate activities, Home settings behavior, Accessibility enabled/bound state, and test date/operator. Repeat after a firmware upgrade; model names alone are insufficient.

| Required case | Expected behavior / observation | Chromecast 4K | TiVo Stream 4K |
|---|---|---|---|
| Fresh normal APK install/open | Existing UI; no new onboarding; at most one native role confirmation if eligible | NOT RUN | NOT RUN |
| Native picker visibility | Record whether Nuvio is listed; distinguish available role from usable OEM UI | NOT RUN | NOT RUN |
| Accept native role; service disabled | HOME from another app returns to existing Nuvio task | NOT RUN | NOT RUN |
| Deny/cancel role | No repeated prompt on focus/resume/reopen/reboot; ordinary app remains usable | NOT RUN | NOT RUN |
| Native already selected before update | No redundant role request/instructions | NOT RUN | NOT RUN |
| HOME inside Nuvio | Home route at top from detail/search/settings/player, no recreation | NOT RUN | NOT RUN |
| Double HOME | Applicable existing profile picker, including first press from external app | NOT RUN | NOT RUN |
| Burst/hold/cancel HOME | No multiple tasks, false taps, corrupt stack or stuck key consumption | NOT RUN | NOT RUN |
| Accessibility unauthorized | Native/ordinary app functionality only; no claim of interception or settings loop | NOT RUN | NOT RUN |
| Authorized fallback key path | HOME returns to Nuvio; repeated DOWN/canceled UP do not navigate | NOT RUN | NOT RUN |
| OEM window-only fallback | Record exact declared activity/events; no duplicate/window-dialog loops; record double-tap limits | NOT RUN | NOT RUN |
| Stock launcher deliberately selected again | Remains usable; previous native selection must not be silently overridden by the service | NOT RUN | NOT RUN |
| Explicitly choose fallback again | Existing Home override action permits deliberate authorized fallback; never grants permission | NOT RUN | NOT RUN |
| Reboot | Native selection retained; Android restores authorized service where supported; no prompt loop | NOT RUN | NOT RUN |
| OS service/process kill and rebind | Bound state accurate; native unaffected; fallback recovers when Android rebinds | NOT RUN | NOT RUN |
| Force-stop separately | Record Android-imposed loss of restart/default/service behavior; do not conflate with OS reclaim | NOT RUN | NOT RUN |
| In-place production APK update | Same certificate, higher versionCode, retained profiles/preferences/default/authorization | NOT RUN | NOT RUN |
| Restricted settings (API 33+, if present) | Android approval remains required; no bypass or automatic settings redirect | NOT RUN | NOT RUN |

### Layout and profile regression matrix (both devices)

For **Modern, Grid and Classic**, repeat single/double/burst HOME from top, scrolled rows, detail, search, Settings, internal playback and external playback return. Exercise all six icon aliases against the same task. Verify restored scroll resets only when requested; no additional Activity/task is created.

For each layout exercise: one profile (no PIN), multiple profiles, PIN-protected profile, remember-last-profile on/off, manual profile change, picker already visible, idle-profile-switch on/off, ambient slideshow, long foreground/background idle and internal/external playback idle exemptions. Existing profile-selection/PIN logic must not be bypassed by a cold HOME launch. These visual/navigation/profile cases are **NOT RUN**; their production code was preserved, not certified by policy tests.

## Remaining Android/manufacturer restrictions

1. No app can silently grant ROLE_HOME, Accessibility authorization or restricted-settings approval. Consent stays in Android's interfaces. No root/ADB/device-owner workaround was added.
2. OEMs can advertise ROLE_HOME/settings but suppress or deny a third-party choice. API availability or RESULT_OK is not proof HOME was redirected; resolver observation is used, and hardware verification is still necessary.
3. Android normally reserves HOME. An authorized and bound service may receive no HOME keys, or another filtering service may take precedence. Bound state is not verified interception.
4. Window-only fallback has no physical key identity, cannot safely infer every double press, and can mistake deliberate opening of the selected launcher for HOME. If firmware hides/mislabels the declared Home activity, fallback may be unavailable rather than guess and hijack arbitrary windows.
5. Android 13+ sideload installation paths/firmware can require App info → Allow restricted settings before Accessibility can be authorized. This is a platform restriction; the app cannot bypass it. Native HOME does not require Accessibility.
6. Android/OEM background-start, reboot, force-stop, battery and service-rebind policy can prevent or delay fallback. No watchdog, notification foreground service, security bypass or forced boot launch is introduced.
7. Chromecast-specific and TiVo-specific firmware differences remain **unmeasured**. There is no justified vendor-specific workaround or support guarantee yet.
8. Update compatibility requires the existing production certificate and increasing versionCode. Signing passwords now require external provisioning rather than hard-coded defaults; validate that release outputs are actually production-signed before publishing. Historical credential exposure requires maintainer review; changing a production key arbitrarily would break updates.
9. Cleartext remains for arbitrary user HTTP/LAN sources; this is documented residual network exposure, not silently removed at the expense of existing functionality.

## Completion gate

Still required: provision the standard Android build environment and signing inputs; pass the full unit/instrumentation suites and release build; run the entire matrix on **both physical devices**; record firmware-specific failures separately; verify production-signed in-place update. Until then deliverables 6 and hardware validation in 7 remain incomplete, and the user's final-release definition of success has **not** been met.
