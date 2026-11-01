# Launcher audit — before implementation

Date: 2026-11-01. Base: `6ba56b12aa4d3bbf09b4bbbfc6a21ef715552fea`.

## Scope and baseline

Inspected the repository structure, main/flavor manifests, Gradle configuration and release workflows; traced launcher helpers, MainActivity lifecycle/navigation, Apps/Default Launcher settings, profile/idle handling, accessibility XML/service, existing launcher tests, network security, application startup and crash reporting. This is a source audit, not hardware certification or a claim that every media/player implementation was dynamically tested.

Working tree was clean. Existing Python release-tool suite: **21 passed**. Attempted `./gradlew testFullDebugUnitTest --console=plain`: blocked before Gradle starts (no Java/JAVA_HOME). No Android SDK, adb, attached target devices or production signing material is provisioned. Gradle/Maven/Android SDK download hosts are outside this sandbox's outbound allowlist. Android tests and APK build must not be represented as passed.

## Current implementation (already present, retain)

1. `MainActivity` is exported, `singleTask`, with a correct MAIN/HOME/DEFAULT filter. Six LAUNCHER/LEANBACK_LAUNCHER icon aliases target that same activity. HOME queries already support Android 11 package visibility. No manifest rewrite is needed.
2. `LauncherSetup` already implements API 29+ `RoleManager.ROLE_HOME`, availability/held checks, HOME resolver-based default detection and native Home settings. APK sideloading is not used to disqualify native HOME. Requests are currently **manual**, via shared Compose state in Apps and Settings, not startup. Role results correctly recheck the resolver instead of trusting RESULT_OK.
3. `LauncherAccessibilityService` already requests key filtering, consumes HOME DOWN/UP, forwards uncanceled UP timestamps in explicit single-task intents and redirects known stock-launcher window events. XML binding permission/export/configuration are present. This is an authorized fallback; Android does not guarantee HOME key delivery to accessibility services.
4. `LauncherHomeRequest` recognizes native HOME and tagged fallback intents. `MainActivity` funnels intents and raw keys into **one** `HomeKeyGesture` (120 ms duplicate floor, 450 ms double window). Immediate single press resets Home scroll/navigation; double also requests the existing profile picker. Cold native HOME seeds timing without bypassing existing startup. Recreation avoids replaying the cold intent.
5. Navigation already pops to Home or single-top/restores it, deferring scroll reset until Home is visible. Profile picker applicability, PIN/remember-last-profile semantics, ambient slideshow, playback exemptions and inactivity return are implemented. These must remain unchanged.
6. Lifecycle refreshes default/enabled status on resume; application startup performs existing sync/plugin/diagnostics work. Boot receiver schedules TV-channel work; it is not a permission bypass or forced boot launcher.

## Verified defects / gaps

- `handlesHome` equates accessibility **enabled in settings** with operational service, even when killed/unbound. UI likewise advertises an active override from settings alone.
- Enabled-service parsing has a substring fallback matching *any* component containing the service's simple name. This can falsely identify another package's service.
- No capability snapshot distinguishes role support, role holder, resolver default, settings discoverability, enabled authorization and live binding. No evidence-based restriction reporting.
- The service's window fallback uses a fixed package list (no TiVo discovery), ignores activity class and redirects every window event after 400 ms. Launcher dialogs and repeated events can cause unwanted repeated navigation. It runs even when native HOME works.
- Raw key UP deliveries always call startActivity before Activity-level deduplication; the service should suppress duplicate releases before launching while retaining the Activity's single gesture classifier.
- No persistent one-time native-role attempt at normal foreground startup. Existing manual UI remains useful for deliberate retries, but must not become a separate required wizard.
- Runtime service flags request interactive windows and unimportant views, while no code reads window content/nodes. This is unnecessary accessibility data access.

## Device differences and limits

There are no explicit Chromecast/TiVo model branches. Google TV packages are hard-coded in the fallback; TiVo firmware is not directly covered. Manufacturer names alone do not establish whether the role picker, HOME key filter or launcher-window fallback works. Discover HOME activities and the actual selected resolver; record build/model/SDK. Treat role/request failures as observed restrictions, not proof all firmware on a brand is locked. Native settings resolving is only evidence of a handler, **not** proof the OEM exposes a usable picker.

Neither target is attached. No actual-firmware behavior, reboot, stock-launcher restoration or service-kill recovery has been verified in this session.

## Distribution/security review

- Full flavor is the existing sideloaded distribution (`com.nuvio.tv`); Play flavor is separate and need not be removed. No new Play dependencies are needed.
- SDK range is 24–36. Current release version is 1.1.2 / 1083. Preserve app ID/component identities. Release publisher must increment versionCode before shipping and use the same production certificate for in-place updates.
- Release signing reads env/local properties and ignored keystore. **Hard-coded signing password defaults exist in Gradle**; remove defaults without reproducing their values. Existing history cannot be scrubbed by a code patch; the maintainer should assess exposure. Do not replace the production key with a new key for existing installs. CI has an explicit debug-signing test option; such APKs are not production updates.
- Release workflows supply secrets; no secrets should enter reports or artifacts. Release R8/mapping configuration already exists.
- INTERNET, network state, APK install, microphone, TV channel, boot and media-service permissions serve existing functionality. Accessibility is protected by BIND_ACCESSIBILITY_SERVICE; no privileged/default-grant approach is appropriate.
- Cleartext is globally allowed with system plus ISRG trust anchors. The app supports arbitrary addon/stream/subtitle/custom server/LAN URLs. A fixed domain allowlist would break legitimate HTTP sources; retain global cleartext until the supported user-defined URL contract changes. HTTPS certificate validation must remain enabled. No safe broad cleartext restriction is established by this audit.
- Sentry automatic manifest initialization is disabled. Application initializes reporting through existing user preference/DSN controls, strips request/user data and disables screenshots/view hierarchy. Preserve this; do not add capability telemetry or accessibility content collection.

## Implementation boundary

Add capability observation and a persistent one-shot native prompt using Android's UI only. Never auto-open Accessibility or Home settings. Keep manual controls in existing screens. Improve fallback delivery/deduplication and live status without changing gesture/navigation/profile semantics. Discover selected Home components rather than invent TiVo-specific firmware rules. Add tests and a hardware test matrix with truthful blocked/not-run outcomes. No compatibility certification or final release until both devices and production-signed APK are validated.
